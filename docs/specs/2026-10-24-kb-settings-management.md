# 知识库设置管理页：服务卡 + 模型选择

日期：2026-10-24
状态：已实现（ticket 14）
关联需求：FR-2（知识库 / RAG）
上游 spec：[2026-10-23-knowledge-base-rag.md](2026-10-23-knowledge-base-rag.md)

## Problem Statement

当前 `/knowledge` 的设置区是一张原始表单：Base URL、API Key、模型、向量维度、路径全部平铺，用户需要自己知道模型名和维度。它与「模型供应商」页的「添加服务 → 探测 → 选择模型」交互不一致，普通用户容易填错，维度也不该由用户手填。

目标：embedding/rerank 各是一张服务卡；添加服务后从服务返回的模型列表中选择（列表不可用时手填），维度由探测自动得到，路径收进高级选项。

## Decisions（grilling 结论）

1. **单活配置**：每用户一套生效 embedding + 一套生效 rerank；不做多服务保存/切换。沿用 `kb_user_settings` 一行一用户。
2. **服务与模型分离**：先填 Base URL/API Key 并「获取模型」，再从下拉选择；选中模型后探测并自动填充维度。
3. **列表降级**：`/models` 不可用/为空时，允许手动输入模型 ID，仍通过探测校验可用性与维度。
4. **部署默认**：
   - `source=system` 只读展示 + 「覆盖为我的配置」；
   - `source=user` 提供「恢复部署默认」，按区块清除用户覆盖（embedding/rerank 互不影响）；
   - embedding 不允许关闭；rerank 有独立开关，关闭保留已存配置，重新开启恢复；
   - 恢复入口的确认弹窗写明「部署未配置默认时会变为未配置」。
5. **高级字段**：`path` 放入「高级」折叠，默认 `/embeddings` 或 `/rerank`；维度只读。
6. **保存语义**：embedding 保存不再清空 rerank；rerank 有独立保存/开关/清除接口。

## API（Java ↔ web，加法/语义调整）

### 模型列表

`POST /api/kb/settings/models`

```json
{ "type": "embedding", "baseUrl": "https://api.example.com/v1", "apiKey": "sk-..." }
```

- `type`：`embedding` | `rerank`；`apiKey` 省略/null 时使用已存密文，`""` 表示无密钥。
- Base URL 走现有 SSRF 守卫；请求 `{baseUrl}/models`（OpenAI 兼容，Bearer），10s 超时。
- 200：`{ "models": ["BAAI/bge-m3"], "message": null }`。
- 上游失败/不可解析/无模型：200 + `models: []` + 可读 `message`（前端切手动输入），本地校验失败仍 400。

### 探测（维度发现）

`POST /api/kb/settings/probe` 的 embedding 段允许 **dimension 省略/null**：

- 省略：只返回端点实际维度（`ok` 依据请求成功，不做维度断言）；
- 提供：保持现有断言行为（不一致 → `ok:false`）。
- PUT 保存仍要求正维度（前端用探测结果填入）。

### Rerank 独立更新

`PUT /api/kb/settings/rerank`

```json
{ "enabled": false }
```

或完整配置：

```json
{ "enabled": true, "baseUrl": "...", "apiKey": "...", "model": "...", "path": "/rerank" }
```

- 只传 `enabled`：切换开关，保留已存 rerank 字段；开启时若无用户配置且部署未配置 → `KB_NOT_CONFIGURED`。
- 同时传 `baseUrl/model`：先探测，再替换 rerank 配置；embedding 字段不变。
- 返回最新 `KbSettingsView`。

### 清除覆盖

- `DELETE /api/kb/settings/embedding`：清空用户 embedding 字段（Base URL/Key/Model/Dim/Path），rerank 不动；返回 view。
- `DELETE /api/kb/settings/rerank`：清空用户 rerank 字段并把 `rerank_enabled=true`（回落到部署；部署没有则 view 为 `none`）；embedding 不动；返回 view。

### PUT /api/kb/settings 语义调整

- `embedding` 仍必填；
- `rerank` **省略/null 时保留现有 rerank 设置**（不再默认关闭）；显式传 `{enabled:false}` 仍按旧语义关闭并清空。
- 兼容现有调用；新增测试覆盖“保存 embedding 不清 rerank”。

## 数据语义

- `kb_user_settings` 一行同时承载两类配置；允许 embedding 字段为 NULL（表示回落部署），`KbEmbeddingSettings` 已按 Base URL 是否为空回落。
- `rerank_enabled=false` 保留字段，`KbRerankSettings` 返回空（关闭）；重新开启后若用户字段存在则恢复用户配置，否则回落部署。
- 清除/开关只更新对应列，不整行覆盖，避免误清另一类配置。
- 无 schema 迁移。

## UI

### Embedding 卡

- `source=none`：未配置 + 「添加 Embedding 服务」。
- `source=system`：部署默认徽标、Base URL、模型、维度 + 「覆盖为我的配置」。
- `source=user`：用户配置徽标、Base URL、密钥末四位、模型、只读维度 + 「更换模型」「编辑服务」「恢复部署默认」。
- 添加/编辑弹窗：Base URL、API Key（留空保留已存）、「获取模型」、模型下拉（或手动输入）、探测结果（维度/延迟）、高级 path、保存。
- 模型下拉为空/获取失败：切手动输入；仍可探测。

### Rerank 卡

- 开关（`PUT /settings/rerank {enabled}`），关闭后显示「已关闭」并保留配置。
- `source=system` 只读 + 覆盖；`source=user` 可编辑/恢复；`source=none` 可添加。
- 弹窗与 embedding 共用模型获取/探测交互；保存走 rerank 专用接口。

### 交互规则

- 选中模型后自动探测；探测成功前保存禁用；维度只展示不可编辑。
- 换模型/改服务后提示「现有文档会标记为需重建」。
- 恢复默认用确认弹窗；成功后卡片按新 source 重绘。

## 验收

- 添加 embedding 服务 → 获取模型 → 选择 → 自动维度 → 保存；文档列表可继续上传/检索。
- `/models` 不可用时手动输入模型并探测成功保存。
- rerank 开关关闭/开启不改变 embedding；关闭后检索保持向量序（`reranked=false`）。
- 保存 embedding 不清空已启用的 rerank；反之亦然。
- 恢复 embedding/rerank 只影响对应区块；部署未配置时 view 显示 `none`。
- 现有 server 设置/检索/摄取测试无回归；web typecheck/build/tokens；浏览器 390/768/1280 两主题验证添加/切换/恢复流程。

## Out of Scope

- 多 embedding/rerank 服务保存与切换。
- 自动识别服务类型、过滤非 embedding 模型、模型能力元数据。
- 拉取模型列表后持久化缓存；每次打开弹窗重新获取。
- 用户级 OCR 配置。
