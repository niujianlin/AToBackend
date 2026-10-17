# 用户中心 · 登录与多端登录后端设计

> 版本：v1.0　|　范围：用户身份认证、会话管理、多端登录策略　|　读者：后端 / 架构 / 测试

---

## 1. 背景与目标

用户中心是所有业务系统的统一身份入口，登录是其最核心、访问量最高的能力。随着业务覆盖 App、Web、小程序、桌面端、第三方开放平台等多种形态，**同一账号在多个终端同时存在**成为常态，因此"多端登录"不再是可选项，而是设计的一等公民。

本设计要达成的目标：

| 目标 | 说明 |
|---|---|
| 统一认证 | 所有业务方通过统一登录服务换取令牌，不再各自实现登录 |
| 多端共存 | 支持同一账号在 App / H5 / 小程序 / PC 等多端同时在线 |
| 可控的互斥 | 支持"同端互踢""按端限额""全部下线"等策略，策略可配置 |
| 会话可管理 | 用户可查看/踢出指定设备，支持设备级登出 |
| 安全可信 | 令牌防重放、防泄露、可失效、可追溯 |
| 高可用 | 支撑高并发登录、毫秒级鉴权 |

---

## 2. 总体架构

```
                ┌──────────────┐
   客户端 ─────▶│  API Gateway  │  (TLS 终止 / 限流 / 路由)
                └──────┬───────┘
                       │
              ┌────────▼─────────┐
              │   Auth Service    │  ← 登录/注册/刷新/登出/会话管理
              │  (用户中心认证域)  │
              └───┬─────────┬────┘
                  │         │
        ┌─────────▼──┐  ┌───▼────────┐  ┌──────────────┐
        │  MySQL/    │  │   Redis     │  │ 风控/Risk    │
        │ PostgreSQL │  │ (会话/令牌) │  │ (异地/异常)  │
        │ 用户/凭证  │  └─────────────┘  └──────────────┘
        └────────────┘         │
                  │            │
        ┌─────────▼────────────▼──────────┐
        │  MQ / 推送  (踢出通知、事件总线)  │
        └──────────────────────────────────┘
```

- **Auth Service 无状态化**：进程本身不保存会话，会话状态统一放 Redis，便于水平扩容。
- **数据库**存持久化用户数据；**Redis** 存高频读写的会话/令牌/风控计数。
- **事件总线（MQ）**：登录成功、被踢下线等事件通过 MQ 广播给业务方与推送服务。

---

## 3. 核心数据模型

### 3.1 用户表 `user`

| 字段 | 类型 | 说明 |
|---|---|---|
| `user_id` | BIGINT PK | 全局唯一用户 ID（雪花 ID） |
| `username` | VARCHAR(64) UNIQUE | 登录名（可空，支持纯手机号注册） |
| `nickname` | VARCHAR(64) | 昵称 |
| `avatar` | VARCHAR(255) | 头像 |
| `status` | TINYINT | 0 正常 / 1 禁用 / 2 注销中 |
| `created_at` / `updated_at` | DATETIME | 时间戳 |

### 3.2 凭证表 `user_credential`

用于"一个用户多种登录方式"。账号密码、手机号、邮箱、第三方 openid 都抽象为凭证。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT PK | |
| `user_id` | BIGINT | 关联用户 |
| `type` | TINYINT | 1 密码 / 2 手机号 / 3 邮箱 / 4 微信 / 5 Apple / 6 企业微信… |
| `identifier` | VARCHAR(128) | 账号标识（密码类型存 username，第三方存 openid） |
| `secret` | VARCHAR(255) | 密码哈希（bcrypt/argon2），第三方类为空 |
| `extra` | JSON | 扩展（unionid、scope 等） |
| `verified` | TINYINT | 是否已验证 |
| UNIQUE | (`type`,`identifier`) | 保证同类型标识唯一 |

### 3.3 会话表 `auth_session`（多端登录的核心）

**每一次成功登录 = 一条会话记录**，不同端/设备产生不同会话，互不影响。

| 字段 | 类型 | 说明 |
|---|---|---|
| `session_id` (`sid`) | CHAR(32) | 会话唯一 ID（UUID），令牌中携带 |
| `user_id` | BIGINT | 归属用户 |
| `device_id` | VARCHAR(64) | 设备唯一标识（客户端持久化生成） |
| `platform` | TINYINT | 端类型：1 iOS / 2 Android / 3 Web / 4 小程序 / 5 PC / 6 OpenAPI |
| `device_info` | JSON | 机型、OS 版本、App 版本、IP 归属地 |
| `refresh_token_hash` | CHAR(64) | refresh token 的哈希（不存明文） |
| `login_ip` / `login_time` | | 登录来源 |
| `last_active_at` | DATETIME | 最近活跃时间，用于会话列表与闲置回收 |
| `expire_at` | DATETIME | 会话过期时间 |
| `status` | TINYINT | 1 有效 / 0 已登出 / -1 被踢 / -2 过期 |

