# OAuth / OIDC 登录与账号绑定

日期：2026-10-26
状态：已实现并验收（fake OIDC 全矩阵 + 真实 Keycloak 冒烟，见 `docs/research/2026-10-29-oauth-acceptance.md`）
关联 ADR：[0027](../adrs/0027-oauth-oidc-login.md)、[0007](../adrs/0007-jwt-access-rotating-refresh-auth.md)、[0023](../adrs/0023-observability-mysql-read-only-admin.md)
关联需求：FR-6（认证、多租户与安全）
涉及模块：server、web、契约文档（仅内部 API，不改 Java↔gateway 契约）

## Problem Statement

现在用户只能用邮箱密码注册和登录。产品把 OAuth 列为 M2 的 P1，但当前模型没有外部身份概念：

- `users.email` 是唯一标识，`password_hash` 必填；一个用 Google/Keycloak 登录的人无法进入系统；
- 如果按 provider 邮箱直接合并账号，IdP 账号被接管就等于本系统账号被接管；
- 如果每次 OAuth 都创建新账号，同一邮箱会出现两个身份，用户和运维都无法理解；
- 现有认证是无状态 JWT + httpOnly refresh cookie，没有 `HttpSession`，不能直接套 Spring 默认的 session 登录流程。

需要在不改变现有邮箱密码登录、JWT、refresh 轮换和 gateway 边界的前提下，增加一条可配置的 OIDC 登录路径，并给出明确的绑定、解绑和密码 fallback 规则。

## Solution

- 部署方配置一个标准 OIDC provider；登录页出现「使用 {providerName} 登录」。
- OAuth 回调只建立 refresh 会话，Web 继续走现有 `/api/auth/refresh` 获取 access token。
- 首次 OAuth 登录且邮箱不存在时创建账号（无本地密码）；邮箱已存在时不自动关联，提示先用原方式登录后在设置页绑定。
- 设置页可绑定/解绑外部身份，OAuth-only 用户可以补设本地密码；登录后显示非阻塞提醒。
- provider 故障、配置关闭或用户取消都不影响邮箱密码登录。

## User Stories

1. As a 访客, I want 用已配置的 OIDC provider 登录, so that 我不用再记一个密码
2. As a 新用户, I want 首次 OAuth 登录自动创建账号, so that 我不需要先走邮箱注册
3. As a 邮箱密码用户, I want OAuth 邮箱命中我的账号时不要静默合并, so that 我的账号不会被第三方身份接管
4. As a 邮箱密码用户, I want 登录后在设置页显式绑定 OAuth, so that 之后可以一键登录
5. As a 已绑定用户, I want 再次 OAuth 登录直接进入系统, so that 不用重复绑定
6. As a 用户, I want 在设置页看到已绑定的外部身份, so that 我知道哪些登录方式可用
7. As a 用户, I want 解绑一个外部身份, so that 我可以移除不再使用的登录方式
8. As a 用户, I want 系统阻止我解绑最后一个登录方式, so that 我不会把自己锁在账号外
9. As a OAuth-only 用户, I want 在设置页补设本地密码, so that provider 不可用时仍有 fallback
10. As a OAuth-only 用户, I want 登录后看到可关闭的设密码提醒, so that 我知道有 fallback 但不会被强制打断
11. As a 用户, I want provider 邮箱之后变化时仍能登录, so that 我不会因为 IdP 改邮箱而失去账号
12. As a 用户, I want 在 provider 侧取消授权后回到登录页并看到“已取消”, so that 我知道没有发生错误
13. As a 用户, I want provider 暂时不可用时仍能用密码登录, so that 单点故障不影响整个系统
14. As a 用户, I want 绑定成功/失败后回到设置页并看到明确结果, so that 我不用猜操作是否生效
15. As a 管理员, I want verified email 命中 `ADMIN_EMAILS` 的 OAuth 账号仍是 admin, so that 管理员规则在两条注册路径一致
16. As a 运维, I want 用一个开关关闭 OAuth, so that provider 维护或安全事件时可以立即回退
17. As a 运维, I want OAuth 配置错误在启动时暴露, so that 不会等到用户登录才发现
18. As a 运维, I want provider discovery/JWKS 不阻塞服务启动, so that provider 抖动不影响密码登录和聊天
19. As a 安全负责人, I want state/nonce/PKCE 和固定回调地址, so that 登录不能被 CSRF、重放或开放重定向利用
20. As a 安全负责人, I want 不保存 provider token, so that 第三方长期凭证不进入本系统
21. As a 安全负责人, I want OAuth 端点纳入认证限流, so that discovery/token 交换不能被随意刷
22. As a 开发者, I want OIDC 适配器隔离在端口后, so that 以后换 pac4j 或多协议时不改账号业务
23. As a 开发者, I want 用 fake OIDC 覆盖异常路径, so that CI 不依赖真实 provider
24. As a 用户, I want 登出只结束本系统会话, so that 不会意外退出我其他使用同一 provider 的应用

