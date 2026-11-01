# gVisor 评估：暂不启用，保留迁移清单

Date: 2026-10-30

## Status

Accepted

## Context

`run_code` 目前的隔离是 Docker `HostConfig` 组合：`network=none`、只读 rootfs、`/work` tmpfs、非 root（`USER`）、内存与交换上限、pids 限制、`cap-drop ALL`、`no-new-privileges`、`init=true`。requirements 的 R2 明确写了「容器不是绝对边界」，把 gVisor 评估列进 M2。

本次评估要回答：是否现在就把沙箱 runtime 换成 `runsc`，以及如果要换，代价是什么。

**限制**：评估环境（本机 Docker）只装了 `runc`，`runsc` 未安装，因此**没有做实测**；下面的结论来自官方行为文档与对现有隔离参数的逐项分析，不是跑出来的数据。

## Decision

**M2 不启用 gVisor**，把评估结论与迁移清单落档；等到有更强多租户或不可信代码需求、且节点具备 KVM/runsc 条件时再落地。

理由：

1. 当前威胁模型下，`network=none` + 只读 rootfs + 非 root + `cap-drop ALL` + `no-new-privileges` + seccomp 默认策略已经覆盖「用户代码不能联网、不能写宿主、不能提权」这三条主诉求；
2. 沙箱执行是**短生命周期、单次容器**，且预热池与执行容器必须使用同一 runtime——切换会影响预热池命中率与冷启动指标，需要重新压测；
3. gVisor 的 syscall 兼容性（`ptrace`、部分 `io_uring`、嵌套命名空间）会在科学计算与多进程代码上产生行为差异，需要一套「已知不兼容」清单与降级策略；
4. 没有 runsc 环境时落一段未验证的 `withRuntime("runsc")` 开关，风险大于收益。

## 迁移清单（将来启用时按此执行）

1. **代码**：`DockerSandboxContainerFactory.hostConfig()` 增加 `withRuntime("runsc")`（配置项 `SANDBOX_RUNTIME`，默认空 = `runc`），并让**预热池与执行容器共用同一值**；
2. **节点**：安装 `runsc` 并注册到 Docker（`/etc/docker/daemon.json` 的 `runtimes`），重启 dockerd；
3. **镜像**：`sandbox/Dockerfile.python` 的入口脚本需在 runsc 下复测（Gofer/overlay 对只读 rootfs 与 tmpfs 的支持）；
4. **配额**：gVisor 有独立的内存开销（sentry），需要重估 `SANDBOX_MEMORY` 与池目标数；
5. **指标**：冷启动与执行耗时会有可测量的上升，需要重跑 `docs/research/2026-10-25-sandbox-pool-loadtest-report.md` 的同一套压测做对照；
6. **兼容性**：建立「已知不兼容库/调用」清单，工具结果在 runsc 下失败时给出明确错误码而不是笼统超时。

## Considered Options（被否的备选）

- **直接切 runsc 并保留 runc 作为回退** — 没有验证环境，等于拿生产做实验 → 否决。
- **加配置开关但不验证** — 默认关闭时风险低，但会让人误以为「已支持」；加固路径里出现未跑过的分支不可接受 → 否决。
- **只写「评估后不做」不写迁移清单** — 等于没评估，M2 该项无法验收 → 否决。

## Consequences

- R2 维持「容器不是绝对边界」的表述，gVisor 仍是 P2/后续项；
- 一旦有 runsc 节点，按上面 6 步即可落地，不需要重新做方案设计；
- 本次结论**未实测**，报告中必须如实标注，不得写成性能数据。