> 存储策略：会话**热数据放 Redis**（key 见 §5），`auth_session` 表做持久化与审计、供用户端"登录设备列表"查询。

### 3.4 Redis Key 设计

| Key | 类型 | 说明 |
|---|---|---|
| `sess:{sid}` | Hash | 会话主体：user_id、platform、device_id、expire_at、status |
| `user:sess:{user_id}` | Set | 该用户所有活跃 sid 集合，O(1) 遍历用于踢人/统计端数 |
| `user:sess:{user_id}:{platform}` | Set | 按端维度分组，用于"同端互踢"限额 |
| `token:blacklist:{jti}` | String | 主动登出 / 踢出后，access token 剩余生命周期的黑名单（TTL=剩余过期时间） |
| `login:fail:{identifier}` | String | 登录失败计数（滑动窗口限流） |
| `sms:code:{phone}` | String | 验证码 |

---

## 4. 认证方案：双 Token 模型

采用 **Access Token + Refresh Token** 双令牌，兼顾安全与体验。

| 令牌 | 格式 | 有效期 | 存放 | 用途 |
|---|---|---|---|---|
| Access Token | JWT（无状态校验） | 短，15~30 min | 内存（Web 放内存，App 放安全存储） | 业务接口鉴权 |
| Refresh Token | 不透明随机串（服务端存储） | 长，7~30 天 | HttpOnly Cookie / 安全存储 | 换取新 access token |

**Access Token 载荷（JWT Claims）：**

```json
{
  "iss": "auth.user-center",
  "sub": "100238472",          // user_id
  "sid": "a1b2c3d4e5f6...",     // 会话 ID —— 多端互踢的关键
  "plt": 1,                     // platform
  "jti": "唯一令牌 ID",          // 用于黑名单
  "iat": 1730000000,
  "exp": 1730001800
}
```

**为什么带 `sid`：**
- 鉴权时可用 `sid` 快速判断该会话是否已被踢出/登出（查 Redis 或黑名单）。
- 实现细粒度失效：踢某个设备 = 删除对应 `sid` 的会话 + 把该会话下发的 token 拉黑。

**刷新采用 Rotation（令牌轮换）：**
- 每次 refresh 都签发**新的** refresh token，旧的立即作废。
- 若检测到**已作废的 refresh token 被再次使用**，判定为令牌泄露 → 直接注销该会话下所有令牌并告警。

---

## 5. 登录流程

### 5.1 账号密码登录（主流程）

```
Client                      Gateway/AuthService                Redis / DB
  │  POST /auth/login              │                               │
  │  {account, password, deviceId, platform}                       │
  │───────────────────────────────▶│                               │
  │                                │ 1. 限流校验 login:fail:xxx    │
  │                                │──────────────────────────────▶│
  │                                │ 2. 查 user_credential 校验密码 │
  │                                │ 3. 风控：异地/新设备校验       │
  │                                │ 4. 生成 sid，写会话 + Set 索引 │
  │                                │──────────────────────────────▶│
  │                                │ 5. 签发 access + refresh      │
  │  200 {accessToken, refreshToken, expiresIn, sid}               │
  │◀───────────────────────────────│                               │
```

步骤要点：
1. **失败限流**：同一账号/IP 连续失败 N 次锁定 M 分钟，并触发图形/短信验证。
2. **风控判断**：新设备、异地、异常频率 → 二次验证（短信/邮箱），验证通过才建会话。
3. **建会话 + 应用多端策略**（见 §6）。
4. 记录登录日志、发 `login.success` 事件。

### 5.2 其他登录方式

| 方式 | 流程要点 |
|---|---|
| 短信验证码 | 校验 `sms:code`，未注册则自动注册（静默注册） |
| 邮箱验证码 | 同上 |
| 第三方 OAuth | code → 换取 openid/unionid → 关联/创建 `user_credential` → 建会话 |
| 扫码登录 | PC 展示二维码带 `qrToken` → App 扫码确认 → 服务端将 `qrToken` 绑定 user → PC 轮询换令牌 |
| 单点登录 SSO | 统一在 Auth Service 建会话，各子系统通过内网 introspect 或共享密钥校验 JWT |

---

## 6. 多端登录设计（重点）

### 6.1 端类型与设备标识

- **platform** 区分端形态：`iOS / Android / Web / 小程序 / PC / OpenAPI`。
- **deviceId**：客户端首次启动生成并持久化（App 存 Keychain/Keystore，Web 存 localStorage），用于区分"同一端的多个设备"（如两部手机）。

