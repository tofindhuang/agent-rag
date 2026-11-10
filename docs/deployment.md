# 部署指南

本文是 agent-core 的**可执行部署说明**：进程拓扑、构建产物、环境变量、首次上线顺序、多实例与滚动更新、反代要求、可观测与故障排查。

> 现状边界（先说清楚）：仓库只提供**开发用 `docker-compose.yml`**，没有 Helm/K8s 清单、没有镜像发布流水线、没有内置备份与告警。本文描述的是「在一台主机上用 systemd/容器编排自行拉起」的部署方式，以及上生产前必须自己补齐的部分。

---

## 1. 进程拓扑

```
                ┌────────────┐
  浏览器 ──────▶│  web :3000 │  Next.js（NEXT_PUBLIC_API_BASE 指向 server）
                └─────┬──────┘
                      │ HTTPS（同域反代 /api）
                ┌─────▼──────┐        ┌──────────────────────┐
                │ server:8080│◀──────▶│ MySQL 8.4 / Redis 7  │
                │  (Java)    │        │ Qdrant / RabbitMQ    │
                └─────┬──────┘        │ S3 兼容对象存储       │
                      │ 内部 HTTP      │ （KB 原文）           │
        ┌─────────────▼─────────────┐ └──────────────────────┘
        │ gateway :3001 / :3002 …   │  Node + pi 引擎，可多实例
        │ 共享 GATEWAY_SESSION_DIR  │
        └─────────────┬─────────────┘
                      │ docker.sock
                ┌─────▼──────────────┐
                │ 沙箱容器（一次性）  │  agent-core-sandbox:python-3.12
                └────────────────────┘
```

- **只有 web 面向公网**；`/api/*` 由反代转发到 server。gateway 与 server 只在内网通信。
- 管理端口 `:9091` 只暴露 `/actuator/prometheus`，不要对公网开放。
- gateway 需要访问宿主 Docker（`/var/run/docker.sock`）来创建沙箱容器。

---

## 2. 依赖

| 组件 | 版本 | 用途 | 备注 |
|---|---|---|---|
| JDK | 21 | server | `mvn -version` |
| Node | ≥22 | gateway / web | gateway 用 `tsx` 直接运行 TypeScript |
| Maven | 3.9+ | 构建 server | |
| Docker Engine | 24+ | 沙箱执行 + 基础设施 | gateway 需要 socket 权限 |
| MySQL | 8.4 | 业务库 | 首次启动由 Flyway 建表 |
| Redis | 7 | 会话路由、限流、配额缓存、沙箱许可 | 可丢数据，不做持久化要求 |
| Qdrant | v1.14 | 知识库向量 | 集合按用户自动创建 |
| RabbitMQ | 4.x | KB 摄取队列 | 关闭 `KB_ENABLED` 可不依赖 |
| S3 兼容存储 | MinIO / Silo / 云 S3 | KB 原文 | 可配 path-style |
| 可选：OCR | — | 扫描版 PDF | `docker compose --profile ocr up -d` |

---

## 3. 构建产物

```bash
# server：可执行 jar
cd server && mvn -q -DskipTests package
# → server/target/server-0.1.0-SNAPSHOT.jar

# gateway：无需编译（tsx 运行 TS），但必须安装完整依赖（tsx 在 devDependencies）
cd gateway && npm ci

# web：Next.js 生产构建
cd web && npm ci && npm run build      # 之后用 npm run start 启动

# 沙箱镜像（run_code 必需；服务运行时不构建、不拉取镜像）
docker build -t agent-core-sandbox:python-3.12 -f sandbox/Dockerfile.python sandbox
```

> gateway 的 `npm start` 使用 `tsx`（devDependency），所以**不能用 `npm ci --omit=dev`**。若要瘦身，需先把 `tsx` 移到 dependencies 或改用编译产物（当前仓库未提供该构建步骤）。

---

## 4. 配置

所有配置都走环境变量（Spring 与 Node 都不读 `.env`）；`.env.example` 是完整清单，下面只列**上线必改**的项。

### 4.1 必填

| 变量 | 说明 |
|---|---|
| `JWT_SECRET` | HS256 签名密钥，**≥32 字节**随机值 |
| `MODEL_SECRET_KEY` | Base64 的 32 字节密钥，用于加密用户模型 API Key |
| `MYSQL_HOST` / `MYSQL_USER` / `MYSQL_PASSWORD` | 业务库（库名固定 `agent_core`） |
| `REDIS_HOST` / `REDIS_PORT` | 路由/限流/配额 |
| `GATEWAY_URLS` | 多实例时列出全部 gateway，如 `http://10.0.0.11:3001,http://10.0.0.12:3001` |
| `GATEWAY_API_TOKEN` | server↔gateway 内部令牌（两侧一致） |
| `GATEWAY_SESSION_DIR` | **所有 gateway 实例共享同一路径**（宿主机卷） |
| `GATEWAY_WORK_DIR` | pi 的中性工作目录（默认 `/tmp/agent-core-work`）；**切勿指向仓库目录**——pi 会把它写进系统提示 |
| `SANDBOX_*` | 至少确认镜像名与宿主机 Docker 可用 |

