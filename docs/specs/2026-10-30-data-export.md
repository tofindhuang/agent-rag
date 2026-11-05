# M3 切片 3：个人数据导出

日期：2026-10-30
状态：已实现并验收（见 `docs/research/2026-10-30-data-export-acceptance.md`）
关联 ADR：[0034](../adrs/0034-personal-data-export.md)、[0033](../adrs/0033-audit-events.md)、[0023](../adrs/0023-observability-mysql-read-only-admin.md)
关联需求：FR-6（安全）、FR-7（运营）、§7 M3
涉及模块：server、web、docs

## Problem Statement

用户数据分散在 14 张表与对象存储，没有自助导出入口；合规与信任都需要「把我的数据带走」这条路径。

## Solution

`POST /api/export/me` 同步流式返回 ZIP（`manifest.json` + 每域一个 JSON）；导出前做体积估算与每日次数限制，有密码的账号需二次确认，成功/失败都写审计。

## Implementation Decisions

### 1. 包内容

| 文件 | 内容 |
|---|---|
| `manifest.json` | `formatVersion`、`exportedAt`、各域条目数、排除项说明 |
| `account.json` | email、role、注册时间、`hasPassword`、外部身份（provider 名与绑定时间） |
| `sessions.json` / `messages.json` / `compactions.json` | 会话元数据、全部消息、压缩摘要 |
| `profile.json` | 事实（含版本链）、记忆集、记忆设置 |
| `knowledge-bases.json` | KB、文档元数据、评测标注、评测运行摘要 |
| `usage.json` | run 级 token 用量、执行记录摘要 |
| `audit.json` | 自己的审计事件（元数据） |

排除：密码哈希、API key 密文/提示、refresh token、他人数据、向量、KB 原文二进制。

### 2. 防护

- 体积估算：消息正文 + 记忆 + 评测标注的字符量；超过 `EXPORT_MAX_BYTES`（默认 200 MB）→ `EXPORT_TOO_LARGE`（413）。
- 频率：每用户每天 3 次（Redis `SETEX`，键 `export:count:<userId>:<yyyy-MM-dd>`），超限 429 `RATE_LIMITED`。
- 二次确认：`hasPassword` 为 true 时请求体必须带 `password`，校验失败 401；OAuth-only 账号仅需登录态。
- 审计：`user.data.export`，成功记条目数，失败记原因（`wrong_password`、`too_large`、`rate_limited`）。

### 3. 接口

```
POST /api/export/me
Content-Type: application/json
{ "password": "optional" }

200 → application/zip, Content-Disposition: attachment; filename="agent-core-export-2026-11-04.zip"
401 → UNAUTHORIZED（密码错误）
413 → EXPORT_TOO_LARGE
429 → RATE_LIMITED
```

### 4. 前端

设置页「导出我的数据」区块：说明包含/不包含的内容 → 确认对话框（有密码时输入密码）→ 浏览器下载。

## Out of Scope

- 异步导出与大包下载链接；
- KB 原文打包；
- 增量/定期导出；
- admin 代用户导出。

## Acceptance

1. 导出 zip 含 8 个文件，`manifest.json` 的条目数与内容一致。
2. 包内不出现密码哈希、API key、refresh token（用关键词断言）。
3. 密码错误 401 且审计记为失败；正确则成功。
4. 超出体积上限 413；超过每日次数 429。
5. 用户只能导出自己的数据（查询全部以 userId 收窄）。
6. 前端可下载；typecheck/build 通过。
