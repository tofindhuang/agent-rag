# gVisor 评估：兼容性、代价与迁移清单

日期：2026-10-30
对应决策：`docs/adrs/0031-gvisor-evaluation.md`
关联：requirements R2 / O4、`docs/adrs/0003-run-code-not-aio-sandbox.md`、`docs/adrs/0026-sandbox-warm-pool.md`

> **结论未实测。** 本机 Docker 只注册了 `runc`/`containerd`，未安装 `runsc`；下面的分析基于官方文档、OCI 运行时规范与当前代码里的隔离参数，不含性能测量。

## 1. 现状：沙箱的隔离面

`sandbox/Dockerfile.python` + `DockerSandboxContainerFactory.hostConfig()`：

| 维度 | 当前配置 | 说明 |
|---|---|---|
| 网络 | `network=none` | 无网卡，DNS/DNS-over-HTTPS 均不可用 |
| 文件系统 | `readonly_rootfs=true` + `/work` tmpfs | 只有工作目录可写，且不落盘 |
| 用户 | 非 root（镜像 `USER`） | 与 `no-new-privileges` 组合，不能提权 |
| 能力 | `cap-drop ALL` | 无 `CAP_NET_RAW`/`CAP_SYS_ADMIN` 等 |
| 资源 | 内存+swap 上限、NanoCPUs、pids 上限 | 防止 fork 炸弹与内存饥饿 |
| 进程 | `init=true` | PID 1 回收僵尸进程 |
| 生命周期 | 一执行一容器，结束即销毁；预热池仅预启动 idle 容器 | 见 ADR-0026 |

runc 下这些是**命名空间 + cgroup + seccomp 默认策略**的组合；内核 syscall 面仍然是宿主内核。

## 2. gVisor 改变什么

gVisor（`runsc`）在应用与宿主内核之间插一层用 Go 写的用户态内核（Sentry），应用 syscall 由 Sentry 处理，宿主侧只暴露很小的 syscall 集合。两种模式：

- **ptrace 平台**：Sentry 通过 `ptrace` 拦截，兼容性好、开销大；
- **KVM 平台**：Sentry 跑在轻量 VM 里，隔离更强、需要 `/dev/kvm`。

对本项目意味着：沙箱逃逸风险从「宿主内核 CVE」降到「Sentry 实现缺陷」，但代价是 syscall 兼容性与性能。

## 3. 兼容性逐项分析

| 现有配置 | runsc 行为 | 风险 |
|---|---|---|
| `network=none` | 支持；网络命名空间仍可创建 | 低 |
| `readonly_rootfs` | 支持；overlay 默认走内存 | 低，但镜像启动会慢 |
| `/work` tmpfs | 支持（tmpfs 转发到宿主或内存） | 低 |
| 非 root + `no-new-privileges` | 支持 | 低 |
| 内存/CPU/pids 上限 | 支持（cgroup 由 runsc 管理） | 中：Sentry 自身有额外内存开销，需重估 |
| `init=true` | 支持 | 低 |
| Python 科学计算 | 多数 syscall 已实现；`io_uring`、部分 `ptrace`、`perf_event_open` 可能不支持 | 中：numpy/多进程库可能报 `ENOSYS` |
| 预热池 | 池容器与执行容器必须同一 runtime | 中：切换时池要整体重建，冷启动指标会变 |

已知不兼容面（来自 gVisor 官方 compatibility 文档的常见项）：`io_uring`、`userfaultfd`（部分）、`perf_event_open`、嵌套虚拟化、部分 `bpf`。这些在「跑一段 Python 数据处理」的常见场景里很少出现，但**不能假设为零**。

## 4. 性能与容量

- ptrace 平台：syscall 密集负载可能慢 2–10 倍（官方与社区基准的量级，非本项目实测）；
- KVM 平台：开销小得多，但需要 `/dev/kvm`，云主机与容器内 CI 常常没有；
- 预热池：池容器也要跑在 runsc 下，容器启动更慢 → 池目标数与内存水位都要重估。

结论：**在拿到 runsc 节点前不做容量承诺**；启用时必须重跑 `docs/research/2026-10-25-sandbox-pool-loadtest-report.md` 的同一套压测做对照。

## 5. 与现有威胁模型的关系

`docs/requirements.md` R2 已写明「容器不是绝对边界」。当前缓解是禁网 + 只读根 + 非 root + cap-drop + 资源上限，主要防的是「用户代码联网外泄、写宿主、提权、拖垮宿主」。

gVisor 主要增益在「宿主内核 0day」这一类威胁；这是**平台级**风险，而不是单个租户能触发的路径。对当前的部署形态（单机多实例、可信用户为主）来说，收益低于切换带来的兼容性与性能成本。

## 6. 如果将来启用（迁移清单）

1. 节点安装 `runsc` 并注册到 Docker（`daemon.json` 的 `runtimes.runsc`），重启 dockerd；
2. `DockerSandboxContainerFactory.hostConfig()` 增加 `withRuntime("runsc")`，配置项 `SANDBOX_RUNTIME`（默认空 = `runc`）；
3. **预热池与执行容器共用同一 runtime**，切换时先缩容到 0 再重建池；
4. 重估 `SANDBOX_MEMORY`、池目标数与全局并发；
5. 复跑沙箱池压测与 `run_code` 端到端用例（含 numpy/pandas 常见调用）；
6. 建立「已知不兼容调用」清单；命中时返回明确错误码而不是超时。

## 7. 结论

**M2 暂不启用 gVisor。** 现有隔离组合覆盖了当前威胁模型的主要路径；切换的兼容性、性能与运维成本（尤其预热池）高于现阶段的收益。等出现「强多租户 / 完全不可信代码」需求，或节点具备 KVM 与大内存时，按第 6 节清单落地。

## 8. 未覆盖

- 没有任何实测数据（本机无 runsc）；
- 未评估 Kata Containers / Firecracker 等替代方案；
- 未评估 Windows/macOS 宿主行为。
