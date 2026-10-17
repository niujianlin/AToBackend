# ato-account 阿拓 v1.0.0 账号/登录域服务

Spring Boot 3.2 + JDK 17 + MyBatis-Plus + MySQL 8 + Redis 7。

设计依据（与本仓库 `docs/` 同步）：
- `docs/atot-backend-decisions.md` —— 设计定稿与裁决（含与飞书原文的冲突处置）
- `docs/atot-backend-sequence.md` —— 核心流程时序与边界
- `docs/schema.sql` —— 三张表冻结版 DDL（本工程 `resources/db/schema.sql` 为同一份）
- `docs/openapi.yaml` —— 13 个接口契约

## 一、MVP 范围

本期**只实现手机号验证码登录**链路，其余接口保留契约、走 `40099 not implemented in MVP` 桩：

| 接口 | 状态 |
| --- | --- |
| `POST /api/v1/auth/sms_code` | 已实现（mock 短信，验证码打到日志） |
| `POST /api/v1/auth/login` | 已实现（仅 `auth_type=phone_code`，含自动注册、同端互踢） |
| `POST /api/v1/auth/refresh` | 已实现（30s 宽限幂等 + 重放泄露全端踢线） |
| `POST /api/v1/auth/logout` | 已实现 |
| `POST /api/v1/auth/logout_cancel` | 已实现（注销 + 凭证释放） |
| `GET /api/v1/auth/session/list` | 已实现 |
| `POST /api/v1/auth/session/revoke` | 已实现 |
| `GET /api/v1/user/me` | 已实现 |
| `POST /api/v1/user/profile/update` | 已实现 |
| `POST /api/v1/auth/bind` / `unbind` / `change_phone` | 桩（v1.1） |
| `POST /api/v1/auth/ai_verify` | 桩（内网 AI 鉴权，v1.1） |

建表**不做裁剪**：`auth_credential` 保留 wechat / apple 相关列与 `ext_info`，v1.1 无需改表。

## 二、目录结构

```
src/main/java/com/ato/account/
├── AtoAccountApplication.java
├── common/         统一响应、错误码、业务异常、全局异常处理
├── config/         配置项、Jackson（Long→String / snake_case）、拦截器注册
├── context/        请求级登录态（ThreadLocal）
├── controller/     AuthController、UserController
├── dto/            全部出入参（record）
├── entity/         user_info / auth_credential / auth_session
├── interceptor/    AuthInterceptor（JWT 验签 + Redis 即时失效）
├── mapper/         MyBatis-Plus Mapper
├── service/        AuthService / TokenService / SmsService / RegistrationService / UserService
└── util/           雪花 ID、AES-GCM/HMAC/SHA-256、JWT、Redis key、时间格式
```

## 三、环境与初始化

要求：JDK 17+、Maven 3.9+、MySQL 8.0、Redis 7。

### 本机已装好（2026-10-17，免 root，装在用户目录）

| 组件 | 版本 | 路径 |
| --- | --- | --- |
| Temurin JDK | 17.0.20.1+1（LTS，许可免费商用） | `~/.local/opt/jdk17` |
| Maven | 3.9.9 | `~/.local/opt/maven` |
| Maven 镜像 | 华为云（本机直连 repo1.maven.org 不通） | `~/.m2/settings.xml` |

`JAVA_HOME` / `PATH` 已写入 `~/.bashrc` 与 `~/.profile`，新开终端可直接用 `java` / `mvn`。
当前终端若未生效，用绝对路径：

```bash
export JAVA_HOME=$HOME/.local/opt/jdk17
export PATH="$JAVA_HOME/bin:$HOME/.local/opt/maven/bin:$PATH"
```

已实际验证：`mvn clean package -DskipTests` → **BUILD SUCCESS**，产物 `target/ato-account-1.0.0-SNAPSHOT.jar`。

### 本机已跑通的服务（免 root，无 systemd）

| 组件 | 版本 | 端口 | 路径 |
| --- | --- | --- | --- |
| MySQL Community | 8.0.29 | 3306 | `~/.local/opt/mysql`，库 `ato_account` |
| Redis | 7.2.5（源码编译） | 6379 | `~/.local/opt/redis` |
| libaio（补齐依赖） | 0.3.113 | — | `~/.local/opt/libaio` |

一键起停：

```bash
~/.local/opt/ato-services.sh start     # 启动 MySQL + Redis + 后端
~/.local/opt/ato-services.sh status    # 查看状态
~/.local/opt/ato-services.sh smoke     # 跑登录链路冒烟测试（12 项）
~/.local/opt/ato-services.sh logs      # 查看后端日志
~/.local/opt/ato-services.sh stop      # 全部停止
```

端到端冒烟测试（发码 → 登录 → 鉴权 → 刷新 → 登出 → 数据落库）**12 项全部通过**，
详见 `docs/smoke-test-report.md`；测试脚本 `~/.local/opt/smoke-test.sh`。

