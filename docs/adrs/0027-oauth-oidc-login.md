# OAuth 登录用通用 OIDC、显式绑定与无状态回调

Date: 2026-10-26

## Status

Accepted

## Context

FR-6 要求 P1 接 OAuth，但当前认证只有邮箱密码：

- `users.email` 是账号唯一标识，`password_hash` 非空；没有外部身份表；
- access token 是自签 HS256 JWT，只存于 Web 内存；refresh token 是不透明串，存 httpOnly cookie；
- 认证链完全无 `HttpSession`，gateway 与 Java 的边界也不允许把会话搬进 Spring 默认的 session 流程。

OAuth 的关键风险不是“能不能换 token”，而是**邮箱命中已有账号时会不会静默合并**。一旦按 provider 邮箱自动关联，IdP 账号被接管就等价于本系统账号被接管；而如果绑定条件过严，用户又会觉得 OAuth 只能注册新账号。需要同时定义协议范围、账号关联、密码 fallback、回调和状态存储。

## Decision

### 1. 协议范围

- 首期只做**通用 OIDC**：authorization code + PKCE（S256）+ discovery；单 issuer，部署时通过环境配置。
- 只支持标准 OIDC provider（Google、Entra ID、Okta、Keycloak、Logto 等）；GitHub、微信、SAML/CAS 不在首期。
- 不保存 provider 的 access/refresh token，不申请 `offline_access`；provider token 只用于一次 userinfo 兜底，随后丢弃。
- 手写 OIDC 适配器，隔离在 `OidcProviderClient` 端口后；业务层不依赖 OAuth 框架。pac4j 留作未来多协议（SAML/CAS/微信）扩展。

### 2. 身份模型

- 外部身份定义为 `(issuer, subject)`；`subject` 是 ID Token 的 `sub`，不是邮箱。
- 一个本地用户可绑定多个外部身份；同一用户在同一 issuer 下只能有一个身份。
- 新增 `user_identities` 表；`(issuer, subject)` 全局唯一、`(user_id, issuer)` 唯一、`user_id` 外键级联删除；`issuer`/`subject` 使用大小写敏感 collation。
- `users.password_hash` 允许为 `NULL`：OAuth 创建的账号可以没有本地密码。

### 3. 账号关联

- **不按邮箱自动关联**。OAuth 邮箱命中已有账号（无论该账号是否有密码）时拒绝登录，提示先使用原登录方式登录后在设置页绑定。
- 新邮箱首次 OAuth 登录自动创建账号；创建与绑定新身份要求 `email_verified=true`。
- 显式绑定要求 provider 已验证邮箱与本地账号邮箱完全一致；绑定后不修改本地邮箱。
- 已有 `(issuer, subject)` 再次登录只认 `sub`；provider 邮箱之后变化不拒绝登录、不自动同步本地邮箱。
- 同一用户已有该 issuer 的其他身份时拒绝绑定（`OAUTH_ALREADY_LINKED`）；身份已绑其他用户时拒绝（`OAUTH_IDENTITY_IN_USE`）。

### 4. 回调与登录态

- OAuth 回调只建立 refresh 会话：设置 refresh cookie，然后 302 回 Web；Web 继续用现有 `/api/auth/refresh` 换 access token。**不把 access token 放进 URL**。
- `state`、`nonce`、PKCE verifier、intent、绑定用户 ID 放在 HMAC 签名的 HttpOnly cookie 中，10 分钟过期；回调校验后清除。签名密钥从 `JWT_SECRET` 做 domain separation 派生，不新增必填 secret。
- Web 跳转地址固定由配置的 Web base URL 派生，不接受任意 `returnTo`，避免开放重定向。
- 登出只登出本地，不调用 provider 的 end-session。

### 5. 密码 fallback

- OAuth-only 账号可通过已登录的 `POST /api/auth/set-password` 补设本地密码；成功后保留当前会话、吊销其他 refresh token。
- OAuth 登录后显示非阻塞、可关闭的“建议设置密码”提醒；设置页始终保留入口。
- 允许解绑外部身份，但不能删掉最后一个登录方式。

### 6. 配置与安全

- 显式 `OAUTH_ENABLED` 开关；启用时启动只校验配置存在性与 URL 格式，不拉 provider 网络。
- OAuth 的 `start` / `callback` 纳入现有认证 IP 限流；OAuth 故障不影响邮箱密码登录。
- 管理员仍由 verified email 命中 `ADMIN_EMAILS` 决定，与邮箱密码注册一致。

## Considered Options(被否的备选)

- **先接 GitHub 或微信** — 单个 provider 能更快交付，但它们不是标准 OIDC；GitHub 要额外处理 `/user/emails`，微信用平台自定义 OAuth2，后续每加一个 provider 都要重写一套。通用 OIDC 一次解决多数 provider。
- **按已验证邮箱自动关联** — 体验最顺，但 IdP 账号被接管就等于本系统账号被接管，且用户可能不知道自己被绑定；与现有 fail-closed 的安全取向冲突。
- **允许绑定不同邮箱** — 灵活，但会出现“某人的 Google 身份登录进另一个邮箱的账号”的认知混乱；首期以本地邮箱为唯一事实源更简单。
- **引入 Spring Security OAuth2 Client** — 协议实现成熟，但默认依赖 `HttpSession` 保存授权请求与 authorized client；接到现有无状态 JWT 模型需要自定义 repository/success handler，反而增加两套认证抽象。
- **引入 pac4j** — 多协议能力强，但当前只需要单 OIDC，导入一套新的安全引擎和依赖治理成本偏高；保留为未来扩展。
- **用 Redis 存 state** — 标准服务端 state，但 OAuth 登录会额外依赖 Redis；签名 cookie 已能提供同样的 CSRF/重放保护。
- **把 access token 放在回调 URL** — 实现快，但会进入浏览器历史、日志和截图，并绕过 refresh 轮换。
- **保存 provider token** — 未来可代用户调用第三方 API，但本期没有需求，长期凭证会扩大泄露面。
- **要求 OAuth 用户首次登录必须设置密码** — 最安全，但打断 OAuth 的顺滑体验，且密码只是 fallback，不是使用前提。

## Consequences

- 新增 `user_identities` 表和一次 `password_hash` nullable 迁移；现有邮箱密码用户不受影响。
- 外部身份按 `(issuer, subject)` 存储；更换 issuer 后旧身份无法匹配，除非恢复原配置。`issuer` 不做大小写/尾斜杠归一化。
- 首期只有一个 issuer，登录页只有一个 OAuth 按钮；schema 已支持多身份，未来加第二个 OIDC provider 不需要改表。
- OAuth-only 用户在 provider 被关闭且未补设密码时无法登录；部署文档需要说明这一回退路径。
- 手写 OIDC 适配器把 state/nonce/PKCE/JWKS 的测试责任放在本仓库；用 fake OIDC provider 覆盖异常路径，真实 provider 只做可选手动冒烟。
- pac4j 未来若替换 `OidcProviderClient` 实现，账号、绑定、refresh、错误码与前端流程保持不变。