### 6.2 四种登录策略（可配置，默认按端隔离）

| 策略 | 行为 | 典型场景 |
|---|---|---|
| **A. 多端共存** | 各端互不影响，允许多端多设备同时在线 | 默认、主推 |
| **B. 同端互踢** | 同一 platform 下，新登录踢掉旧会话（同端只保留一个） | 视频类账号防共享 |
| **C. 按端限额** | 每个 platform 最多保留 N 个会话，超出按最久未活跃淘汰 | 平衡体验与安全 |
| **D. 全端互踢** | 新登录踢掉该账号所有其他会话（单点登录） | 金融、企业后台 |

策略判定伪代码：

```python
def apply_login_policy(user_id, platform, device_id, policy):
    sessions = redis.smembers(f"user:sess:{user_id}:{platform}")

    if policy == "COEXIST":
        pass
    elif policy == "SAME_PLATFORM_KICK":
        for sid in sessions: kick(sid, reason="same_platform")
    elif policy == "LIMIT_N":
        if len(sessions) >= N:
            oldest = pick_oldest_by_last_active(sessions)
            kick(oldest, reason="limit_exceeded")
    elif policy == "GLOBAL_KICK":
        for sid in redis.smembers(f"user:sess:{user_id}"):
            kick(sid, reason="global_login")

    create_session(user_id, platform, device_id)
```

### 6.3 踢出（Kick / 强制下线）机制

`kick(sid, reason)` 的原子操作：

1. 从 `user:sess:{user_id}` 与 `user:sess:{user_id}:{platform}` 中移除 `sid`。
2. 删除 `sess:{sid}`；将 `auth_session` 表记录置为 `status = -1`。
3. 将该会话对应的 access token 加入黑名单：
   `SET token:blacklist:{jti} 1 EX <剩余有效期>`。
   （若 token 未带 jti，也可用"按 sid 维度的失效时间戳"方案：记录 `sid` 的失效时刻，鉴权时比对 `iat`。）
4. 通过 MQ / 长连接推送 `KICK_OUT` 事件，客户端收到后清理本地令牌并跳登录页。
5. 记录审计日志：谁、何时、因何被踢。

> 关键：**被踢的会话必须让 access token 立即失效**，不能等它自然过期——靠黑名单或 sid 级失效时间实现。

### 6.4 用户可见的会话/设备管理

提供接口让用户自助管理：

- 查看"登录设备列表"（读 `auth_session`，字段：设备名、端、登录时间、最近活跃、IP 归属地，当前会话打标 `current=true`）。
- 单独登出某设备（等价 `kick(sid, "user_manual")`）。
- "退出全部设备"（遍历 `user:sess:{user_id}` 全部踢出）。

### 6.5 一致性与并发注意点

- **同端并发登录竞态**：加分布式锁 `lock:login:{user_id}:{platform}`，保证"限额+踢旧+建新"原子。
- **Set 与实际会话漂移**：会话过期/Redis 淘汰后，`user:sess` 里可能有脏 sid，遍历时以 `sess:{sid}` 是否存在为准，惰性清理。
- **时钟**：统一以服务端时间为准，JWT 校验允许小幅时钟漂移（≤60s）。

---

## 7. 令牌生命周期管理

| 阶段 | 动作 |
|---|---|
| 签发 | 登录/刷新时生成 access + refresh，refresh 仅存哈希 |
| 续期 | access 过期 → 客户端用 refresh 换新 access（+ 新 refresh，轮换） |
| 主动登出 | 删除会话 + 拉黑 access + 作废 refresh |
| 被动失效 | 被踢 / 用户禁用 / 改密 / 注销 → 全量会话失效 |
| 回收 | 会话过期由 Redis TTL 自动清理；`auth_session` 表定时归档 |

**改密 / 重置密码**：默认使该用户**除当前会话外**的所有会话失效（可配置为全部失效）。

---

## 8. 安全设计

| 风险 | 措施 |
|---|---|
| 密码泄露 | bcrypt(cost≥12) / argon2id 加盐哈希，禁止明文与可逆加密 |
| 暴力破解 | 失败次数限流 + 锁定 + 验证码升级 |
| 撞库 | 设备/行为风控、异常 IP 识别、登录告警 |
| 令牌窃取 | access 短有效期、refresh 轮换 + 复用检测、HTTPS、refresh 存 HttpOnly Cookie |
| 重放攻击 | JWT 带 jti + 短时窗口；关键操作二次签名 |
| CSRF | Web 端 refresh 走 HttpOnly + SameSite=Strict；状态变更接口带 CSRF Token |
| XSS | 不用 localStorage 存长令牌（Web 优先内存+Cookie） |
| 越权 | 鉴权中间件统一校验 token → 注入 userId，业务层不再信任客户端传参 |
| 审计 | 登录、登出、踢人、改密全量日志，保留 ≥180 天 |

