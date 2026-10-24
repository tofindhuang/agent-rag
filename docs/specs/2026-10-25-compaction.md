# FR-4 Context Compaction 配置与上下文占比

## Problem Statement

pi 已内置 compaction，但 gateway 目前没有配置它：所有会话使用 pi 默认的 `reserveTokens=16384`、`keepRecentTokens=20000`，触发点不随模型窗口比例变化，也没有面向用户的任何可见性。结果是：

- 小窗口模型可能在很晚才压缩，甚至先撞上 overflow；大窗口模型可能压缩过早或过晚，无法按部署策略统一。
- 用户不知道当前上下文占了多少、什么时候会触发压缩，压缩发生时只看到一句「上下文已压缩」。
- M1 要求「策略可配」和「压缩不中断、可回捞原文」，当前只有 pi 默认行为，没有项目级配置与验收证据。

## Solution

- gateway 启动时读取全局 compaction 配置；每个会话按自己的模型 `contextWindow` 计算 `reserveTokens`，通过该会话独立的 `SettingsManager.applyOverrides` 注入 pi。
- 触发条件沿用 pi：`contextTokens > contextWindow - reserveTokens`；压缩后保留约 `keepRecentTokens` 原文（pi 按 token，不按轮）。
- 新增 `context_usage` 契约事件：在 run 开始、每条 assistant `message_end`、compaction 结束时携带当前 `tokens / contextWindow / percent`、压缩阈值与开关；web 在会话头部显示上下文占比与压缩阈值。
- 原文继续保留在 pi session 文件与 MySQL 消息投影；compaction summary 的内部 usage 不计入 FR-6 TPM。
- 摘要正文查看仍属 P1，不在本次范围。

## User Stories

1. As a 用户, I want 长对话在接近模型窗口时自动压缩, so that 对话不会被上下文溢出打断。
2. As a 用户, I want 压缩后仍能引用早先轮次说过的关键事实, so that 长对话不会「失忆」。
3. As a 用户, I want 在会话头部看到当前上下文占比, so that 我知道离压缩还有多远。
4. As a 用户, I want 看到压缩阈值（例如 80%）, so that 占比接近阈值时我有预期。
5. As a 用户, I want 压缩发生时只看到轻量提示、对话继续, so that 不中断当前任务。
6. As a 用户, I want 压缩后占比显示「待更新」而不是错误的旧值, so that 我不会被过期数字误导。
7. As a 用户, I want 压缩关闭时明确看到「压缩已关闭」, so that 我知道长对话可能撞窗口。
8. As a 用户, I want 悬停占比能看到具体 token 数, so that 需要排障时有细节。
9. As a 部署者, I want 用环境变量统一调整触发比例与保留量, so that 不同模型/部署可以按容量调参。
10. As a 部署者, I want 按每个模型的 contextWindow 自动换算, so that 不需要为每个模型手填绝对 token。
11. As a 部署者, I want 一键关闭自动压缩, so that 出问题时有逃生开关。
12. As a 运维者, I want compaction 继续产生契约事件, so that 现有监控/提示不受影响。
13. As a 开发者, I want 配置换算为纯函数并单测, so that 比例/夹紧/回退行为可验证。
14. As a 开发者, I want 每个会话使用独立 SettingsManager, so that 全局配置不会污染本机 pi 设置或其他会话。
15. As a 开发者, I want 模型切换重建会话时重新计算阈值, so that 8k 与 128k 模型都按各自窗口触发。
16. As a 前端开发者, I want 一个新的 `context_usage` 事件而不是猜 token, so that 占比与 pi 的真实估算一致。
17. As a 前端开发者, I want 刷新页面后不显示过期占比, so that 下一次 run 开始前不会误导用户。
18. As a 审计者, I want compaction summary usage 不计入 TPM, so that 限流账本与对话 usage 的边界清晰。
19. As a 审计者, I want 原文仍可从 pi session/MySQL 投影回捞, so that 压缩不等于删除。
20. As a 产品负责人, I want M1 只做全局配置, so that per-user 偏好可以留到后续版本。

## Implementation Decisions

### 配置与换算

全局 gateway 环境变量：

| 配置 | 默认值 | 说明 |
|---|---|---|
| `COMPACTION_ENABLED` | `true` | 关闭后不自动压缩 |
| `COMPACTION_RESERVE_RATIO` | `0.2` | 预留窗口比例，夹紧 `[0.05, 0.5]` |
| `COMPACTION_KEEP_RECENT_TOKENS` | `20000` | 压缩后保留原文的 token 上限 |

每个会话：

```
contextWindow = model.contextWindow ?? 128000
reserveTokens = max(1024, floor(contextWindow * ratio))
keepRecentTokens = min(configuredKeep, max(1, floor(contextWindow / 2)))
thresholdPercent = round((1 - reserveTokens / contextWindow) * 100)
```

- pi 的触发条件是 `contextTokens > contextWindow - reserveTokens`；因此 `reserveTokens` 是「为下一次请求预留的余量」，不是保留的历史。
- `keepRecentTokens` 是 pi 原生能力，按 token 近似「最近 N 轮」；不修改 pi 的 cut point 实现。
- 非法/缺失配置回退默认值；`contextWindow <= 0` 时回退 128k。
- M1 不做 per-user / per-session 覆盖。模型窗口本身已经按会话/模型自动换算。

