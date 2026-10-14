# pi 依赖家族整族精确锁版本

Date: 2026-02-06

## Status

Accepted

## Context

gateway 直接依赖 `@earendil-works/pi-coding-agent`（SDK 集成点）与 `@earendil-works/pi-ai`（工具 schema 的 `Type`）。pi-coding-agent 自身以 `^1.1.0` 范围依赖同家族的兄弟包（pi-agent-core、pi-codemode、pi-mcp、pi-tui、chord 等）。骨架阶段只精确锁了主包，`^` 范围解析到 1.2.0 的兄弟包，与 1.1.0 主包出现 inter-package 导出断裂（`MCP_TYPESCRIPT_PREAMBLE` 缺失），运行时直接 SyntaxError。

## Decision

`gateway/package.json` 将整个 `@earendil-works/*` 家族（含 chord、pi-telemetry 等 ui/infra 包）统一精确锁定为同一版本，升级时整族同步升。

## Considered Options(被否的备选)

- **只精确锁直接依赖，兄弟包交给 npm 解析** — 即事故方案：兄弟包 `^` 拉新即断，且断在运行时而非安装期。
- **npm overrides 强制子依赖版本** — 能修但语义隐蔽，overrides 与真实依赖图漂移后更难排查。

## Consequences

- 升级 pi 变成「整族 bump + 本地 install + 冒烟」的显式动作，不能只改一个版本号。
- 未来若 pi 家族拆分发版节奏（部分包独立 minor），需要重新评估整族锁是否过紧。
- `pre-push-checks` skill 已把「依赖变更必须本地真实 install/compile」列为规则。
