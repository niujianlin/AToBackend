# 核心流程时序与边界（v1.0.0）

> 配套：`atot-backend-decisions.md`（裁决）、`schema.sql`（DDL）、`openapi.yaml`（契约）
> **MVP 范围（本次已确认）**：登录只实现**手机号验证码登录**；微信 / Apple 的表结构、字段、契约全部保留，代码留扩展点，v1.1 实现。

图例：`→` 调用　`⇒` 读写存储　`★` 关键校验　`!` 高风险点

---

## 1. 短信验证码下发 `POST /api/v1/auth/sms_code`

```
Client → AuthController.smsCode(phone, scene, ip, deviceId)
  1. 参数校验（手机号格式、scene 枚举）
  2. 风控：phone+scene 60s 冷却 / 10次每日；ip 20次/h；deviceId 20次/h
  3. 生成 6 位随机码 ⇒ Redis SET sms:{scene}:{phone_hash} EX 300
  4. 发送（MVP：SmsSender 接口 + LogSmsSender 实现，生产接短信商）
Client ← {code:0}
```

| 场景 | 处理 |
| --- | --- |
| 冷却中 | 40051，**不消耗**每日额度 |
| 超日/时限额 | 40051 |
| 与已存在验证码重复请求 | 覆盖旧值，TTL 重置为 300s |
| 发送失败 | 40052，删除已写入的 Redis key（避免用户拿不到码却能验证） |
| 手机号未注册 | 正常返回 0，不泄露账号是否存在 |

★ scene 隔离：`login` / `bind` / `change_phone` 三个 namespace，码不可混用。

---

## 2. 手机号登录（含自动注册）`POST /api/v1/auth/login`

```
Client → AuthController.login(auth_type=phone_code, phone, auth_code, platform, deviceId)
 1. ParameterCheck：phone 格式、auth_code 6 位
 2. ★ 校验验证码 ⇒ Redis GET sms:login:{phone_hash}
     - 不匹配 → 错误计数 INCR sms:err:{phone_hash}（≥5 直接 DEL 验证码）→ 40002
 3. 校验通过 ⇒ DEL 验证码（一次性）
 4. identifier_hash = HMAC(auth_type + ":" + phone)
 5. ⇒ SELECT * FROM auth_credential WHERE auth_type='phone_code' AND identifier_hash=? 
 6. 分支 A 未命中（新用户）：
      a. 开启事务
      b. user_id = snowflake.nextId() ⇒ INSERT user_info（nickname="阿拓用户"+后6位, avatar=""）
      c. ⇒ INSERT auth_credential(identifier=AES密文, identifier_hash, is_invalid=0)
      d. 若并发插入撞唯一索引 uk_type_identifier_hash → 回滚，重查（转分支 B）
 7. 分支 B 已命中（老用户）：
      a. is_invalid=1 → 40001（凭证已失效）
      b. ⇒ SELECT user_info ⇒ ★ 判 account_status：0→40005 / 2→40006 / 3→40004
 8. ⇒ UPDATE auth_session SET revoked=1 WHERE user_id=? AND platform=? AND revoked=0   ← 同端互踢
 9. 生成 refresh_token（32B 随机 hex）、access_token（JWT: sub/sid/plt/jti/iat/exp）
10. ⇒ INSERT auth_session(id=雪花, user_id, credential_id, refresh_token_hash=sha256(rt),
        platform, auth_type, device_id, device_name, ip_addr, expire_at=now+30d, revoked=0)
11. ⇒ Redis SETEX sess:{sid}:revoked 0 TTL=access 剩余时间
Client ← {access_token, refresh_token, access_expire=7200, refresh_expire=2592000, user_info{is_new_user}}
```

! 第 8、10 步必须在**同一事务**内，否则并发登录会出现两条有效会话（违反同端唯一）。
! 同端并发登录：行锁 + `idx_user_platform` 下的 UPDATE 会串行化，最终保留后到者。