## Implementation Decisions

### 1. 领域模型与 Schema

新增 `user_identities` 表（up-only 迁移，不生成 down）：

| 列 | 类型 / 约束 |
|---|---|
| `id` | `BIGINT` 自增主键 |
| `user_id` | `BIGINT NOT NULL`，FK → `users(id)` `ON DELETE CASCADE` |
| `issuer` | `VARCHAR(255) NOT NULL`，大小写敏感 collation |
| `subject` | `VARCHAR(255) NOT NULL`，大小写敏感 collation |
| `created_at` | `DATETIME(6) NOT NULL` |
| `last_login_at` | `DATETIME(6) NULL` |

约束：

- `UNIQUE (issuer, subject)`：一个外部身份只能属于一个本地用户；
- `UNIQUE (user_id, issuer)`：同一用户在同一 provider 下只能有一个身份；
- 索引 `user_id` 用于设置页查询。

`users.password_hash` 改为 nullable：`NULL` 表示该账号没有本地密码。现有账号的 hash 不变；邮箱密码注册仍强制密码。`users.email` 仍是本地账号唯一标识，不因 provider 邮箱变化而修改。

领域对象增加 `UserIdentity`；`User.passwordHash` 允许 null。`UserProfile` / `MeResponse` 增加 `hasPassword`。

### 2. 配置

新增 `app.oauth` 配置（环境变量同名大写）：

| 配置 | 默认 | 说明 |
|---|---|---|
| `enabled` | `false` | 显式开关；关闭时登录页不显示 OAuth |
| `issuer` | 空 | OIDC issuer，必须与 discovery 的 `issuer` 完全一致 |
| `client-id` | 空 | OAuth client id |
| `client-secret` | 空 | 只从环境注入，不写日志/仓库 |
| `redirect-uri` | 空 | 本服务回调地址，必须与 provider 注册值一致 |
| `web-base-url` | 空 | Web 根地址，用于固定成功/失败跳转 |
| `provider-name` | `OIDC` | 登录按钮与设置页展示名 |
| `scopes` | `openid email profile` | 必须包含 `openid email` |

启动时只校验格式：`enabled=true` 时必填项非空、URL 为绝对 http(s)、`redirect-uri` 与 `web-base-url` 合法；生产环境非 localhost 必须 HTTPS。不拉取 discovery。配置非法或缺失时启动失败，并指出缺失项；不打印 secret。

公开 `GET /api/auth/oauth/config` 返回 `{enabled, providerName}`；不暴露 issuer/client-id。关闭时 `enabled=false`，登录页隐藏入口。

### 3. OIDC 适配器端口

业务层只依赖 `OidcProviderClient`，不依赖具体 HTTP/OIDC 库：

- 生成授权 URL：输入 state、nonce、code challenge，返回 provider authorization URL；
- 用授权码换 token：输入 code、code verifier，返回 ID Token、access token（仅用于本次 userinfo）；
- 解析并校验 ID Token；
- 可选调用 userinfo（仅当 ID Token 缺少邮箱时）；
- 提供当前 issuer 的展示信息。

实现使用 JDK `HttpClient` + 现有 Nimbus JOSE，不新增依赖。discovery 缓存 1 小时；JWKS 按 `kid` 缓存，未知 `kid` 时最多刷新一次（60 秒内不重复刷新）；HTTP 连接超时 5 秒、请求超时 10 秒。token endpoint 客户端认证按 discovery 的 `token_endpoint_auth_methods_supported` 选择：优先 `client_secret_basic`，否则 `client_secret_post`；都不支持则返回 `OAUTH_UNAVAILABLE`。

### 4. 授权请求与 state

