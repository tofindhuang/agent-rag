# 压缩摘要可见验收记录

日期：2026-10-29
对应 spec：`docs/specs/2026-10-29-compaction-summary.md`
关联：ADR-0028、ADR-0019、ADR-0023
工单：`docs/tickets/compaction-summary/01–05`

## 范围

- 契约 `compaction` 事件纯增量增加可选 `summary`；aborted/无 result 不再发该事件。
- `session_compactions` 投影表 + 只读接口 `GET /api/chat/sessions/{id}/compactions`。
- Web 消息流内联折叠摘要卡，「已压缩」标记改为记录驱动并可点击滚动。

## 自动化验收

| 套件 | 覆盖 | 结果 |
|---|---|---|
| gateway `translate` 单测 | 成功压缩带 summary、空白 summary 省略、aborted 不发事件、无 result 不发事件 | 通过（gateway 137 tests） |
| `contract/v1/validate.mjs` | 8 份 fixture（新增 `07-compaction-aborted`） | 通过 |
| `SessionCompactionDaoIntegrationTest` | 插入/最近窗口排序/50 条上限/可空锚点与 tokens/级联删除 | 4 例通过 |
| `ChatProjectionServiceImplTest` | 锚点=最大消息 id、无 summary 不落行、空白不落行、200k 截断置 truncated | 4 例新增通过 |
| `SessionCompactionApiIntegrationTest` | 升序返回+total、空态、跨用户 404、归档 404→恢复可读、50 条上限 | 5 例通过 |
| `MessageProjectionIntegrationTest` | 事件→投影→API 全链路（fake gateway 带 summary） | 通过 |
| 完整 server 套件 | 全量回归 | **482 通过 / 0 失败 / 2 skipped**（docker 依赖跳过） |
| Web | `npm run typecheck`、`npm run build` | 通过 |

## 手工验收（真实触发压缩 + 浏览器）

用仓库 `tools/loadtest` 一次性栈（MySQL/Redis/Qdrant/RabbitMQ 独立端口，**不触碰本机共享库**）+ 本地 OpenAI 兼容 stub（`:8899`，返回固定摘要、上报 2900 tokens），以 `MODEL_ALLOW_PRIVATE_ENDPOINTS=true` 允许 gateway 访问本地 provider。

- 用户模型 `contextWindow=3000`，累计多轮长消息后 pi 按 threshold 触发自动压缩，`compaction` 事件携带 `summary`。
- 服务端投影落 `session_compactions`，`after_message_id=6`（即压缩时最后一条已投影消息），API 返回：

```json
{"items":[{"id":1,"afterMessageId":6,"reason":"threshold",
  "summary":"## 压缩摘要\n\n- 关键事实：用户常驻北京，偏好中文回复。\n- 项目数据库为 MySQL 8.4，向量库为 Qdrant。\n- 本次对话用于验收压缩摘要可见。",
  "truncated":false,"tokensBefore":2900,"tokensAfter":3978}],"total":1}
```

浏览器（Playwright 驱动 `http://localhost:3000`）：

1. 登录后进入该会话，消息流出现「历史已压缩」卡，副行含「阈值触发 · 2,900 → 3,978 tokens · 时间」，摘要正文可见，默认折叠（6 行），有「展开全文」按钮；
2. 点「展开全文」→ markdown 渲染为列表，按钮变「收起」；
3. 刷新页面 → 卡片仍在、回到折叠态（展开态不持久化）；
4. 再次发送消息后，「已压缩」标记出现且为可点击按钮；点击后最新摘要卡滚动进视口（`boundingBox` 顶部落在视口内）。

## 残余风险

- 卡片锚点是投影时刻；overflow 在轮内压缩时会落在上一条已完成消息之后，可能比真实切点略早（ADR-0028 已接受）。
- 上线前的会话无摘要行，接口返回空数组、UI 不显示缺失提示。
- `compaction` 事件语义收紧后，`compaction_events` 历史计数口径与旧数据不可直接比较（旧数据含失败假事件）。
- 摘要正文进入 MySQL，备份/导出数据面扩大，需与消息投影同等对待。
- 手工验收用的 stub 摘要由本地脚本返回，未用真实 LLM 验证摘要文本质量（文本质量由 pi 的 summarizer 保证，本片只负责可见性）。
