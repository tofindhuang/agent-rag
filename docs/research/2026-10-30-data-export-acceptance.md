# 个人数据导出验收记录

日期：2026-11-05（实现与验收完成日）
对应 spec：`docs/specs/2026-10-30-data-export.md`
关联：ADR-0034、ADR-0033

## 自动化

| 套件 | 覆盖 | 结果 |
|---|---|---|
| `ExportIntegrationTest` | ZIP 含 9 个条目（manifest + 8 域）；`messages.json` 含真实对话；`account.json` 含邮箱但不含 `$2a$` 哈希；整包不含 `password_hash`/`refresh_token`；跨用户隔离（B 的包不含 A 的正文）；密码错误 401 且审计记 `UNAUTHORIZED` | 3 例通过 |
| `ExportBudgetIntegrationTest` | 超过 `EXPORT_MAX_BYTES` 返回 413 `EXPORT_TOO_LARGE`；随后同日再请求 429 `RATE_LIMITED` | 1 例通过 |
| Web | `npm run typecheck`、`npm run build` | 通过 |

## 关键实现点

- 所有查询按 `user_id` 收窄（`messages`/`runs` 经 `sessions` 关联），包内不出现他人数据；
- 体积估算用 `CHAR_LENGTH(CAST(json AS CHAR))` 处理 JSON 列，估算值 ×2 作为保守余量，超限在**流开始前**拒绝；
- 每日次数用 Redis `export:count:<userId>:<yyyy-MM-dd>`（`SETEX` 1 天）；Redis 故障时放行（与限流 fail-open 一致）；
- 有密码的账号必须提交当前密码；OAuth-only 账号只需登录态；
- 审计 `user.data.export`：成功记一条，失败记 `reason`（`UNAUTHORIZED`/`EXPORT_TOO_LARGE`/`RATE_LIMITED`/`stream_failed`）。

## 未覆盖

- 未做浏览器级下载验收（前端只做了 typecheck/build；下载逻辑是 `blob` + `URL.createObjectURL`）；
- 未验证超大包（>200MB）的真实拒绝以外的降级路径——按设计就是拒绝并提示清理；
- 未包含 KB 原文二进制与向量（设计如此）。

## 残余风险

- 导出是重查询：限频 3 次/天 + 体积上限保护数据库，但单次仍需扫该用户的全部消息；
- ZIP 生成在请求线程内流式进行，超大但未超限的包会占用连接较久（上限 200MB）；
- 审计的 `detail` 只记原因，不记导出内容（这是刻意的）。
