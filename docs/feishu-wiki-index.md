# 飞书知识库索引（阿拓产品）

> 用途：记录已接入的飞书知识库与文档 token，便于后续直接读取、导出、整理。
> 生成时间：2026-10-17

## 连接状态

| 项目 | 值 |
| --- | --- |
| 授权状态 | 已授权（应用注册 + 用户 OAuth 全部完成） |
| 用户 | 牛建霖 |
| App ID | `cli_aa580f09a2789c2c` |
| 品牌 | feishu |
| 令牌类型 | DPoP（Access Token 有效期至 2026-10-17 09:30，Refresh 至 2026-10-24） |

## 知识库

### 阿拓产品知识库

- space_id：`7666860099957755120`
- 类型：team / 团队知识库
- 描述：该知识库为阿拓产品知识库，包含应用的产品文档

文档清单：

| 标题 | node_token | obj_token (docx) | 说明 |
| --- | --- | --- | --- |
| v1.0.0 版本（目录） | `B9kWwuxAWi05IekJZILcqDqcnTf` | — | 含子文档 |
| └ 阿拓 v1.0.0 产品需求文档 | `RyzBw5I79iMqc0kLqwWcV8hvndh` | `CleudedAQow9lhxflRgcR9BknCe` | PRD，含登录/OOBE/首页/消息/我的等页面定义 |
| └ v1.0.0 后端服务技术设计 | `HrI5wBiJliiGNwkfuTmcSrp0nec` | `RuW4doONNoscjlxt2kWcfwL7nyh` | 数据库表 / 流程 / 接口设计 |
| 三方平台依赖信息 | `IHhJw3qyLiLHNLkQwStcM87On6e` | `EE4OdBOvBoMbAtxxl0NcPAqhngb` | 第三方依赖说明 |
| 会议记录 | `F3Rw7wbGiONR7kDwxKccEiInvc` | `JKyCdTG5doWHhMx29r2c1GBFnZe` | 会议纪要 |

## 文档结构概要

### v1.0.0 后端服务技术设计

```
# 数据库表设计
  ## user_info        / auth_credential / auth_session（表名、字段、索引、表说明）
# 流程设计
  ## 登录流程（主流程 / Apple / Wechat / Phone code 时序，各端业务规则）
  ## Token 令牌刷新流程
  ## 登出流程
  ## 绑定第三方 / 手机号流程
  ## 解绑第三方
  ## 账号注销流程
  ## 验证码发送流程
  ## 手机号换绑流程
  ## 多设备管理流程
  ## AI 鉴权流程
# 接口设计
  登录 / Token 刷新 / 登出 / 绑定 / 解绑 / 账户注销 / 获取验证码 / 手机号换绑
  / 获取登录设备列表 / 吊销指定会话（踢下线某台设备）/ AI 鉴权
  每个接口均含：基本信息、Headers、Body、响应格式、响应示例、错误码定义
```

### 阿拓 v1.0.0 产品需求文档（主要章节）

```
前言 / 版本信息 / 需求背景
Icon logo（设计风格、线条规范、色彩规范、禁忌、交付要求）
前置流程页面：开屏页 / 登录页（手机号、微信登录与绑定规则）/ OOBE（6 大信息模块 + 交互流程）
首页及子页面：搭子 Tab / 用户详情页
Agent 页面
消息列表页及聊天页
我的页面及子页面
预计发展路径（四个阶段）
补充：AI 工具能力、落地顺序建议
```

## 常用读取命令

```bash
export PATH=/home/niujl/.workbuddy/binaries/node/versions/20.18.0/bin:$PATH

# 读全文（markdown）
lark-cli docs +fetch --doc <obj_token> --doc-format markdown

# 只读大纲
lark-cli docs +fetch --doc <obj_token> --scope outline --doc-format markdown

# 关键词定位段落
lark-cli docs +fetch --doc <obj_token> --scope keyword --keyword "多设备管理"

# 列出知识库节点
lark-cli wiki +node-list --space-id 7666860099957755120 --format table

# 搜索文档（需额外授权 search:docs:read）
lark-cli drive +search --query "关键词" --format pretty
```

## 待办 / 注意

1. **全局文档搜索未授权**：`drive +search` 需要补充 `search:docs:read` 权限，如需要可按关键词全局检索文档，需再做一次增量授权。
2. **Token 会过期**：Refresh Token 有效至 2026-10-24，过期后需重新 `lark-cli auth login`。
3. 私有文档需当前用户（牛建霖）在其飞书账号内有阅读权限。
