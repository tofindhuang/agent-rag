# 压缩摘要可见（FR-4 P1）

日期：2026-10-29
状态：Phase 1 已完成（Q1–Q11 逐问确认），待实现
关联 ADR：[0028](../adrs/0028-compaction-summary-visibility.md)、[0019](../adrs/0019-compaction-config-and-context-usage.md)、[0023](../adrs/0023-observability-mysql-read-only-admin.md)
关联需求：FR-4（自动总结记忆 / compaction，P1「摘要可见」）、FR-7（admin 不可读对话正文）
涉及模块：gateway、server、web、契约文档（`contract/v1`，纯增量）

## Problem Statement

compaction 已经上线：长对话在接近模型窗口时会自动压缩，用户能在会话头部看到上下文占比。但摘要正文被丢在 gateway 里：

- `compaction_end` 的 `CompactionResult.summary` 由 `translateCompaction` 丢弃，契约 `compaction` 事件只有 `reason/tokensBefore/tokensAfter`；
- Web 只有一句「上下文已压缩」的瞬时提示，以及一个从 `context_usage` 下降 ≥15 个百分点**猜**出来的「已压缩」标记——刷新后丢失、跨实例后不准、点不动；
- 用户无法判断「第 1 轮说过的关键事实」是否还在摘要里，也无法人工兜底（FR-4 的「不失忆保障」缺了可见性这一半）。

`compaction_events` 表只服务 FR-7 的运营计数，而 admin 不可查看对话正文，因此摘要正文既不能塞进该表，也不能出现在任何 admin 响应里。

## Solution

- gateway 把 `result.summary` 作为**可选字段**带进 `compaction` 事件（纯增量，契约仍 v1）；`aborted`/无 `result` 时不再发该事件。
- Java 把摘要投影进新表 `session_compactions`，锚点取「事件到达时该会话已投影的最大 `messages.id`」；`compaction_events` 保持不变，继续只存指标。
- 新增只读接口 `GET /api/chat/sessions/{sessionId}/compactions`，权限与历史接口一致（仅 active 且本人）。
- Web 打开会话时拉一次，按锚点把可折叠摘要卡插进消息流；「已压缩」标记改为记录驱动并可点击滚动到最新卡片。
- 上线前已被压缩的会话不回填，接口返回空数组，UI 不显示任何「缺失摘要」提示。

## User Stories

1. As a 用户, I want 在对话里看到「此处之前的历史已被摘要替换」, so that 我知道上下文范围发生了什么
2. As a 用户, I want 展开摘要读全文, so that 我能核对早先的关键事实是否还在
3. As a 用户, I want 摘要默认折叠且刷新后仍折叠, so that 长摘要不会把页面撑爆
4. As a 用户, I want 看到压缩是阈值触发、溢出还是手动触发, so that 我能判断是不是模型窗口太小
5. As a 用户, I want 看到压缩前后 token 数, so that 我能评估压缩收益
6. As a 用户, I want 看到压缩发生时间, so that 我能把它和某次对话对上
7. As a 用户, I want 点击「已压缩」标记跳到最新摘要, so that 不用翻历史找
8. As a 用户, I want 摘要卡只在对应历史窗口加载后出现, so that 消息流不会被凭空插入打断
9. As a 用户, I want 摘要消失/失败时不阻塞聊天, so that 可见性问题不影响正常使用
10. As a 用户, I want 别人的会话摘要不可读, so that 我的对话内容不会泄露
11. As a 用户, I want 归档会话必须先恢复才能看摘要, so that 归档的「不可读」语义保持一致
12. As a 用户, I want 彻底删除会话后摘要一并删除, so that 删除是彻底的
13. As a Admin, I want 任何 admin 接口都读不到摘要正文, so that 只读观测角色看不到对话内容
14. As a 开发者, I want 摘要走事件而不是新建 gateway 读接口, so that 归档/多实例/恢复都不需要额外读路径
15. As a 开发者, I want 摘要表与指标表分离, so that 正文永远不会顺着 admin 报表泄露
16. As a 开发者, I want 老 gateway 不发 summary 时系统照常工作, so that 滚动升级期间不报错
17. As a 开发者, I want 压缩失败不再伪装成成功事件, so that 用户提示与运营计数都忠实
18. As a 运维, I want 摘要写入有硬上限与告警日志, so that 异常超长摘要不会撑爆存储
19. As a 运维, I want 摘要随会话备份/清理, so that 数据生命周期与消息投影一致
20. As a 测试者, I want 投影、权限、上限、级联都有自动化用例, so that 不依赖手工点页面

