# 检索拒答与无答案评测验收记录

日期：2026-10-30
对应 spec：`docs/specs/2026-10-29-kb-refusal-and-eval-scale.md`
关联：ADR-0029、ADR-0022、ADR-0005
工单：`docs/tickets/kb-refusal-eval/01–05`

## 范围

- per-user 两个绝对阈值（rerank 分 / 向量分），默认不启用。
- `kb_search` 拒答时返回 `noAnswer/maxScore/threshold`，不回传被过滤正文。
- 评测支持「无答案」标注，产出 `refusal_accuracy`，运行记录所用阈值与来源。
- 前端四处：设置页阈值、评测面板无答案标注与拒答卡、调试面板判定、聊天工具卡未命中态。

## 自动化验收

| 套件 | 覆盖 | 结果 |
|---|---|---|
| `KbRefusalPolicyTest` | 阈值组合、rerank/向量不串味、等于阈值不拒、空结果不判 | 7 例通过 |
| `KbSettingsIntegrationTest` | 阈值保存/清除、保存 embedding 不丢阈值、非有限值拒绝 | 24 例通过（新增 4 例） |
| `KbSearchIntegrationTest` | 阈值下拒答、阈值上命中、rerank 生效时不套用向量阈值、关闭时响应形状不变 | 14 例通过（新增 5 例） |
| `KbEvalMetricsCalculatorTest` | 无答案样本不进 recall 分母、只有无答案样本时 recall 为 null、拒答准确率 | 5 例通过 |
| `KbEvalLabelIntegrationTest` | 无答案标注创建/列表、带目标的非法组合、有答案缺目标、同问题切换类型 | 12 例通过（新增 4 例） |
| `KbEvalRunIntegrationTest` | 混合标注的拒答准确率、高阈值同时压低 recall 并抬高拒答率、run 记录阈值 | 8 例通过（新增 2 例） |
| gateway `extensions.test.ts` | `kb_search` 命中文案字节级不变、拒答文案与 details | 139 tests 通过（新增 2 例） |
| 完整 server 套件 | 全量回归 | **506 通过 / 0 失败 / 2 skipped** |
| 契约 | `node contract/v1/validate.mjs` | 8 份 fixture 通过 |
| Web | `npm run typecheck`、`npm run build` | 通过 |

## 端到端验收（一次性栈 + 确定性 embedding/rerank）

用仓库 `tools/loadtest` 的一次性栈（独立端口 23306/23379/23333/23673 + `fake-embedding` 侧车），**不触碰本机共享库**。

准备：
- 用户上传 1 份 6.4KB 文本（60 段巡检记录），摄取完成；
- 45 条有答案标注（每条查询取当前排序第一条为目标）+ 5 条无答案标注 = **50 条**，上限 100。

| 运行 | 阈值 | 执行 | recall@5 | 池 recall@10 | 拒答准确率 | 耗时 |
|---|---|---|---|---|---|---|
| 1 | 未启用 | 50（含 5 条无答案） | 1.00 | 1.00 | 0.00 | 0.1s |
| 2 | rerank 0.5 | 50 | 0.00 | 1.00 | 1.00 | 0.1s |

判读：

- 阈值关闭时无答案样本被「答了」，所以拒答准确率 0；有答案样本全命中（fake 语料下 recall 1.0 属预期，不作为质量结论）。
- 阈值 0.5（该 rerank 打分约 0.17–0.22）把所有查询判为拒答：拒答准确率 1.0，同时 recall 掉到 0——这正是阈值代价的可见证据。
- **池 recall@10 保持 1.0**：候选池仍召回了目标，只是最终排序被阈值拦下；这与指标口径一致（池指标衡量 embedding，最终指标衡量阈值+rerank）。
- 50 条一次运行 0.1s（fake 端点），远低于 60s 预算。

`kb_search` 工具路径（同一会话绑默认 KB）：

```json
// 阈值关闭
{"passages": [5 条], "noAnswer": false, "maxScore": null, "threshold": null}
// rerank 阈值 0.5
{"passages": [], "noAnswer": true, "maxScore": 0.1731, "threshold": 0.5}
```

服务端日志：

```
kb_search refused for session 11111111-…: maxScore=0.1731 threshold=0.5 source=rerank
```

调试面板同样显示「阈值 0.500（rerank）· 最高分 0.173 → 会被拒答」。

## 真实 50+ 标注的交接（数据项）

本切片交付能力与流程；真实语料的 50+ 标注由部署方在真实 KB 上完成：

1. 知识库 → 设置：配置真实 embedding / rerank，保存后确认「自检」通过；
2. 文档列表：上传/重建文档，等待全部 `ready`；
3. 检索调试：对每个问题点「标为期望答案」；对确定没有答案的问题在输入框点「标为无答案」；
4. 反复运行评测，观察 `拒答准确率` 与 `recall@5` 的取舍，调整阈值；
5. 至少跑两次确认指标可复算。

> 不使用假 embedding 的指标冒充检索质量结论：本次端到端只证明链路、指标口径与耗时。

## 残余风险

- 换 embedding/rerank 模型后阈值需重新校准；报告里 `threshold_used/threshold_source` 可供对照。
- 阈值过紧会压低 recall（本次 0.5 即全拒），过松则拒答不生效；默认不启用避免升级突变。
- FR-7「检索命中率」会因拒答下降，属预期；看板口径未改。
- 无答案标注的「确实没有」是人工判断，可能与语料范围不一致。
- 池 recall 与最终 recall 的差异在阈值开启后会明显放大，解读报告时需要区分。
