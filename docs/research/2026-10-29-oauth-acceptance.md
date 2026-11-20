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

已用本地 **Keycloak 26.0.8** 完成一次真实 OIDC 冒烟（一次性容器，H2 dev 存储，不触碰本地数据库）：

- realm：`agent-core`；client：`agent-core-web`（confidential，redirect URI `http://localhost:8080/api/auth/oauth/callback`）；用户 `alice`（`alice@example.com`，email verified）。
- 结果：
  1. Web 登录页显示「使用 Keycloak 登录」，授权后回到聊天页；
  2. OAuth-only 账号显示「建议设置本地密码」提醒；设置密码成功后切换为「修改密码」；
  3. 设置页解绑 Keycloak 成功；重新绑定后回到 `/settings/account?oauth=linked` 并显示成功提示；
  4. 关闭 `OAUTH_ENABLED` 重启后，登录页不再显示 OAuth 入口，`alice@example.com` + 新密码登录成功。
- 冒烟中发现并修复：绑定回调固定跳 `/settings/account`，但 Web 只有 `/settings`；已补同内容路由 `web/app/settings/account/page.tsx`。

未覆盖的 provider 差异（Entra/Apple/Logto 的 claim/issuer 特例）仍需部署时各自冒烟。

## 残余风险

- 单 issuer 首期；多 OIDC provider 需要再扩配置/UI。
- 关闭 provider 后，未补设密码的 OAuth-only 用户无法登录；需重新开启或管理员协助。
- 真实 provider 的 claim 差异（Entra/Apple 等）仍需要一次实际冒烟。