### 4.2 上线建议改

| 变量 | 默认 | 建议 |
|---|---|---|
| `AUTH_COOKIE_SECURE` | `false` | **HTTPS 下必须 `true`** |
| `ADMIN_EMAILS` | 空 | 填运营/管理员邮箱（逗号分隔），否则没人能进运营看板 |
| `RATE_LIMIT_TRUSTED_PROXIES` | 空 | 部署在反代后**必须**填反代 CIDR，否则认证 IP 限流会全站共享（ADR-0030） |
| `QUOTA_MONTHLY_TOKENS` | `0` | 0 = 不限；对外服务建议设一个上限 |
| `NEXT_PUBLIC_API_BASE` | `http://localhost:8080` | 同域反代时用 `https://<域名>`；不同域时填 server 公网地址 |
| `MODEL_ALLOW_PRIVATE_ENDPOINTS` | `false` | 只有当用户需要指向内网模型网关时才设 `true` |
| `KB_ALLOW_PRIVATE_ENDPOINTS` | `false` | 同上（embedding/rerank 在内网时） |

### 4.3 可选能力

OAuth/OIDC（`OAUTH_*`，默认关闭，回调地址必须与 provider 注册一致）、OCR（`--profile ocr`）、沙箱预热池（`SANDBOX_POOL_ENABLED`，默认开）、冷归档（`SESSION_ARCHIVE_*`）。

---

## 5. 首次上线顺序

1. **起基础设施**：MySQL / Redis / Qdrant / RabbitMQ / 对象存储（可用 `docker compose up -d` 先生成一套，或接托管服务）。
2. **构建**：server jar、gateway 依赖、web 构建产物、沙箱镜像（见 §3）。
3. **启动 server**：`java -jar server/target/server-0.1.0-SNAPSHOT.jar`
   - 首次启动自动执行全部 up-only 迁移（Flyway），**不要**手工建表。
   - 健康检查：`curl http://127.0.0.1:8080/api/health` → `{"status":"ok"}`。
4. **启动 gateway**（每个实例）：`cd gateway && GATEWAY_PORT=3001 GATEWAY_INSTANCE_ID=gw-1 npm start`
   - 健康检查：`curl http://127.0.0.1:3001/health` → `{"ok":true,"contractVersion":"1",...}`。
   - server 启动时会校验契约版本；gateway 未就绪只会告警，不阻塞启动。
5. **启动 web**：`cd web && npm run start`（:3000）。
6. **注册首个账号**，在「设置 → 模型服务」添加供应商并设为默认，然后在「设置 → 知识库」配置 embedding/rerank（可选）。
7. **创建会话**并发一条消息，确认 SSE 正常、消息落库。

---

## 6. 多实例 gateway 与滚动更新

多个 gateway 进程**共享** `GATEWAY_SESSION_DIR`，通过 Redis 注册表与单写者文件锁协作（ADR-0024）：

- 每个实例必须有唯一 `GATEWAY_INSTANCE_ID`（默认 `hostname:port`）。
- server 通过 `GATEWAY_URLS` 发现实例；会话按亲和放置，锁保证同一会话只有一个写者。
- 容量：`GATEWAY_MAX_SESSIONS`（默认 60/实例），空闲 `GATEWAY_IDLE_EVICT_MS`（默认 15 分钟）后释放内存但保留文件。

**排空式滚动更新**（保证不断流）：

```bash
# 对要下线的实例触发排空：拒绝新轮次，等在飞轮次完成
curl -X POST http://<gateway>:3001/admin/drain
# 轮询，直到进程退出或 draining=true 且无活动轮次
curl -s http://<gateway>:3001/health
# 停止旧进程 → 替换二进制 → 启动新实例 → 健康检查通过 → 下一台
```

- 宽限期 `GATEWAY_DRAIN_GRACE_MS`（默认 10 分钟）到期后仍未完成的轮次会被 abort 并落成 `partial`。
- **崩溃/强杀不在承诺内**：进程被 kill -9 时，在飞轮次只能是 `partial`，用户重发即可（同一 `turnId` 幂等重试）。

---

## 7. 反向代理要求

