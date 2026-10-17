# 阿拓 v1.0.0 账号/登录域 —— 设计定稿（Decision Record）

> 依据：飞书《v1.0.0 后端服务技术设计》(doc_id `RuW4doONNoscjlxt2kWcfwL7nyh`)
> 状态：已定稿，可进入开发
> 配套产物：`docs/schema.sql`（DDL）、`docs/openapi.yaml`（接口契约）

## 一、本次确认的产品决策

- P-1 多端策略：**同端互踢、跨端共存**。踢除维度为 `platform`；同一 platform 下新登录，把该用户该 platform 的旧 session 全部 `revoked=1`（含不同 device_id）。mobile/pad/web/desktop 之间互不影响。
- P-2 用户 ID：**雪花算法 int64，内部与对外统一使用 `user_id`**。删除 `user_inner_id` 概念，不再用自增主键做关联。
- P-3 其余 P0/P1 项：按本文档方案定稿。

## 二、P0 逐条裁决

### D1 会话踢除规则
- 维度：`platform`（同端互踢）。
- 登录成功：`UPDATE auth_session SET revoked=1 WHERE user_id=? AND platform=? AND revoked=0`，再插新行。
- 刷新不触发踢除。
- `device_id` 不再用作踢除维度，仅用于列表展示与审计。

### D2 Token 刷新：原地更新 + Redis 重放检测
1. 刷新请求先 `SELECT ... FOR UPDATE` 锁定 session 行（防并发）。
2. 校验 `revoked=0` 且 `now < expire_at`。
3. 重放判定：
   - 旧 token 哈希命中 `rt:used:{hash}` 且超出宽限期 → 判定泄露 → 该 user 全端 `revoked=1`，返回 40101。
   - 命中 `rt:grace:{hash}`（30s 内）→ 幂等返回缓存的同一对 token（解决 App 并发/重试误踢）。
4. 正常刷新：生成新 refresh_token，更新本行 `refresh_token_hash`、`expire_at=now+30d`、`last_refresh_at=now`。
5. 写 Redis：`rt:grace:{old_hash}` → 新 token 对（TTL 30s）；`rt:used:{old_hash}` → 1（TTL 30d）。

> 既保留"旧 token 复用即泄露"的检测能力，又避免 session 表无限增长。

### D3 ID 与序列化
- 所有表主键、`user_id` 均为**雪花 int64**，不用 `AUTO_INCREMENT`。
- `auth_session.id` 即对外 `session_id`（不暴露自增业务量）。
- **所有 int64 ID 在 HTTP 响应中序列化为字符串**（JS 安全整数上限 2^53，雪花 ID 会精度丢失）。
- `worker_id` 由环境变量注入；需实现时钟回拨保护。

### D4 注销后 identifier 释放
采用**释放**（注销后手机号/unionid 可被重新注册）。注销时每条 `auth_credential`：
- `identifier` 密文清空
- `identifier_hash` 改写为 `dead_{id}`（让出唯一索引）
- `is_invalid = 1`（保留审计，不可再登录）

同时 `user_info.account_status=3`、`deleted_at=now`，该用户全部 session `revoked=1`。
可选风控（默认关闭）：注销后 N 天内禁止同一 identifier 注册，用 Redis 标记实现，不改表。

### D5 验证码 scene 枚举（统一）
`login` / `bind` / `change_phone`
Redis key：`sms:{scene}:{phone_hash}`，value 为 6 位数字，TTL 300s。

### D6 bind 接口定稿
第三方绑定**不由前端直传 identifier**（防止绑定他人 unionid），改为传 `auth_code` 由后端换取：
```json
{
  "auth_type": "wechat | apple | phone_code",
  "auth_code": "微信 code / Apple identityToken（第三方必填）",
  "phone": "13800138000（phone_code 必填）",
  "sms_code": "654321（phone_code 必填）"
}
```
字段名统一为 `sms_code`（原 `phone_code` / `code` 混用废弃）。

### D7 登出鉴权
- `Authorization` 与 body `refresh_token` **至少提供一个**，Header 优先。
- 两者都无 → `code=40106`。
- 其余（无效/过期/已吊销/不存在）→ 一律返回成功，客户端清空本地存储。

