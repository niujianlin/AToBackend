# 服务器部署说明（全 Docker）

目标：空白 Ubuntu 服务器 → `docker compose up -d` 一键起「应用 + MySQL 8 + Redis 7」，
后续由 GitHub Actions（`.github/workflows/cd.yml`）打 tag 自动滚动发布。

---

## 一、服务器上需要安装什么

应用、MySQL、Redis 全部跑在容器里，所以**宿主机不需要装 JDK / Maven / MySQL / Redis / Nginx**。

| 软件 | 版本 | 是否必须 | 用途 |
| --- | --- | --- | --- |
| Ubuntu Server | 22.04 LTS / 24.04 LTS（64 位） | 必须 | 操作系统 |
| Docker Engine | 24.x 或更新（CE） | 必须 | 运行容器 |
| docker-compose-plugin | v2.x | 必须 | CD 使用 `docker compose`（空格形式）子命令 |
| containerd.io / docker-buildx-plugin | 随 Docker 一起装 | 必须 | Docker 依赖组件 |
| openssh-server | 系统自带 | 必须 | CD 通过 SSH 部署 |
| curl、ca-certificates、gnupg | 系统自带 | 必须 | 安装 Docker 源、拉取镜像 |
| git | 任意 | 推荐 | 把本仓库拉到部署目录，便于同步 compose 变更 |
| ufw | 系统自带 | 推荐 | 只放行 22 / 80 / 443（或 8080） |
| unattended-upgrades | 系统自带 | 可选 | 自动安全更新 |
| fail2ban | 可选 | 可选 | 防 SSH 暴力破解 |

**硬件建议**：MySQL 8 空库约占用 300–500MB，加上应用（限 1G）、Redis，建议 **2 核 4G 内存、40G 磁盘**起步；
内存 ≤2G 时请在 `docker-compose.yml` 里放开 MySQL 的内存限制注释。

不需要安装（常见误解）：JDK 17、Maven、MySQL 客户端、Redis、Nginx（HTTP 直连阶段）、Node。

---

## 二、安装 Docker（官方源）

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg git ufw
sudo install -m 0755 -d /etc/apt/keyrings

# 国内服务器访问 download.docker.com 慢时，把下面的 URL 换成：
#   https://mirrors.aliyun.com/docker-ce/linux/ubuntu/gpg
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

sudo systemctl enable --now docker
docker version          # 出现 Server 版本即成功
docker compose version  # 必须是 v2.x
```

免 sudo 用 docker（部署用户加入 docker 组）：

```bash
sudo usermod -aG docker "$USER" && newgrp docker
```

---

## 三、一次性服务器配置

### 1. 创建部署用户与 SSH 密钥（给 GitHub Actions 用）

```bash
sudo adduser deploy
sudo usermod -aG docker deploy

# 在**本地电脑**生成专用部署密钥（不要复用个人密钥）
ssh-keygen -t ed25519 -C "github-cd" -f ~/.ssh/ato_deploy_ed25519