---

## 9. 接口设计（REST 参考）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/auth/register` | 注册（账密/手机验证码） |
| POST | `/auth/login` | 账密登录，返回双令牌 |
| POST | `/auth/login/sms` | 短信验证码登录 |
| POST | `/auth/login/oauth/{provider}` | 第三方登录 |
| POST | `/auth/refresh` | 刷新令牌（轮换） |
| POST | `/auth/logout` | 登出当前会话 |
| POST | `/auth/logout-all` | 退出全部设备 |
| GET | `/auth/sessions` | 当前账号的登录设备列表 |
| DELETE | `/auth/sessions/{sid}` | 踢出指定设备 |
| POST | `/auth/password/reset` | 重置密码（并失效相关会话） |
| GET | `/auth/userinfo` | 获取当前用户信息（需 access token） |

**登录请求 / 响应示例：**

```json
// POST /auth/login
{
  "account": "13800000000",
  "password": "******",
  "deviceId": "d-9f8e7d6c",
  "platform": 2,
  "deviceInfo": { "model": "MI 14", "os": "Android 14", "appVersion": "3.2.1" }
}

// 200
{
  "code": 0,
  "data": {
    "accessToken": "eyJhbGciOi...",
    "refreshToken": "rt_7f3a...",
    "expiresIn": 1800,
    "sid": "a1b2c3d4e5f6"
  }
}
```

**统一鉴权中间件：**

```
提取 Authorization: Bearer <accessToken>
  → 校验签名/过期 → 校验 jti 不在黑名单 → 校验 sid 会话有效
  → 通过：注入 userId/sid 到上下文
  → 失败：401（附带区分码：过期→可刷新 / 已踢出→强制重登）
```

---

## 10. 关键时序

### 10.1 多端登录 + 同端互踢

```
用户   新设备(端=Android)        AuthService              Redis
 │         │  login                │                       │
 │         │──────────────────────▶│  应用策略 SAME_PLATFORM_KICK
 │         │                       │  查到旧 sid_old        │
 │         │                       │──kick(sid_old)────────▶│ 删会话+拉黑
 │ 旧设备  │                       │──push KICK_OUT────────▶│  (长连接)
 │ 收到踢出提示，跳登录页 ◀─────────│                       │
 │         │  200 新令牌            │  建新 sid_new          │
 │         │◀──────────────────────│──────────────────────▶│
```

### 10.2 刷新令牌（Rotation + 复用检测）

```
Client                     AuthService                Redis
  │ POST /auth/refresh rt_old    │                       │
  │─────────────────────────────▶│ 校验 rt_old 是否有效   │
  │                              │ 若"已作废却被使用"→告警+注销会话
  │                              │ 签发 rt_new, 作废 rt_old
  │ {accessToken, refreshToken=rt_new}                    │
  │◀─────────────────────────────│                       │
```

---

## 11. 存储与选型小结

| 组件 | 选型建议 | 理由 |
|---|---|---|
| 用户/凭证/会话持久化 | MySQL 8 / PostgreSQL | 事务、关系约束 |
| 会话/令牌/限流 | Redis（主从 + 哨兵/集群） | 高并发读写、TTL 自动过期 |
| 令牌 | JWT（Access）+ 不透明串（Refresh） | 无状态校验 + 可控失效 |
| 事件/推送 | Kafka / RocketMQ | 解耦、广播被踢事件 |
| 风控 | 独立 Risk 服务 | 与认证解耦，独立演进 |

---

## 12. 落地建议与迭代路线

**MVP（第一阶段）**
1. 账密 + 短信登录，双令牌，Redis 会话
2. 多端共存策略，用户可查看设备列表并单独登出
3. 失败限流、密码加盐哈希

**第二阶段**
4. 同端互踢 / 按端限额 / 全端互踢（策略引擎化）
5. refresh 轮换 + 复用检测、踢出实时推送
6. 扫码登录、第三方 OAuth

**第三阶段**
7. 接入风控（异地/新设备二次验证）
8. SSO 打通各子系统、开放平台 OpenAPI 鉴权
9. 会话审计分析、异常登录告警、灰度与降级方案

**关键设计决策清单（Review 时重点确认）**
- [ ] Access Token 用 JWT 还是全不透明？（无状态 vs 可即时失效的权衡）
- [ ] 默认多端策略选哪种？是否需要按业务线差异化？
- [ ] 被踢会话的 access 失效方案：jti 黑名单 vs sid 失效时间戳？
- [ ] refresh token 存储位置：HttpOnly Cookie 还是客户端安全存储？
- [ ] 会话过期默认时长（端差异：App 长、Web 短）？
