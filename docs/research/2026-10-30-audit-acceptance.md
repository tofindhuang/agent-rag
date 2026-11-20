# 审计日志验收记录

日期：2026-11-04（实现与验收完成日）
对应 spec：`docs/specs/2026-10-30-audit-log.md`
关联：ADR-0033、ADR-0023、ADR-0030
工单：`docs/tickets/audit-log/01–04`

## 自动化

| 套件 | 覆盖 | 结果 |
|---|---|---|
| `AuditServiceTest` | 写入元数据（不含密钥）、DAO 抛错不影响业务且计数 | 2 例通过 |
| `AuditRetentionJobTest` | 180/30 天截止时间计算与删除调用 | 1 例通过 |
| `AuditLogIntegrationTest` | 注册/登录失败/登录/供应商新增落库；改密 + 归档 + purge 落库；detail 不含 API key；用户只能读自己的事件；非 admin 访问 403、admin 可读全量 | 4 例通过 |
| 回归 | `AuthLogin`、`AuthAccountSecurity`、`ModelProvider`、`KbSettings` 套件 | 45 例通过 |
| Web | `npm run typecheck`、`npm run build` | 通过 |

## 覆盖的动作

认证：`auth.register`、`auth.login`、`auth.login.failed`、`auth.refresh.reuse`、`auth.logout.all`、`auth.password.set`、`auth.password.change`、`auth.identity.unlink`
配置：`model.provider.create/update/delete/default`、`kb.settings.update/reset`、`kb.threshold.update`
破坏性：`session.archive/restore/purge`、`kb.document.delete`、`kb.delete`、`memory.fact.delete`

## 关键断言

- `detail` 只写摘要（如 `{"apiKeyChanged":true}`），**不含** `sk-secret` 之类的密钥；
- 审计写失败（模拟 DAO 抛错）时业务调用不抛出，`agentcore_audit_write_failures_total` +1；
- `GET /api/audit/me` 只返回调用者自己的行（按 `actor_user_id` 过滤）；
- `GET /api/admin/audit` 对普通用户 403、对 `app.admin.emails` 中的账号返回全量元数据。

## 未覆盖

- 未在一次性栈上做浏览器级手工验收：本次走的是真实 HTTP + Testcontainers（MySQL/Redis）的集成测试 + 前端构建；
- `auth.identity.link` 未埋点（绑定成功路径在 OAuth 服务里与登录共用回调，留待后续统一）；
- 审计没有防篡改（WORM）能力，admin 也没有写操作可审计。

## 残余风险

- 审计是 best-effort：数据库故障时可能丢一条，计数可见但不阻塞业务；
- `session.purge` 会同时删除会话与消息，但审计行不清理（`actor_user_id` 无外键，正是为此）；
- 保留 180 天；更长的合规要求需要改配置或引入归档。