# 把公钥放到服务器（私钥填到 GitHub Secrets）
ssh-copy-id -i ~/.ssh/ato_deploy_ed25519.pub deploy@<服务器IP>
```

### 2. 放行防火墙端口

```bash
sudo ufw allow OpenSSH
sudo ufw allow 8080/tcp        # 若前面挂 Nginx/Caddy，可改为仅允许 80/443 并只允许本机访问 8080
sudo ufw enable
sudo ufw status
```

> MySQL 3306 / Redis 6379 **不要在服务器上放行**，它们只在 compose 内网通信。

### 3. 准备部署目录

```bash
sudo mkdir -p /opt/ato && sudo chown -R deploy:deploy /opt/ato
cd /opt/ato
git clone --depth 1 https://github.com/<owner>/AToBackend.git repo   # 或 scp 上传 deploy/ 目录
cp -r repo/deploy/* /opt/ato/          # docker-compose.yml、.env.example、initdb/
cp .env.example .env
chmod 600 .env
```

### 4. 填写 `.env`（**必须修改默认值**）

```bash
cd /opt/ato
vim .env
```

| 变量 | 说明 |
| --- | --- |
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码（仅运维/健康检查用） |
| `DB_NAME` / `DB_USER` / `DB_PASSWORD` | 应用连库账号，容器首次启动自动创建并授权 |
| `REDIS_PASSWORD` | Redis 密码，应用与 healthcheck 共用 |
| `JWT_SECRET` | HS256 密钥，**≥32 字节**，生产必须随机生成 |
| `AES_KEY` / `AES_KEY_ID` | identifier 加密密钥与版本号 |
| `IDENTIFIER_HMAC_KEY` | identifier 查询用 HMAC 密钥 |
| `SNOWFLAKE_WORKER_ID` | 雪花 workerId，多实例必须不同（0~31） |
| `APP_PORT` | 宿主机映射端口，默认 8080 |

生成随机密钥：

```bash
openssl rand -base64 32   # 依次生成 JWT_SECRET / AES_KEY / IDENTIFIER_HMAC_KEY
openssl rand -hex 16      # 生成数据库、Redis 密码
```

### 5. 配置 GitHub 仓库 Secrets

Settings → Secrets and variables → Actions → New repository secret：

| Secret | 值 |
| --- | --- |
| `DEPLOY_HOST` | 服务器 IP 或域名 |
| `DEPLOY_USER` | `deploy` |
| `DEPLOY_SSH_KEY` | 上一步生成的**私钥**全文（`-----BEGIN OPENSSH PRIVATE KEY-----` …） |
| `DEPLOY_PATH` | `/opt/ato` |

同时建议在 Settings → Environments 里建 `production` 环境并开启「Required reviewers」人工审批。

---

## 四、首次启动（手动跑一次，验证环境）

```bash
cd /opt/ato
export ATO_IMAGE=ghcr.io/<owner>/ato-account:v1.0.0   # 或等 CD 自动注入
echo "$GITHUB_TOKEN" | docker login ghcr.io -u <github用户名> --password-stdin   # 镜像为 private 时
docker compose up -d

docker compose ps            # 三个容器均为 Up (healthy)
docker compose logs -f ato-account
```

首次启动时 `initdb/schema.sql` 会自动建三张表，验证：

```bash
docker compose exec mysql mysql -uato -p"$DB_PASSWORD" ato_account -e "SHOW TABLES;"
```

冒烟：

```bash
curl -X POST http://127.0.0.1:8080/api/v1/auth/sms_code \
  -H 'Content-Type: application/json' -H 'X-Device-Id: smoke' \
  -d '{"phone_number":"13800138000","scene":"login"}'
# 去 docker compose logs ato-account 找 [MOCK SMS] 行取验证码
```

---

## 五、日常运维

```bash
cd /opt/ato
docker compose ps                       # 状态
docker compose logs -f --tail=100 ato-account
docker compose pull ato-account && docker compose up -d ato-account   # 手工更新

# 数据库备份（每天一次，可放进 crontab）
docker compose exec -T mysql mysqldump -uato -p"$DB_PASSWORD" --single-transaction \
  --routines --triggers ato_account | gzip > /opt/ato/backup/ato_$(date +%F).sql.gz

# 回滚到上一个镜像
docker compose up -d ato-account   # CD 失败时会自动回滚到 .last_image 记录的镜像
```

数据卷：`mysql-data`（数据库文件）、`redis-data`（Redis 持久化，当前 appendonly=no 基本为空）。
`docker compose down` **不会**删除数据卷；只有 `down -v` 才会删除，慎用。

---

## 六、后续建议（当前未做）

1. **HTTPS / 域名**：加一个 Nginx 或 Caddy 容器做反向代理，应用端口改为 `127.0.0.1:8080:8080`。
2. **日志与监控**：接入 Loki/Prometheus 或云厂商日志服务（当前只有 json-file 本地日志，上限 50m×5）。
3. **数据库备份**：加 crontab + 异地存储（OSS/S3），并定期演练恢复。
4. **MySQL 版本**：生产建议锁定 8.0.4x 最新小版本（本项目开发实测为 8.0.29，缺近年安全补丁）。
5. **Redis 持久化**：若风控计数丢失不可接受，把 `command` 里的 `appendonly no` 改为 `yes`。