登录接口曾因 `AuthSession` 的 `revoked` 属性 getter 类型歧义（`getRevoked(): Integer`
与手写 `isRevoked(): boolean` 冲突）导致 MyBatis 反射异常、登录 100% 返回 500，已修复为 `isSessionRevoked()`。




```bash
# 1. 建库建表
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS ato_account DEFAULT CHARSET utf8mb4;"
mysql -uroot -p ato_account < src/main/resources/db/schema.sql

# 2. 编译运行（默认 mock 短信、默认连本机 MySQL/Redis）
mvn clean spring-boot:run

# 3. 打包
mvn clean package -DskipTests && java -jar target/ato-account-1.0.0-SNAPSHOT.jar
```

## 四、环境变量

| 变量 | 说明 | 默认 |
| --- | --- | --- |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | 数据库 | 127.0.0.1 / 3306 / ato_account |
| `DB_USER` / `DB_PASSWORD` | 数据库账号 | root / root |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` / `REDIS_DB` | Redis | 127.0.0.1 / 6379 / 空 / 0 |
| `JWT_SECRET` | HS256 密钥，≥32 字节 | 内置开发值（**生产必须替换**） |
| `AES_KEY` / `AES_KEY_ID` | identifier 加密密钥与版本号 | 内置开发值 |
| `IDENTIFIER_HMAC_KEY` | identifier 查询 HMAC 密钥 | 内置开发值 |
| `SNOWFLAKE_WORKER_ID` | 雪花 workerId，多实例必须唯一 | 1 |
| `ATO_SMS_MOCK` | true=不真实发短信，验证码打日志 | true |

## 五、联调示例

```bash
# 1. 下发验证码（mock 模式：去服务日志里找 [MOCK SMS] 行拿验证码）
curl -X POST http://127.0.0.1:8080/api/v1/auth/sms_code \
  -H 'Content-Type: application/json' -H 'X-Device-Id: dev_001' \
  -d '{"phone_number":"13800138000","scene":"login"}'

# 2. 登录（Platform 决定同端互踢范围：mobile/pad/web/desktop）
curl -X POST http://127.0.0.1:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' -H 'Platform: mobile' \
  -H 'X-Device-Id: dev_001' -H 'X-Device-Name: iPhone 15' \
  -d '{"auth_type":"phone_code","phone":"13800138000","auth_code":"123456"}'

# 3. 带令牌访问
curl http://127.0.0.1:8080/api/v1/user/me -H "Authorization: Bearer <access_token>"

# 4. 刷新（30s 内重复请求返回同一对 token，实现并发幂等）
curl -X POST http://127.0.0.1:8080/api/v1/auth/refresh \
  -H 'Content-Type: application/json' -d '{"refresh_token":"<refresh_token>"}'

# 5. 设备列表 / 踢设备 / 登出
curl http://127.0.0.1:8080/api/v1/auth/session/list -H "Authorization: Bearer <access_token>"
curl -X POST http://127.0.0.1:8080/api/v1/auth/session/revoke \
  -H 'Content-Type: application/json' -H "Authorization: Bearer <access_token>" \
  -d '{"session_id":"<session_id>"}'
curl -X POST http://127.0.0.1:8080/api/v1/auth/logout \
  -H 'Content-Type: application/json' -d '{"refresh_token":"<refresh_token>"}'
```

## 六、关键实现约定（改代码前务必阅读）

1. **响应结构**：HTTP 恒 200，业务结果看 `code`；`code=0` 成功，`msg` 英文。
2. **int64 序列化**：所有 `Long` 出参自动转字符串（`JacksonConfig`），前端不丢精度；入参仍接受字符串与数字。
3. **字段命名**：全局 `SNAKE_CASE`，与 `docs/openapi.yaml` 完全一致。
4. **同端互踢**：踢除维度是 `platform`，登录时在**同一事务**内吊销旧会话再插入新会话。
5. **Redis 负缓存一致性**：任何置 `auth_session.revoked=1` 的路径都必须调用 `TokenService` 的吊销方法同步写 Redis（`sess:{sid}:revoked=1`），否则拦截器可能命中过期的 `"0"` 缓存放行已吊销会话。
6. **refresh_token**：只存 SHA-256 哈希；`rt:used:{hash}` 里存 userId 用于重放后全端踢线。
7. **identifier**：AES-256-GCM 密文入库，等值查询走 `identifier_hash`（HMAC）；解绑/注销时改写为 `dead_{id}` 释放唯一索引。
8. **时间**：DB 与 API 全部 UTC，输出形如 `2026-10-05T10:49:31Z`。
9. **雪花 ID**：主键全部 `IdType.INPUT`，由 `SnowflakeIdGenerator` 生成；多实例部署必须配置不同 `SNOWFLAKE_WORKER_ID`。

## 七、待办（v1.1）

1. 微信 / Apple 登录与绑定（`AuthService.login` 中 `auth_code` 换取 unionid 的分支已留位）
2. 真实短信服务商接入（替换 `SmsService#deliver`）
3. `ai_verify` 内部接口（服务间密钥校验）
4. MySQL 逻辑外键与归档：`auth_session` 过期数据清理任务
5. 单元测试与接口自动化用例