## Implementation Decisions

### 1. 契约（纯增量，版本仍 v1）

```ts
| {
    type: "compaction";
    data: {
      reason: "manual" | "threshold" | "overflow";
      tokensBefore: number;
      tokensAfter: number | null;
      summary?: string; // 新增：result.summary 非空白时才带
    };
  }
```

- gateway `translateCompaction`：仅当 `event.result` 存在且 `result.summary.trim()` 非空时附带 `summary`（原文不 trim，避免破坏 markdown 缩进）。
- **`aborted === true` 或 `result` 缺失时不发 `compaction` 事件**（仍按现有逻辑发 `context_usage`）。这是对本片验收自洽的必要修正：否则会出现「已压缩」提示但没有摘要。
- 不新增 `firstKeptEntryId`（无消费方）。
- 同步更新：`contract/v1/README.md` 事件表与映射表、`contract/v1/validate.mjs` 的事件枚举/形状、`contract/v1/fixtures/gateway/03-compaction.json`。

### 2. Schema（up-only，幂等，无 down 文件）

`server/src/main/resources/db/migration/V20261029090000__session_compactions.up.sql`：

```sql
CREATE TABLE IF NOT EXISTS session_compactions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id CHAR(36) NOT NULL,
    after_message_id BIGINT NULL,
    reason VARCHAR(16) NULL,
    summary MEDIUMTEXT NOT NULL,
    truncated TINYINT(1) NOT NULL DEFAULT 0,
    tokens_before BIGINT NULL,
    tokens_after BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_session_compactions_session (session_id, id),
    CONSTRAINT fk_session_compactions_session FOREIGN KEY (session_id)
        REFERENCES sessions (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
```

- `session_id` 外键级联删除：purge / 删除会话时摘要一并消失。
- `after_message_id` **不加外键**：它只是展示锚点，消息行的生命周期由会话级联负责，加外键会让删除顺序耦合。
- 不写 `user_id`：会话已带归属，冗余列容易被 admin 链路误用。
- 表语义与 `compaction_events`（FR-7 指标，无正文）严格分离。

### 3. 投影

`ChatProjectionServiceImpl.recordCompaction` 扩展（保持原有 `compaction_events` 写入）：

| 情况 | `compaction_events` | `session_compactions` |
|---|---|---|
| 带非空 summary | 写 | 写，`after_message_id = SELECT MAX(id) FROM messages WHERE session_id = ?` |
| 无 summary（老 gateway） | 写 | 不写，不报错 |
| 摘要为空/空白 | 写 | 不写 |
| 摘要 > 200,000 字符 | 写 | 截断到 200,000 写入，`truncated = 1`，打 warn 日志（含 sessionId） |

- 同一事务内完成，失败不阻断 SSE 透传（沿用现有投影异常语义）。
- `after_message_id` 在该事务内取最大值，保证锚点不晚于卡片出现位置。

### 4. API

`GET /api/chat/sessions/{sessionId}/compactions`（`ChatController`，与 `history` 同一路由组）

- 鉴权：`sessionDao.findActiveOwned(sessionId, userId)`，为空 → `404 SESSION_NOT_FOUND`（跨用户与归档会话同码，不泄露存在性）。
- 查询：按 `id DESC` 取 50 条，再反转为升序返回；`total = COUNT(*)`。
- 响应：

```json
{
  "items": [
    {
      "id": 12,
      "afterMessageId": 431,
      "reason": "threshold",
      "summary": "## 已确认的事实\n...",
      "truncated": false,
      "tokensBefore": 12400,
      "tokensAfter": 3100,
      "createdAt": "2026-10-29T07:12:33.412Z"
    }
  ],
  "total": 3
}
```

- `total > items.length` 时前端提示「仅显示最近 50 次压缩」；**不提供更早摘要的分页**。
- 不写 Redis 缓存：每会话打开只读一次，MySQL 足够。

### 5. Web