### D8 设备列表
- `GET /api/v1/auth/session/list`（方法修正为 GET）。
- 分页 `page`(默认1) / `page_size`(默认20，上限100)。
- 条件：`user_id=当前 AND revoked=0 AND expire_at>now`。
- 同 platform 理论只有 1 条有效，仍按 `device_id` 去重防御，保留 `updated_at` 最新。
- `is_current` 由 JWT 的 `sid` 与 `session_id` 比对。

### D9 响应码规范
- HTTP 统一 **200**（仅框架级 400/404/500 例外）。
- `code` 为 **int**，`0` 成功；原 `401`/`402` 改为 `40100`（未登录/Token 无效）、`40004`（已注销）。
- `msg` 统一英文，成功固定 `success`。
- 时间统一 ISO8601 UTC：`2026-10-05T10:49:31Z`。

## 三、P1 安全设计

### D10 access_token 与即时失效
- JWT，**HS256**，密钥配置注入（`JWT_SECRET`，≥32 字节）。
- claims：`sub`(user_id 字符串)、`sid`(session_id 字符串)、`plt`(platform)、`jti`、`iat`、`exp`。
- access 7200s，refresh 30d。
- **即时失效**：鉴权中间件验签后校验 session 状态：Redis `sess:{sid}:revoked`（TTL=access 剩余有效期），未命中回源 DB 并回填；revoked=1 → 40100。

### D11 敏感字段存储
| 字段 | 方案 |
| --- | --- |
| `refresh_token` | 只存 SHA-256 哈希（`refresh_token_hash char(64)`），明文只返回客户端，不落库 |
| `identifier` | AES-256-GCM 信封加密，密文带 `key_id` 前缀支持轮换；`varchar(512)` |
| `identifier_hash` | `HMAC-SHA256(IDENTIFIER_HMAC_KEY, auth_type + ":" + 明文identifier)`，唯一索引与等值查询均走它（加盐防止手机号彩虹表反查） |
| 密钥管理 | `AES_KEY` / `IDENTIFIER_HMAC_KEY` / `JWT_SECRET` 全部由环境变量或 KMS 注入，不入库、不入 Git |

### D12 刷新并发保护
行锁（`FOR UPDATE`）+ 30 秒宽限幂等（见 D2），避免 App 并发/网络重试被误判为泄露而全端踢下线。

### D13 短信风控
- 手机号+场景：60s 冷却、10 次/天
- IP：20 次/小时；device_id：20 次/小时
- 验证码错误 5 次即作废（Redis 计数）
- 验证码 6 位数字，5 分钟有效；接口匿名可访问，风控在业务层实现

### D14 账号状态校验顺序
先校验凭证（换取 unionid/sub、校验验证码），**凭证通过后再判账号状态**，避免通过错误码枚举账号是否存在。
- 凭证无效 → 40001 / 40002
- 凭证有效但封禁 → 40005；已注销 → 40004；异常 → 40006

### D15 手机号换绑
- MVP 只校验新手机号验证码（不校验旧手机号，文档已确认）。
- 成功后**踢掉除当前 session 外的全部 session**（安全加固）。
- 预留开关 `change_phone_require_old_verify`，后续可要求旧号验证码或设备二次确认。

### D16 解绑规则（修订）
- 通用规则：**最后一条凭证禁止解绑**。
- 手机号**允许解绑**（前提：账号还有其它凭证），原文档"有手机号时不允许解绑手机号"按此修订，避免用户无法移除手机号。
- 解绑采用**软删**：`is_invalid=1`、`identifier` 清空、`identifier_hash` 改写为 `dead_{id}`，释放给其他账号绑定；不物理删除（保留审计）。
- 解绑不影响当前 session，不踢下线。

## 四、P2 / P3 定稿