| 边界 | 结果 |
| --- | --- |
| 验证码错误 1~4 次 | 40002，验证码仍有效 |
| 验证码错误第 5 次 | 40002 + 立即作废验证码（需重新下发） |
| 验证码正确但已过期 | 40002 |
| 账号封禁 / 异常 / 已注销 | 40005 / 40006 / 40004（★ 仅在验证码通过后判定，防枚举） |
| 新用户自动注册成功 | `is_new_user=true`，昵称「阿拓用户{user_id 后 6 位}」 |
| 不同 platform 登录 | 互不影响，各自保留一条会话 |
| 同 platform 二次登录 | 旧会话 revoked=1，旧 access_token 最长 2h 内失效（见 §5），旧 refresh 立即失效 |
| 并发撞唯一索引 | 捕获 DuplicateKeyException → 回滚 → 重查凭证（幂等） |

---

## 3. 刷新 Token `POST /api/v1/auth/refresh`

```
Client → AuthService.refresh(refresh_token)
 1. old_hash = sha256(refresh_token)
 2. ⇒ Redis GET rt:grace:{old_hash}
     - 命中 → 直接返回缓存的新 token 对（幂等，30s 内并发/重试安全）★
 3. ⇒ Redis GET rt:used:{old_hash}
     - 命中（超出宽限）→ ! 判定泄露 ⇒ UPDATE auth_session SET revoked=1 WHERE user_id=? 
        → 40101
 4. 事务开始：⇒ SELECT ... WHERE refresh_token_hash=? FOR UPDATE
 5. ★ 校验 revoked=0 且 now < expire_at，否则 40101
 6. ⇒ SELECT user_info ⇒ 判 account_status（3→40103 / 0→40104 / 2→40105）
 7. 生成新 refresh_token；⇒ UPDATE 本行 refresh_token_hash / expire_at=now+30d / last_refresh_at
 8. ⇒ Redis SETEX rt:grace:{old_hash} → 新 token 对 TTL 30s
    ⇒ Redis SETEX rt:used:{old_hash} → 1 TTL 30d
    ⇒ Redis SETEX sess:{sid}:revoked 0 TTL=新 access 剩余时间
 事务提交
Client ← 新 {access_token, refresh_token, expire}
```

! refresh_token **只存哈希**，明文不落库；日志与监控不得打印明文。
! 判定泄露时"全端踢下线"是**故意**的强安全策略，仅宽限期外命中才触发。

| 边界 | 结果 |
| --- | --- |
| 30s 内重复提交同一 refresh_token | 幂等返回同一对新 token（不报错、不泄密） |
| 超出 30s 再次使用旧 token | 40101 + 账号全端下线 |
| session 已 revoked / 已过期 | 40101 |
| 并发两台设备同时刷新 | 行锁串行，后者命中 grace 宽限，安全 |

---

## 4. 登出 `POST /api/v1/auth/logout`

```
 1. token 来源：Authorization Header 优先；无则取 body.refresh_token
 2. 两者皆无 → 40106
 3. 解析出 sid（Header 路径）或按 hash 查 session（body 路径）
 4. ⇒ UPDATE auth_session SET revoked=1 WHERE id=?
 5. ⇒ Redis SET sess:{sid}:revoked = 1（TTL = access 剩余有效期）
Client ← {code:0}  ← 无效/过期 token 也返回 0，客户端清空本地即可
```

| 边界 | 结果 |
| --- | --- |
| token 无效 / 已过期 / 会话不存在 | **返回 0**（幂等登出） |
| 无任何凭证 | 40106 |
| 重复登出 | 0 |
| 登出后立刻用旧 access_token 访问 | 40100（Redis 标记即时生效） |

---

## 5. 鉴权拦截（所有需登录接口）