- 打开会话时拉一次 `compactions`，按 `sessionId` 缓存在聊天状态里；切换会话重新拉取。
- **合并渲染**：摘要卡插在 `id === afterMessageId` 的消息之后；`afterMessageId` 为 `null` 时，仅在已加载窗口到达历史最前（`hasMore=false` 且是最早一页）时渲染在首条消息之前；锚点不在当前窗口内则不渲染（随翻页自然出现）。
- **实时插入**：运行中收到 `compaction` 事件时，用当前最后一条消息 id 作本地锚点立即插入卡片；重新打开会话后以服务端数据为准，不做对齐动画。
- 卡片：

| 元素 | 内容 |
|---|---|
| 标题 | `历史已压缩` |
| 副行 | 触发原因（`阈值触发` / `溢出压缩` / `手动压缩`）· `12,400 → 3,100 tokens` · 本地时间 |
| 正文 | markdown 渲染（复用 `MarkdownContent`），默认折叠约 6 行，按钮「展开全文 / 收起」 |
| 异常标注 | `truncated=true` 时显示「摘要过长，已截断保存」 |
| 缺失字段 | `tokensAfter` 为 `null` 时只显示 `12,400 tokens` |

- 展开态**不持久化**（刷新回到折叠）。
- `ContextUsageMeter` 的「已压缩」标记：`compactions.length > 0 || 本次会话收到过 compaction 事件`；可点击，滚动到最新摘要卡；**删除 `useChat.ts` 中基于 ≥15 个百分点下降的推断逻辑**。
- 接口失败：静默降级（不显示卡片、不弹错），聊天主流程不受影响。

### 6. 权限与生命周期

| 场景 | 行为 |
|---|---|
| 跨用户 / 不存在 | `404 SESSION_NOT_FOUND` |
| 归档会话 | `404`，需先 restore；恢复后照常可读 |
| purge / 删除会话 | 行随 `session_id` 外键级联删除 |
| admin | 无任何入口；admin 报表继续只读 `compaction_events` |
| 限流 | 普通读接口，仅走认证，不进认证 IP 限流 |

### 7. 兼容与失败

- 老 gateway（不发 `summary`）→ 新 Java 只写指标表，不写摘要表，不报错。
- 摘要行写入失败 → SSE 透传不受影响，仅该次摘要不可见。
- 本功能上线前的压缩没有摘要行 → 接口返回空数组，UI 不显示「缺失」提示。

## Rejected Alternatives

见 ADR-0028「Considered Options」：gateway 读接口、直读 session 文件、扩 `compaction_events`、`messages` 加 kind、精确 `firstKeptEntryId` 锚点、存量回填、凭证正则过滤、塞进消息分页响应。

## Out of Scope

- 上线前会话的摘要回填脚本；
- 摘要全文搜索、导出、分享、编辑；
- 用户级「显示/隐藏摘要」开关；
- admin 侧任何摘要视图（FR-7 约束不变）；
- 超过 50 条的摘要分页；
- 手动触发压缩的入口（pi 提供 `compact()`，本片不暴露）。

## Acceptance

1. 真实对话触发一次压缩后，消息流出现摘要卡，位置在该压缩对应的历史位置之后；折叠 6 行、可展开、markdown 正常渲染。
2. 刷新页面后卡片仍在且回到折叠态；点击「已压缩」标记能滚动到最新卡片。
3. 卡片显示触发原因、token 前后值与时间。
4. 跨用户请求返回 404；归档会话说 404；restore 后可读。
5. purge 会话后摘要行随会话删除（DB 校验）。
6. admin 接口响应中不含摘要正文（现有 admin 契约不变，回归测试通过）。
7. 老 gateway 不发 `summary` 时：不写摘要表、不报错、`compaction_events` 计数照旧。
8. 压缩被 abort 时：不产生 `compaction` 事件、不写两张表、前端不出现「已压缩」与卡片。
9. 超过 50 条压缩记录时接口返回最近 50 条且 `total` 正确。
10. 摘要超过 200,000 字符时落库截断并置 `truncated=1`。
11. `contract/v1/validate.mjs` 通过；gateway/server 测试全绿；web `typecheck` + `build` 通过。

## Residual Risks

- 卡片锚点是投影时刻，overflow 在轮内压缩时可能比真实切点略早。
- 上线前会话无摘要，产品上接受；如需要，可用一次性脚本按本 schema 补做。
- `compaction` 事件语义收紧后，`compaction_events` 的历史计数口径不可与旧数据直接比较（旧数据含失败的假事件）。
- 摘要正文进入 MySQL 后，备份与导出的数据面扩大，需要与消息投影同等对待。