- D17 AI 鉴权：定位为**内部服务接口**（供 AI 服务校验令牌），路径 `POST /api/v1/auth/ai_verify`，仅内网可达，需服务间密钥；返回 `user_id`/`session_id`/`is_valid`。
- D18 时区：DB `datetime` 统一存 **UTC**，API 返回 ISO8601 UTC。
- D19 补充接口：`GET /api/v1/user/me`（当前用户资料）、`POST /api/v1/user/profile/update`（改昵称/头像/简介/性别）。
- D20 状态字段职责：`user_info.account_status` 为唯一判据（1 正常/0 封禁/2 异常/3 注销）；`deleted_at` 仅记录时间；`auth_credential.is_invalid` 表示该凭证已失效（解绑或注销）。
- D21 技术栈建议（待最终确认）：MySQL 8.0 + Redis 7 + JWT；序列化层需配置 int64→string（Java 用 `@JsonSerialize(using=ToStringSerializer.class)`，Go 用 `json:",string"` 或 string 字段）。
- D22 表结构变更点汇总（相对原设计）：
  1. `user_info` 去掉自增 `id`，`user_id` 为 bigint 主键
  2. `auth_credential.user_id` 由自增 id 关联改为关联 `user_info.user_id`
  3. `auth_credential.secret` 改名为 `ext_info`，存第三方附加信息（Apple 邮箱、微信昵称头像等）
  4. `identifier` 长度 255 → 512（密文）
  5. 唯一索引 `uk_type_identifier` → `uk_type_identifier_hash(auth_type, identifier_hash)`
  6. `auth_session.refresh_token` → `refresh_token_hash char(64)`
  7. `auth_session` 新增 `last_refresh_at`；新增复合索引 `idx_user_platform(user_id, platform, revoked)`

## 五、遗留待产品确认（不阻塞开发）
1. 换绑是否加旧号二次确认（默认不加，留开关）
2. 注销后是否需要 N 天注册冷静期（默认无）
3. 单账号是否限制最多绑定凭证数（默认不限）
4. 新用户默认昵称/头像生成规则（默认：昵称「阿拓用户{user_id 后6位}」，头像空串）

## 六、冻结范围
- DDL：`docs/schema.sql`
- 接口契约：`docs/openapi.yaml`（13 个接口）
- 冲突项澄清：`docs/atot-backend-clarify.md`（历史记录，已被本文档裁决覆盖）

## 七、与飞书原文冲突项及处置（同步状态：仅落本地，暂不写回飞书）

> 按要求：冲突与修订先记录在本地文档，飞书原文保持不动，待确认后再回写。

| # | 飞书原文 | 本地修订 | 依据 |
| --- | --- | --- | --- |
| 1 | `user_info` 有自增 `id`，另设 `user_inner_id` | 去掉自增主键，`user_id`（雪花）为唯一标识，内部与对外统一使用 | 产品决策 P-2 |
| 2 | `auth_credential.user_id` 关联自增 id | 改为关联 `user_info.user_id` | P-2 连带影响 |
| 3 | `auth_credential.secret` | 改名为 `ext_info`，语义为第三方附加信息（Apple 邮箱、微信昵称头像） | 字段名歧义 |
| 4 | `identifier` varchar(255) 明文 | 改存 AES-256-GCM 密文，长度 512；等值查询走 `identifier_hash`(HMAC) | D11 |
| 5 | 唯一索引 `uk_type_identifier` | 改为 `uk_type_identifier_hash(auth_type, identifier_hash)` | D11 |
| 6 | `auth_session.refresh_token` 存明文 | 改为 `refresh_token_hash char(64)`（SHA-256），明文不落库 | D11 |
| 7 | 登录时按 `device_id` 踢同设备 | 改为按 `platform` 同端互踢，`device_id` 仅用于展示与审计 | 产品决策 P-1 |
| 8 | 刷新接口未定义并发/重放处理 | 新增原地更新 + 30s 宽限幂等 + 超窗重放全端踢线 | D2 |
| 9 | 解绑规则为"有手机号时不允许解绑手机号" | 改为通用规则"最后一条凭证禁止解绑"，手机号在尚有其它凭证时允许解绑 | D16 |
| 10 | `POST /auth/session/list` | 改为 `GET`，并补 `page` / `page_size` | D8 |
| 11 | 错误码使用 `401` / `402` | 改为 `40100`（未登录）/ `40004`（已注销）等 int 业务码 | D9 |
| 12 | 响应未提及 int64 精度 | 明确所有 int64 ID 序列化为字符串 | D3 |
| 13 | 未定义换绑/绑定的字段命名 | 统一 `sms_code`；绑定改传 `auth_code` 由后端换取 identifier | D6 |
| 14 | 缺少当前用户资料接口 | 补充 `GET /user/me`、`POST /user/profile/update` | D19 |
| 15 | `ai_verify` 定位不清 | 明确为内网服务接口，需服务间密钥 | D17 |

回写飞书时的建议方式：在原文对应位置插入「修订说明」批注，不要直接覆盖历史版本，保留讨论痕迹。