- `GET /api/auth/oauth/start` 公开，302 到 provider。参数：`response_type=code`、`client_id`、`redirect_uri`、`scope`、`state`、`nonce`、`code_challenge`、`code_challenge_method=S256`。
- `POST /api/auth/oauth/link` 需要 JWT，返回 `{authorizationUrl}`；Web 用 `authFetch` 拿到 URL 后顶层跳转，避免丢内存 access token。
- state cookie：`agentcore_oauth_state`，`HttpOnly`、`SameSite=Lax`、`Path=/api/auth/oauth`、`Max-Age=10m`、`Secure` 跟随 `app.auth.cookie-secure`。
- cookie 内容：版本、`state`、`nonce`、`code_verifier`、`intent`（login/link）、绑定用户 ID（仅 link）、签发时间；JSON 后 base64url，追加 HMAC-SHA256。签名密钥由 `JWT_SECRET` 做 domain separation 派生。
- 回调校验签名、过期、state 与 query 相等；校验后清除 cookie。state/nonce/verifier 每次请求随机生成。

### 5. 回调与 ID Token 校验

`GET /api/auth/oauth/callback` 公开，流程：

1. provider 返回 `error=access_denied` → `OAUTH_DENIED`；
2. 校验 state cookie 与 query；缺失/不匹配/过期 → `OAUTH_STATE_INVALID`；
3. 用 code + verifier 换 token；
4. 校验 ID Token：JWKS 签名、`iss` 与配置完全一致、`aud` 包含 client_id（多 audience 时校验 `azp`）、`exp`/`iat`（允许 60 秒时钟偏差）、`nonce` 与 cookie 一致；
5. 取 `sub`（非空、≤255 字符）；`issuer` ≤255；
6. **登录 intent 且 `(issuer, sub)` 已存在**：直接进入登录流程，不再要求 provider 返回邮箱（Q13）；provider 邮箱变化不影响登录；
7. 其余情况（新账号、绑定）解析邮箱：优先 ID Token 的 `email`，缺失才调一次 userinfo；要求 `email_verified` 为 true（兼容字符串 `"true"`）；userinfo 的 `sub` 必须与 ID Token 一致；邮箱经现有 `EmailNormalizer` 归一化并校验格式、长度 ≤255；
8. 按 intent 执行账号流程；provider 原始错误只写日志。

成功：

- 登录：设置 refresh cookie，302 到 `{web-base-url}/`；
- 绑定：不轮换 refresh，302 到 `{web-base-url}/settings/account?oauth=linked`。

失败：302 到 `{web-base-url}/login?oauth_error=<CODE>`（登录）或 `{web-base-url}/settings/account?oauth_error=<CODE>`（绑定）。不回传 provider 原始错误描述。

### 6. 账号流程

**登录（intent=login）**

- `(issuer, subject)` 已存在：加载用户，best-effort 更新 `last_login_at`，发 refresh token；不检查 provider 邮箱是否变化，也不要求本次返回邮箱。
- 不存在、邮箱已被任何本地账号占用：返回 `OAUTH_ACCOUNT_EXISTS`，不创建、不绑定、不自动登录。
- 不存在、邮箱未被占用：同一事务创建 `users`（`password_hash=NULL`，role 按 `ADMIN_EMAILS`）与 `user_identities`；然后发 refresh token。
- 并发首次登录：依赖 `UNIQUE(email)` 与 `UNIQUE(issuer, subject)`；捕获冲突后回读身份一次，成功则继续登录，否则返回对应冲突错误。

**绑定（intent=link）**

- 仅允许已登录用户发起；state 中携带 userId。
- 回调时校验 state userId 与当前 refresh cookie 对应用户一致；不一致按 `OAUTH_STATE_INVALID` 拒绝，避免 A 发起、B 登录后误绑。
- provider 已验证邮箱必须与当前本地账号邮箱完全一致（归一化后比较），否则 `OAUTH_EMAIL_MISMATCH`。
- `(issuer, subject)` 已绑定当前用户：幂等成功。
- 已绑定其他用户：`OAUTH_IDENTITY_IN_USE`。
- 当前用户已有该 issuer 的其他身份：`OAUTH_ALREADY_LINKED`。
- 成功只插入身份，不修改本地邮箱、不改变 role、不轮换当前会话。

**解绑**

- `DELETE /api/auth/identities/{id}`，需 JWT；只能删除自己的身份，不存在返回 404。
- 若删除后用户既没有密码、也没有其他身份，返回 `LAST_LOGIN_METHOD`，不删除。
- 成功 204；不改变当前 access/refresh 会话。

**邮箱密码登录的兼容**

- `password_hash=NULL` 的账号走密码登录时，用固定 dummy hash 做一次 BCrypt 比较，返回与“密码错误/账号不存在”相同的 401，不泄露“此账号没有密码”。
- OAuth-only 账号调用现有 `change-password`（需要当前密码）时返回 401；补设密码必须走 `set-password`。

**补设密码**

