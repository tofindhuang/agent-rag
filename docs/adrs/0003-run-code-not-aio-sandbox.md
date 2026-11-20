# run_code 沙箱不采用 AIO Sandbox

Date: 2026-10-21

## Status

Accepted

## Context

评估对象：[agent-infra/sandbox](https://github.com/agent-infra/sandbox)（AIO Sandbox，字节 agent-infra，Apache-2.0）——单容器全合一 agent 工作环境：Chromium/VNC 浏览器、code-server、Jupyter、terminal、file API、MCP hub，经 HTTP API（`:8080/v1/*`）驱动。

FR-3 的隔离模型是「单次执行」粒度：一次执行一个临时容器、结束即销毁、禁网络（AC：`requests.get` 必须失败）、per 执行资源限制、最小攻击面。AIO Sandbox 的实际形态与此相反：

- 长驻工作区：`sandbox_data` volume 持久化，文件跨执行保留
- 禁网与可用性结构冲突：其 API 走容器网络口，浏览器功能依赖外网；`--network none` 后 API 不可达
- 官方 quick start 要求 `--security-opt seccomp=unconfined`（Chromium 需要），容器内常驻多个服务，攻击面远大于需求允许
- 镜像 GB 级（含 Chrome、VSCode），多租户只能每用户一实例，成本模型不成立

## Decision

`run_code` 维持自建轻量 executor（`sandbox/Dockerfile.python` 路线：python-slim + `--network none` + CPU/内存/pids 上限 + 非 root + 只读 rootfs + tmpfs 工作目录）。AIO Sandbox 不引入作为 run_code 底座。

## Considered Options(被否的备选)

- **AIO Sandbox 作为 run_code 底座** — 粒度相反、禁网结构性冲突、seccomp=unconfined 安全姿态弱于需求 → 否决。
- **按次起停 AIO 容器模拟临时执行** — GB 级镜像秒级起停不可得，且仍需手动收紧安全参数，得不偿失 → 否决。
- **Judge0 等托管代码执行服务** — 未在本轮评估（需求 §5 曾列为备选）；若 M1 自建成本超预期再评估 → 挂起。

## Consequences

- run_code 的隔离参数与 FR-3 AC 一一对应，审计口径简单。
- 浏览器渲染（FR-2 网页摄取 JS 页）与 agent 工作区能力不在 run_code 沙箱内解决：已登记开放问题 O4，M2 后评估 AIO Sandbox 作为独立 workspace 层（与 run_code 沙箱并存，不同粒度、不同安全边界）。
- 若 O4 落地，AIO 层需要单独立网络策略（它需要外网）与实例生命周期管理（每会话一实例、空闲回收）。