- **SSE 必须关闭缓冲**：`proxy_buffering off;`（nginx）或等价配置；否则 token 流会被攒住。
- **超时**：`proxy_read_timeout` 至少 10 分钟（`run_code` 单次默认 30s、上限 60s，长回答也会持续推流）。
- **转发真实 IP**：设置 `X-Forwarded-For`，并把反代网段写进 `RATE_LIMIT_TRUSTED_PROXIES`；否则认证 IP 限流会退化。
- **上传大小**：KB 单文件上限 20MB（`spring.servlet.multipart.max-file-size`），反代 `client_max_body_size` 要匹配。
- 只暴露 web 与 `/api`；`/actuator/*`（:9091）与 gateway 端口不对公网开放。

---

## 8. 可观测

| 端点 | 用途 |
|---|---|
| `GET /api/health`（server :8080） | 服务存活 |
| `GET /health`（gateway） | 实例状态：`sessions`、`draining`、`maxSessions`、`contractVersion` |
| `GET :9091/actuator/prometheus` | Prometheus 抓取（限流、审计失败、配额拒绝、沙箱指标等） |

日志：server 输出结构化业务日志（含 `audit` logger 单独一行）；gateway 输出请求与 pi 事件（`GATEWAY_LOG_PI_EVENTS=1` 可详细）。

> 仓库**没有**告警规则与 SLO 定义；上生产前自行补 Alertmanager 规则（建议至少覆盖：5xx 比例、限流拒绝激增、审计写入失败、沙箱不可用、gateway 实例数下降）。

---

## 9. 数据与持久化

| 数据 | 位置 | 是否必须持久化 |
|---|---|---|
| 业务表（用户/会话/消息/用量/审计…） | MySQL `agent_core` | **是** |
| 知识库向量 | Qdrant | **是**（可从原文重建） |
| KB 原文 | S3 兼容对象存储 | **是** |
| pi 会话文件（引擎上下文） | `GATEWAY_SESSION_DIR` | **是**（共享卷；冷归档会上传到对象存储） |
| 会话路由/锁/限流/配额缓存 | Redis | 否（可丢；丢后路由自愈） |

**备份**（仓库未内置，需自行落地）：MySQL 逻辑备份 + Qdrant snapshot + 对象存储版本化/跨桶复制 + `GATEWAY_SESSION_DIR` 卷快照。恢复顺序：基础设施 → MySQL → 对象存储 → 会话目录 → 启动 server/gateway。

## 10. 升级与回滚

- 迁移是 **up-only**，没有 down 脚本；回滚靠**回退镜像/二进制**，因此每个版本必须保证对旧数据向前兼容。
- 升级顺序：server（迁移）→ gateway（契约向后兼容，可先滚动）→ web。
- 契约 v1 是纯增量演进（新增可选字段/事件），老 gateway 与新 server、新 gateway 与老 server 都要能跑。
- 滚动更新步骤见 §6；**不要**在没有 drain 的情况下直接重启 gateway。

---

## 11. 故障排查

| 现象 | 检查 |
|---|---|
| server 启动失败 | MySQL 可达性、`agent_core` 库是否存在、Flyway 版本表是否被手工改过（只允许执行**新增**的迁移文件） |
| `MODEL_NOT_CONFIGURED` | 用户没有添加供应商/设默认；或 `ALLOW_SYSTEM_DEFAULT_MODEL=false` 且没配系统默认 |
| 模型 401/502 | `PROVIDER_AUTH_FAILED` 多为 Key 失效；检查用户 provider 配置（密钥加密入库，换 key 后需重填） |
| SSE 只有最后一整段 | 反代开了 buffering；按 §7 关闭 |
| 429 全员触发 | `RATE_LIMIT_TRUSTED_PROXIES` 未配，所有请求共用反代 IP；按 §4.2 配置 |
| 429 `QUOTA_EXCEEDED` | 本月 token 配额用尽；看「设置 → 用量」的重置时间 |
| `KB_INDEX_STALE` | 换了 embedding 模型/维度但未重建文档；在知识库页执行重建 |
| 沙箱 503 / run_code 失败 | 宿主 Docker 不可达、镜像未构建、`SANDBOX_*` 并发/池配置超限 |
| 会话「忙」409 | 同一会话有在飞轮次；等待或先 abort，再迁移/重启 |
| 分支入口不显示 | 该消息没有 `pi_entry_id`（老会话/老 gateway），属预期 |

---

## 12. 已知边界（不要承诺给客户超出这些的能力）

- 单机多实例：会话目录必须共享本地卷，**不支持跨主机**与网络文件系统。
- 排空式滚动更新不断流；**崩溃与强杀仍是 `partial`**。
- 沙箱是 `runc + 禁网 + 只读根 + 非 root + cap-drop`，**不是绝对边界**；gVisor 评估结论为暂不启用（ADR-0031）。
- 审计是观测级日志（无 WORM），保留 180 天。
- 配额是部署级默认，不是计费系统。
- 数据导出为同步流，单包上限 200MB。
