# 阿拓账号服务 - 本地端到端冒烟测试报告

- **测试时间**：2026-10-17 08:22（北京时间）
- **测试对象**：`backend/target/ato-account-1.0.0-SNAPSHOT.jar`（Spring Boot 3.2.5 + MyBatis-Plus）
- **测试目的**：在真实 MySQL + Redis 环境下跑通「手机号 + 短信验证码登录」全链路，验证上一轮未能本地验证的代码
- **结论**：**12 项用例全部通过（PASS=12 / FAIL=0）**；过程中发现并修复 1 个会导致登录 100% 失败的代码缺陷

---

## 一、运行环境（全部免 root，装在 `~/.local/opt`）

| 组件 | 版本 | 路径 | 说明 |
| --- | --- | --- | --- |
| 操作系统 | Ubuntu 26.04.1 LTS | — | 4 核 / 15G 内存 / 88G 可用 |
| JDK | Temurin 17.0.20.1+1 | `~/.local/opt/jdk17` | LTS，许可免费 |
| Maven | 3.9.9 | `~/.local/opt/maven` | 依赖走华为云镜像 |
| MySQL | 8.0.29 Community | `~/.local/opt/mysql` | 官方 minimal 二进制包 |
| Redis | 7.2.5 | `~/.local/opt/redis` | 源码编译（`MALLOC=libc`） |
| libaio | 0.3.113 | `~/.local/opt/libaio` | 系统缺失，自行编译补上 |

免 root 部署过程中解决的三个环境障碍：
1. **系统缺 `libaio.so.1`**：MySQL 官方二进制硬依赖。现场编译 libaio 源码，产物同时软链进 `mysql/lib/private`（mysqld 的 RUNPATH 指向该目录），运行时无需 `LD_LIBRARY_PATH`。
2. **`mysql` 客户端缺 `libncurses.so.5`**：Ubuntu 26.04 只提供 ncurses 6。在 `~/.local/opt/compat-lib` 下用 `libncurses.so.6 / libtinfo.so.6` 建 `.so.5` 兼容软链解决。
3. **apt 索引不可用且无 sudo**：改用官方 tar 包 + 源码编译，完全绕开包管理器。

## 二、部署拓扑

```
java -jar ato-account-1.0.0-SNAPSHOT.jar   →  :8080  (进程已启动，11s 完成)
mysqld --defaults-file=~/.local/opt/mysql-my.cnf →  :3306  (库 ato_account，3 张表)
redis-server --port 6379 --bind 127.0.0.1  →  :6379
```

数据库建表来源：`backend/src/main/resources/db/schema.sql`，实际导入后 `user_info` / `auth_credential` / `auth_session` 三张表及 `uk_type_identifier_hash` 唯一索引均已确认存在。

## 三、用例执行结果

| # | 用例 | 预期 | 实测 | 结果 |
| --- | --- | --- | --- | --- |
| 1 | `POST /api/v1/auth/sms_code` 发码（scene=login） | `code=0` | `{"code":0,"msg":"success"}` | PASS |
| 2 | 验证码写入 Redis | key=`sms:login:{sha256(phone)}`，TTL 300s | key 正确，`ttl=300s` | PASS |
| 3 | `POST /api/v1/auth/login` 登录并自动注册 | 返回双 token + `is_new_user=true` | `user_id=237007235853586432`，`access_token` 291 字符 | PASS |
| 4 | 验证码一次性消费 | 校验后立即删除 | Redis 中已不存在 | PASS |
| 5 | 复用已消费验证码 | 拒绝 | `40002 Code expired` | PASS |
| 6 | `GET /api/v1/user/me`（Bearer） | `code=0` 且返回用户信息 | 昵称「阿拓用户586432」，`account_status=1` | PASS |
| 7 | 无 token 访问受保护接口 | `40100` | `40100 missing bearer token` | PASS |
| 8 | `POST /api/v1/auth/refresh` | 轮换出新令牌 | 新旧 token 均正常返回 | PASS |
| 9 | `POST /api/v1/auth/logout` | `code=0` | `{"code":0,"msg":"success"}` | PASS |
| 10 | 登出后旧 access_token | 立即失效 | `40100 session revoked` | PASS |
| 11 | 老用户二次登录 | 复用同一 user_id，`is_new_user=false` | user_id 完全一致，标记正确 | PASS |
| 12 | 数据库与 Redis 落库 | 数据符合设计 | 见下 | PASS |