- `POST /api/auth/set-password`，需 JWT，body `{newPassword}`。
- `password_hash` 已存在 → `PASSWORD_ALREADY_SET`，引导使用现有 `change-password`。
- 校验 8–72 字节；更新 hash；吊销该用户其他 refresh token，保留当前请求携带的 refresh token（若可识别）；返回 204。
- 成功后 `hasPassword=true`；不强制重新登录。

### 7. API 与错误码

新增/修改端点：

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/api/auth/oauth/config` | 公开 | `{enabled, providerName}` |
| GET | `/api/auth/oauth/start` | 公开 | 302 到 provider |
| POST | `/api/auth/oauth/link` | JWT | 返回 `{authorizationUrl}` |
| GET | `/api/auth/oauth/callback` | 公开 | 302 回 Web |
| GET | `/api/auth/identities` | JWT | 当前用户的外部身份列表 |
| DELETE | `/api/auth/identities/{id}` | JWT | 解绑 |
| POST | `/api/auth/set-password` | JWT | OAuth-only 账号补设密码 |
| GET | `/api/auth/me` | JWT | 增加 `hasPassword` |

身份列表项：`{id, issuer, providerName, linkedAt, lastLoginAt}`；不返回 subject 和 provider token。

错误码（加入 `ErrorCode`，均不包含 provider 原始 message）：

| 错误码 | HTTP | 可重试 | 场景 |
|---|---|---|---|
| `OAUTH_DISABLED` | 404 | 否 | OAuth 未启用时直接访问 start/link |
| `OAUTH_UNAVAILABLE` | 503 | 是 | discovery/token/JWKS/网络失败 |
| `OAUTH_DENIED` | 400 | 否 | 用户在 provider 侧取消 |
| `OAUTH_STATE_INVALID` | 400 | 否 | state/nonce/cookie/PKCE 校验失败 |
| `OAUTH_EMAIL_UNVERIFIED` | 403 | 否 | 邮箱缺失或未验证 |
| `OAUTH_EMAIL_MISMATCH` | 403 | 否 | 绑定邮箱与本地账号不一致 |
| `OAUTH_IDENTITY_IN_USE` | 409 | 否 | 身份已绑定其他用户 |
| `OAUTH_ALREADY_LINKED` | 409 | 否 | 当前用户已有同 issuer 的其他身份 |
| `OAUTH_ACCOUNT_EXISTS` | 409 | 否 | 登录邮箱已被本地账号占用 |
| `LAST_LOGIN_METHOD` | 409 | 否 | 解绑会移除最后一个登录方式 |
| `PASSWORD_ALREADY_SET` | 409 | 否 | 已有本地密码，应走 change-password |
| `OAUTH_CLAIM_MISMATCH` | 403 | 否 | ID Token 与 userinfo 的 sub 冲突 |

### 8. Web 交互

- 登录页/注册页加载 `GET /api/auth/oauth/config`；enabled 时在邮箱表单下方显示「使用 {providerName} 登录」，链接到 start。两页共用同一流程。
- 回调错误读取 `oauth_error` query：`OAUTH_DENIED` 显示 notice「已取消登录」；其他显示对应中文错误；`OAUTH_UNAVAILABLE` 提供「重试」。
- OAuth 登录成功后，AuthProvider 通过 `/me` 恢复会话；`hasPassword=false` 时显示非阻塞 banner：
  「你正在用 OAuth 登录。建议设置本地密码，避免 OAuth 不可用时无法登录。」→「去设置」/「稍后」。
  banner 关闭状态按 userId 存 localStorage；设置页始终保留入口。
- 设置页账号区新增「登录方式」：
  - 列出已绑定身份（providerName、绑定时间）；
  - 「绑定 {providerName}」调用 `POST /oauth/link` 并跳转；
  - 每个身份提供「解绑」，二次确认；`LAST_LOGIN_METHOD` 显示明确提示；
  - `hasPassword=true` 显示现有改密表单；false 显示「设置密码」表单（新密码 + 确认）。
- 绑定成功回设置页显示「已绑定」；失败显示 `oauth_error` 对应文案。
- 登出只调用现有本地 logout，不跳 provider。

### 9. 安全与运维

- OAuth start/callback/link 纳入现有认证 IP 限流（`authIpRpm`）；`/oauth/config` 不限流。
- 回调 GET 不依赖 access token；state cookie 提供 CSRF/重放保护；refresh cookie 继续 `SameSite=Lax`。
- 不保存 provider token，不申请 `offline_access`；日志不记录 code、state、token、client secret、完整邮箱。
- 固定 `web-base-url` 与固定路径，不接受任意 `returnTo`。
- 管理员规则与邮箱密码注册一致：verified email 命中 `ADMIN_EMAILS` 时创建为 admin，`AdminBootstrap` 仍可提升既有账号。
- 结构化日志：事件（start/callback/link/unlink/set-password）、intent、结果错误码、issuer、userId（已知时）、耗时；provider 错误只记录标准 error code。
- 首期不新增指标；如需要，后续加 `oauth_attempts_total{intent,result}`，不在本期阻塞。

### 10. 迁移与发布

- 一条 up-only 迁移：`users.password_hash` 改 nullable；创建 `user_identities` 及唯一约束/外键。迁移必须可重复执行（`CREATE TABLE IF NOT EXISTS`；`MODIFY` 重跑无副作用）。
- 发布顺序：先迁库/部署代码（`OAUTH_ENABLED=false`）→ 配置 issuer/client → 开启开关 → 登录页出现入口。
- 回退：`OAUTH_ENABLED=false` 立即隐藏入口；已有身份保留。未设置密码的 OAuth-only 用户会暂时无法登录，需重新开启或由管理员协助。

## Testing Decisions

好的测试验证外部行为：HTTP 状态/错误码、cookie 属性、数据库身份行、账号是否被创建/绑定、是否发生自动合并；不测 Docker/HTTP 调用顺序或私有字段。

测试缝（按最高层优先）：

1. **`OidcProviderClient` 端口**：用 JDK `HttpServer` 的 fake OIDC provider 提供 discovery/JWKS/token/userinfo；Nimbus 生成测试 RSA 密钥和 ID Token。覆盖签名、iss/aud/exp/nonce、PKCE、userinfo 兜底、provider 500/超时。
2. **服务层**：账号创建、已有邮箱拒绝、绑定邮箱一致、身份冲突、解绑最后方式、补设密码、并发首次登录。
3. **HTTP/Spring 集成**：`@SpringBootTest` + 现有 MySQL/Redis Testcontainers + fake OIDC；用 cookie jar 走 start→callback→refresh，断言 refresh cookie、302 目标、`hasPassword`、身份列表、错误 query。
4. **Web**：`npm run typecheck` + `npm run build`；手工验收登录/绑定/解绑/提醒/错误文案。现有仓库没有 Web 测试框架，本期不新引入。
5. **真实 provider**：可选手动冒烟（Keycloak/Logto），不进 CI 默认路径。

必须覆盖的安全用例：

- state 缺失、state 不匹配、cookie 过期、nonce 不匹配、PKCE verifier 错误；
- `email_verified=false`/缺失、无邮箱、userinfo 与 ID Token `sub` 冲突；
- 已有密码账号同邮箱 OAuth 登录被拒绝且不产生身份行；
- 同一用户同 issuer 第二个身份被拒绝；身份已绑他人被拒绝；
- 解绑最后一个登录方式被拒绝；
- OAuth-only 账号密码登录返回统一 401，不泄露原因；
- OAuth 关闭时密码登录、`/me`、聊天不受影响；
- start/callback 限流触发 429；
- 管理员邮箱 OAuth 创建后 role=admin。

## Out of Scope

- 邮箱验证、找回密码、MFA、passkey。
- RP-initiated logout / provider end-session。
- 保存 provider access/refresh token，或代用户调用第三方 API。
- 同时配置多个 issuer / 多 provider 登录页。
- GitHub、微信、QQ、Gitee 等非标准 OAuth2 适配器。
- SAML、CAS、LDAP；pac4j 迁移。
- 账号删除、本地邮箱修改、身份管理后台。
- OAuth 账号的密码强度策略升级（沿用现有 8–72 字节 BCrypt 限制）。

## Further Notes

- `issuer` 是 OIDC 的精确标识：不做尾斜杠/大小写归一化。更换 issuer 会让旧身份无法匹配，除非恢复原配置。
- `users.email` 仍是本地账号唯一标识；provider 邮箱只用于创建/绑定校验，不作为后续同步源。
- 首期单 issuer，但 `user_identities` 1:N 与 `(issuer, subject)` 唯一约束已支持未来多 OIDC provider。
- 如果未来需要 SAML/CAS/微信，优先替换 `OidcProviderClient` 后面的实现（pac4j），账号、绑定、refresh、错误码和 Web 流程不变。
- OAuth 是可选能力：provider 故障、配置关闭或网络不可达都不应影响邮箱密码登录、聊天和其他工具。
