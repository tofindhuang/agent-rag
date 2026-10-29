# OAuth / OIDC 登录验收记录

日期：2026-10-29
对应 spec：`docs/specs/2026-10-26-oauth-login.md`
关联：ADR-0027、ADR-0007
工单：`docs/tickets/oauth/01–06`

## 范围

- 通用 OIDC 单 issuer，code + PKCE S256 + discovery。
- 首次登录建无密码账号；已有邮箱不自动关联；设置页显式绑定/解绑。
- OAuth-only 账号补设本地密码；登录后非阻塞提醒。
- 回调只建立 refresh 会话，Web 走现有 `/refresh`；不保存 provider token。

## 自动化验收

| 套件 | 覆盖 | 结果 |
|---|---|---|
| `OAuthPropertiesTest` | 默认关闭、必填/URL/scopes 校验、secret 不泄露 | 6 例通过 |
| `OidcProviderClientTest` | 授权 URL、code 交换、ID Token 签名/iss/aud/nonce/exp、userinfo 兜底、缓存、超时、token auth 方式 | 17 例通过 |
| `OAuthStateCodecTest` | 签名回读、篡改、过期、跨密钥 | 4 例通过 |
| `OAuthLoginIntegrationTest` | 新用户登录、重复登录、已有邮箱拒绝、state 缺失/不匹配、provider denied、未验证/缺失邮箱、userinfo sub 冲突、provider 500、admin 邮箱、绑定/解绑/冲突/最后登录方式、补设密码 | 18 例通过 |
| `OAuthRateLimitIntegrationTest` | start 共享认证 IP 限流，第 3 次 429 | 1 例通过 |
| 既有认证回归 | 注册/登录/refresh/改密/logout-all/空密码账号 | 全部通过 |
| Web | `npm run typecheck`、`npm run build` | 通过 |
| 完整 server 套件 | 全量回归（含既有 KB/沙箱/认证） | **469 通过 / 0 失败 / 2 skipped** |

关键安全断言：

- 已有本地邮箱的 OAuth 登录返回 `OAUTH_ACCOUNT_EXISTS`，不写 `user_identities`。
- state 缺失/不匹配、过期、tamper 均拒绝；nonce/iss/aud/签名错误在 adapter 层拒绝。
- userinfo `sub` 与 ID Token 不一致返回 `OAUTH_CLAIM_MISMATCH`。
- 解绑最后一个登录方式返回 `LAST_LOGIN_METHOD`。
- OAuth 关闭时 `/config` 返回 `enabled=false`，`/start` 返回 `OAUTH_DISABLED`；邮箱密码登录不回归。
- 代码与日志不保存 provider token，不记录 code/state/token/client secret。

## 真实 provider 冒烟

**尚未执行。** 本机没有可用的 Keycloak/Logto 部署与 client 凭证；本轮使用 JDK `HttpServer` 的 fake OIDC provider 覆盖协议与异常矩阵。

部署真实 provider 时按以下步骤补一次：

1. `OAUTH_ENABLED=true`，配置 `OAUTH_ISSUER` / `OAUTH_CLIENT_ID` / `OAUTH_CLIENT_SECRET`；
2. `OAUTH_REDIRECT_URI=http(s)://<server>/api/auth/oauth/callback`，与 provider 注册值一致；
3. `OAUTH_WEB_BASE_URL=http(s)://<web>`；
4. 验证：新邮箱登录建号、已有邮箱拒绝并提示绑定、密码账号绑定后可 OAuth 登录、OAuth-only 补设密码后可用密码登录、关闭 `OAUTH_ENABLED` 后密码登录正常。

## 残余风险

- 单 issuer 首期；多 OIDC provider 需要再扩配置/UI。
- 关闭 provider 后，未补设密码的 OAuth-only 用户无法登录；需重新开启或管理员协助。
- 真实 provider 的 claim 差异（Entra/Apple 等）仍需要一次实际冒烟。