第 12 项明细（数据库侧）：

- `user_info`：1 条（`237007235853586432` / 阿拓用户586432 / `account_status=1`）
- `auth_credential`：1 条（`phone_code`，`is_invalid=0`，密文长度 61 → AES-256-GCM 信封格式 `key_id:iv:ct:tag`，`identifier_hash` 前缀 `5f7b57b4f0c317f1`）
- `auth_session`：2 条 —— 一条 `revoked=1`（首次登录会话，被同端登录踢除）、一条 `revoked=0`，**同端互踢逻辑生效**
- Redis：`sess:{sid}:revoked`、`sess:{sid}:plat`、`sms:cd/day/ip/dev:*` 限流键全部按设计生成

同时验证了两项安全设计：手机号在 Redis 中只以 SHA-256 哈希出现（不落明文），验证码错误次数与限流计数按 scene + phone_hash 维度隔离。

## 四、发现并修复的缺陷

### 缺陷：`AuthSession` 属性 getter 类型歧义，导致登录接口 500

**现象**：首次冒烟时 `POST /api/v1/auth/login` 返回 `{"code":500,"msg":"Internal server error"}`。值得注意的是用户与凭证已成功写库，只有会话签发环节失败，属于典型的「部分成功」故障。

**根因**（后端日志异常栈）：
```
org.apache.ibatis.reflection.ReflectionException:
Illegal overloaded getter method with ambiguous type for property 'revoked'
in class 'com.ato.account.entity.AuthSession'.
```

`AuthSession` 类同时存在两个 getter：
- `@Data` 按字段 `Integer revoked` 生成的 `getRevoked(): Integer`
- 手写的 `isRevoked(): boolean`

在 JavaBeans 语义下两者都属于 `revoked` 属性，类型却不同，MyBatis 反射时直接抛异常。该缺陷会让**所有登录请求 100% 失败**，且因异常发生在写库之后，还会留下"用户已注册但无法登录"的脏状态。

**修复**：将手写方法重命名为 `isSessionRevoked()`，并同步 4 处调用点：
- `entity/AuthSession.java`（定义处，补充注释说明为何不能叫 `isRevoked`）
- `service/AuthService.java:254`
- `service/TokenService.java:116`、`service/TokenService.java:141`
- `interceptor/AuthInterceptor.java:83`

已确认其余两个实体 `AuthCredential`、`UserInfo` 不存在同类问题。

**验证**：重新编译（BUILD SUCCESS）→ 清空库表与 Redis → 重启服务 → 重跑冒烟，12 项全绿。

## 五、如何复现本次测试

```bash
# 一键起停（免 root）
~/.local/opt/ato-services.sh start     # 启动 MySQL + Redis + 后端
~/.local/opt/ato-services.sh status    # 查看状态
~/.local/opt/ato-services.sh smoke     # 跑冒烟测试
~/.local/opt/ato-services.sh logs      # 看后端日志
~/.local/opt/ato-services.sh stop      # 全部停止
```

冒烟脚本源码：`~/.local/opt/smoke-test.sh`（使用 `wget` 发 HTTP 请求，本机无 `curl`）。

## 六、遗留事项与风险

1. **短信为 mock 模式**（`ato.sms.mock=true`）：验证码只写 Redis + 打日志，不真实发送。测试通过 Redis 读取验证码，与真机流程等价，但接入真实短信商后需回归本链路。
2. **第三方登录为 MVP 桩**：`bind` / `unbind` / `change_phone` / `ai_verify` 四个接口按设计返回 `40099`，本次未覆盖。
3. **MySQL 8.0.29 为国内镜像可得最高版本**（2022 年发布）。功能满足当前 schema，但缺少近年的安全补丁，正式环境建议换用 8.0.4x 最新版或云数据库。
4. **无 systemd 守护**：三个进程均由 `nohup + setsid` 拉起，机器重启后不会自动恢复，正式环境应容器化或配置进程守护。
5. **兼容性软链属临时手段**：`libncurses.so.5`、`libaio.so.1` 均为手工补齐，后续若允许 root，建议直接用系统包管理器安装以消除隐患。
6. **未覆盖的测试面**：并发刷新令牌（`refresh_grace_seconds=30` 幂等窗口）、会话列表 / 踢会话、注销账号、雪崩限流边界（每日 10 条、IP 每小时 20 条）均未做压测式验证。