### 应用方式

- 每个会话创建自己的 `SettingsManager`（加载 `~/.pi/agent` 原有设置），同时传给 `DefaultResourceLoader` 与 `createAgentSession`。
- 模型确定后（用户配置模型或系统默认模型）计算 compaction 设置，调用 `settingsManager.applyOverrides({ compaction: {...} })`；不写回磁盘。
- 用户切换模型会重建会话，重新计算并应用。
- `COMPACTION_ENABLED=false` 时 `compaction.enabled=false`，不自动压缩；手动压缩入口不提供。

### 上下文占比事件

新增契约事件（纯增量）：

```
event: context_usage
data: { tokens, contextWindow, percent, compactionThresholdPercent, compactionEnabled, source }
```

- `tokens`/`percent` 可为 `null`：压缩后、下一条 assistant 回复前没有可靠的 provider usage。
- `source`：`provider` 表示最近一条 assistant `usage.totalTokens`（缺失回退 input+output+cacheRead+cacheWrite）；`estimated` 表示 pi 的上下文估算（会话刚恢复、尚未收到 assistant usage 时使用）。
- 发送时机：`run_started` 之后一次；每条 assistant `message_end` 之后；`compaction_end` 之后发一次 `null`（等待下一轮重算）。
- 不逐 delta 发送；不进入模型上下文；不落 MySQL。
- Java 只透传该帧，不做投影；web 用它更新头部占比。
- 刷新页面后不主动查询 gateway，下一次 `run_started` 会补齐。

### UI

- 输入框/发送按钮下方显示小胶囊 + 细进度条：`上下文 42% · 压缩阈值 80%`；会话头部只保留模型/知识库。
- 悬停显示 `tokens / contextWindow` 与数据来源（provider 统计 / pi 估算）；占比达到阈值时变色，但不改变对话行为。
- `tokens/percent` 为 null 时显示「上下文统计中」；`compactionEnabled=false` 时显示「压缩已关闭」。
- `compaction` 事件仍显示「上下文已压缩」；若压缩发生在 `run_settled` 之后、没有事件，UI 在 `context_usage` 明显下降（≥15 个百分点）时推断并显示持久的「已压缩」标记；模型/会话切换时重置。摘要正文查看是 P1，不做。
- 刷新后占位隐藏，直到下一次 run。

### 原文与配额

- 原文继续保留在 pi session 文件与 MySQL 消息投影；压缩不删除任何投影行。
- compaction summary 的内部 usage 不计入 FR-6 TPM；TPM 只统计 assistant 对话 usage。后续若需精确计费，再给 `compaction` 事件增加可选 `usage` 字段。

### 契约

- `context_usage` 是新事件，属纯增量，不升主版本；契约 README §5/§6 补充。
- `compaction` 事件形状不变。
- `run_started`/`message_end`/`run_settled` 形状不变。

## Testing Decisions

- 纯函数单测：`contextWindow` 回退、ratio 夹紧、`reserveTokens`/`keepRecentTokens` 计算、disabled、阈值百分比。
- Translator 单测：assistant `message_end` 的 provider usage 覆盖 `context_usage`，`source=provider`；无 provider usage 时回退 pi 估算；compaction 后清空为 null。
- SettingsManager 覆盖测试：会话创建后 `getCompactionSettings(model)` 返回注入值；不影响其他会话/磁盘设置。
- Translator 单测：有 `contextUsage` provider 时在 run 开始/`message_end`/`compaction_end` 发出 `context_usage`；provider 缺失时既有 fixture 不变。
- 契约测试：现有 `03-compaction.json` 继续覆盖 AC2（压缩后继续文本、正常终态）。
- Web：typecheck + build；占比组件的显示/降级逻辑通过类型检查与手动演示验证（项目暂无 web 单测框架）。
- AC1：低窗口手工验收，说明关键事实 → 聊到 3 倍窗口触发 compaction → 追问事实；记录 compaction 事件、上下文占比变化与回复。实际使用 32k/64k 窗口（2k 会被系统 prompt + 工具定义占满，见 ticket 03）。该项不进 CI（真实模型成本/波动）。
- 既有测试基类不受影响；无 DB schema/迁移。

## Out of Scope

- per-user / per-session compaction 偏好（M1 后评估）。
- 摘要正文查看页/API（P1）。
- 自定义摘要 prompt/instructions。
- 手动「立即压缩」入口。
- 把上下文占比持久化或刷新后主动查询 gateway。
- compaction summary usage 计入 TPM/计费。
- 修改 pi 的 turn 级 cut point/token 估算算法。
- 用 faux model 做 AC1 自动化。

## Further Notes

- `reserveTokens` 增加会让压缩更早；`keepRecentTokens` 增加会让摘要后保留更多原文但压缩收益变小。
- 小窗口模型下 `max(1024, ...)` 可能让触发点低于 80%；这是保底余量的已知取舍。
- 压缩后占比为 null 是 pi 的正常行为，不是数据丢失。
- M1 的全局配置 + 按模型窗口换算已覆盖不同模型；per-user 偏好留到 M2，届时需要用户设置表、Java→gateway 字段与 UI。
- pi 的自动压缩可能发生在 `run_settled` 之后；此时不会为已结束的 run 补发 `compaction` 帧，下一次 run 的 `context_usage` 会显示压缩后的占比。压缩本身不依赖该提示帧。
