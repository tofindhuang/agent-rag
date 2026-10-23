# 认证采用短寿命 JWT + 轮换 refresh cookie，MySQL 为凭证唯一真相源

Date: 2026-10-23

## Status

Accepted

## Context

M0 骨架的 `ChatController` 直接信任请求体里的 `userId`，任何人可以冒充任何用户。FR-6 要求 JWT（access + refresh）认证，但留下大量自由度：token 怎么交给浏览器（localStorage / cookie）、refresh 存哪（MySQL / Redis / 无状态 JWT）、登出后 access token 的失效语义、gateway 工具回调怎么鉴权。这些选择互相牵制：存储位置决定 XSS/CSRF 暴露面，撤销语义决定是否要把 Redis 或 MySQL 放进每个请求的热路径。

约束条件：M1 只有 Java 一个服务验签；gateway 不做用户认证（信任内部 `X-User-Id`）；工具回调是 gateway → Java 的服务间调用，没有用户 JWT 可用。

## Decision

- **Access token**：JWT HS256，TTL 15 分钟，claims `sub=userId`、`jti`、`iat`、`exp`、`iss=agent-core`；密钥 `JWT_SECRET` 环境变量（≥32 字节），启动 fail-fast 校验。只有 Java 验签；gateway 维持契约 v1 的 `X-User-Id` 信任模型。
- **传输**：access token 只存前端内存、经 `Authorization: Bearer` 携带；refresh token 放 `agentcore_refresh` httpOnly cookie（`SameSite=Lax`、`Path=/api/auth`、生产开 `Secure`）。XSS 最多暴露 15 分钟凭证。
- **Refresh token**：32 字节随机 base64url 不透明串，7 天有效；MySQL 存 SHA-256 hash（`refresh_tokens` 表：token_hash 唯一、family_id、expires_at、revoked_at）。每次 `/refresh` 轮换：旧行 revoked、同 family 插入新行；旧 token 重现判定为被盗重用，撤销整个 family。
- **登出**：撤销当前 refresh token + 清 cookie；**不做 access denylist**，明确接受旧 access token 最长 15 分钟的残留窗口。
- **端点分层**：auth 四端点与 health 公开；`/api/auth/me` 与 `/api/chat/**` 需 JWT；工具回调 `/api/kb/**`、`/api/exec` 验共享 `GATEWAY_API_TOKEN`（未配置时放行但启动告警，仅限 dev）。
- **Redis 边界**：凭证类事实永不以 Redis 为真相源；Redis 只服务后续限流/缓存，丢了不能改变认证语义。

## Considered Options(被否的备选)

- **双 token 都放 httpOnly cookie** — 前端最简，但 access token 自动随请求发送，所有写接口都要补 CSRF 防护，暴露面更大。
- **token 放 localStorage** — 实现最快，但 XSS 可偷长期凭证，等于账号长期失守。
- **refresh token 也用无状态 JWT、不查库** — 无法可靠撤销，与「用户可控」方向冲突。
- **Redis TTL 作为 refresh 真相源** — 重启/淘汰丢凭证，撤销审计弱；缓存不该当真相源。
- **MySQL + Redis 双写 refresh** — 双写一致性、丢失顺序都是坑，没有对应收益。
- **现在就上 denylist** — 立即失效的收益要付出「Redis 进每个请求热路径 + 故障时 fail-open/fail-closed 两难」的代价；M1 接受 15 分钟窗口，denylist 留给安全加固切片。
- **RS256/ES256** — 适合多服务各自验签；目前只有 Java 验签，密钥管理成本无收益。
- **所有端点统一验 JWT** — gateway 没有用户 JWT，工具回调要么发服务 JWT 要么被挡，复杂度不成比例。

## Consequences

- XSS 窃取面收敛到 15 分钟；跨站写入面收敛到 `/api/auth/refresh` 一个 cookie 端点，靠 `SameSite=Lax` 防护，暂不加 CSRF token。
- 登出不是即刻全端失效：已签发的 access token 残留至多 15 分钟，需要向用户说明或后续上 denylist。
- CORS 必须开 `allowCredentials` 且严格白名单 origin；生产建议同源部署规避 CORS。
- 换 `JWT_SECRET` 会使全部 access token 失效，但 refresh 流程自动换新，用户最多多刷一次。
- `refresh_tokens` 过期行会累积，需要后续清理任务（M2 挂账）。
- 未来若要多设备管理/强制下线，family 结构已就位，加 token_version 即可，不用推翻本 ADR。