```
请求 → AuthInterceptor
 1. 取 Authorization: Bearer xxx，缺失 → 40100
 2. JWT 验签 + exp 校验，失败 → 40100
 3. sid = claims.sid
 4. ⇒ Redis GET sess:{sid}:revoked（未命中回源 DB 并回填，TTL=access 剩余）
    - =1 → 40100
 5. ⇒ Redis GET sess:{sid}:plat → 与 claims.plt 比对（防跨端伪造）
 6. UserContext.set(userId, sessionId, platform)
```

! 这一步是把 access_token 的"2 小时真空期"压缩为"即时失效"的关键。
! Redis 不可用时策略：**fail-open 仅限读接口**，写接口 fail-close（避免误封全站）。MVP 先 fail-open + 告警。

---

## 6. 注销 `POST /api/v1/auth/logout_cancel`（本期实现）

```
事务 {
 1. ⇒ UPDATE user_info SET account_status=3, deleted_at=now() WHERE user_id=?
 2. ⇒ UPDATE auth_session SET revoked=1 WHERE user_id=?
 3. 遍历该用户 auth_credential：
      ⇒ UPDATE ... SET is_invalid=1, identifier='', identifier_hash='dead_{id}'
}
⇒ Redis 批量 SET sess:{sid}:revoked=1（各 sid，TTL 取剩余 access 时长）
Client ← {code:0}
```

| 边界 | 结果 |
| --- | --- |
| 已注销账号再次调用 | 40041 |
| 注销后同一手机号重新注册 | **允许**（identifier 已释放），生成新 user_id、新账号 |
| 注销用户旧 access_token | 40100（Redis 即时失效或 status=3 判定） |

---

## 7. 绑定 / 解绑 / 换绑（契约已冻结，v1.1 实现）

- 绑定：后端用 `auth_code` 换 unionid/sub，**禁止前端直传 identifier**；只插 credential，不建会话、不踢设备。
- 解绑：最后一条凭证禁止解绑 → 40030；软删释放 `identifier_hash = dead_{id}`；不影响当前会话。
- 换绑：MVP 只验新号验证码；成功后踢掉**除当前 session 外**的全部会话；旧手机号释放。

---

## 8. 幂等与并发矩阵

| 操作 | 并发风险 | 防护 |
| --- | --- | --- |
| 登录（同号同端） | 两条有效会话 | 同事务内 UPDATE 旧会话 + 唯一索引兜底 |
| 登录（新号并发） | 重复建号 | 唯一索引 `uk_type_identifier_hash` + 捕获后重查 |
| 刷新（多端/重试） | 误判泄露踢全端 | 行锁 `FOR UPDATE` + 30s grace 幂等 |
| 短信下发 | 刷接口 | 三层限流 + 冷却 |
| 注销 | 重复执行 | `account_status` 幂等判定 → 40041 |
| 踢设备 | 踢到当前会话 | 40071 显式拦截 |

---

## 9. MVP 实现范围

| 接口 | 本期 | 说明 |
| --- | --- | --- |
| `POST /auth/sms_code` | ✅ 实现 | 登录场景，mock 短信发送器 |
| `POST /auth/login` | ✅ 实现 | 仅 `auth_type=phone_code`；wechat/apple 返回"暂未开放" |
| `POST /auth/refresh` | ✅ 实现 | 含宽限幂等与泄露判定 |
| `POST /auth/logout` | ✅ 实现 | |
| `GET /user/me` | ✅ 实现 | |
| `GET /auth/session/list` | ✅ 实现 | 登录页设备管理需要 |
| `POST /auth/session/revoke` | ✅ 实现 | |
| `POST /auth/logout_cancel` | ✅ 实现 | |
| `POST /user/profile/update` | ✅ 实现 | 字段校验为主 |
| `POST /auth/bind` / `unbind` / `change_phone` | ⛔️ 留桩 | 返回 40099「not implemented in MVP」，契约不变 |
| `POST /auth/ai_verify` | ⛔️ 留桩 | 内网接口，后续接 AI 服务 |

> 建表 **不做裁剪**：`auth_credential` 保留 wechat/apple 列与 `ext_info`，v1.1 无需改表。
