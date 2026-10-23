# 设计 token 双层结构与深浅双主题

Date: 2026-10-23

## Status

Accepted

## Context

切片 1 的界面把颜色、字号、间距直接写进三个 CSS module（113 处十六进制色值），`globals.css` 只声明 `color-scheme: dark`。要支持深浅双主题与后续界面扩展，必须先把「颜色决策」从组件里抽出来，否则每加一个组件就会再固化一套值。

同时有几个真实取舍：

- 直接 vendor Radix Colors / Tailwind 色板可以省去设计时间，但会把颜色决策外包给依赖版本；仓库规范也要求「不引入 UI 框架依赖除非单独评审」。
- 主题偏好可以存服务端 `users.settings`（跨设备一致），也可以只存 localStorage（设备偏好）。跨设备同步需要新接口与合并语义，M1 没有该需求。
- 主题可以只做深色，但产品有长时阅读场景，且后补浅色需要重扫所有组件。

## Decision

- **双层 token**：primitive（原始色板/刻度）→ semantic（`--surface-*`、`--text-*`、`--border`、`--accent*`、`--danger/success/warning`、`--bubble-*`、`--radius-*`、`--space-*`、`--text-*`）。组件只允许使用 semantic token，`*.module.css` 中禁止出现十六进制颜色（globals.css 的 token 定义除外）。
- **双主题一次做完**：深色为 `:root` 基线，浅色由 `[data-theme="light"]` 覆盖；未显式选择时由 `prefers-color-scheme: light` 媒体查询把 `:root:not([data-theme])` 切到浅色。`color-scheme` 随主题切换。
- **主题偏好存 localStorage**，三态 `system | light | dark`；`layout.tsx` 内联脚本在首帧前写入 `<html data-theme>`，避免闪烁。服务端同步留到出现多端一致性需求时再做。
- **自建色板**：不引入第三方色板/设计系统；以 WCAG AA 为硬门槛（正文与次要文字 ≥4.5:1，禁用态 ≥3:1），并提供 `check-contrast` 脚本对 token 组合做断言；`check-tokens` 脚本防止组件重新写死颜色。
- **主色不直接用作按钮底色**：`--accent` 用于文字/链接/焦点，`--accent-fill` 用于按钮背景，保证白字对比度 ≥4.5:1（亮蓝填充配白字达不到 AA）。

## Considered Options（被否的备选）

- **vendor Radix Colors 12 级色阶** — 对比度有保障，但接受 12 级命名体系与第三方来源，且与「最小 token 面」目标冲突。
- **只做深色、token 预留双主题** — 省约 30% 工作量，但浅色上线时间不确定，期间新组件仍可能写死颜色。
- **主题偏好存服务端** — 跨设备一致，但需要新 PATCH 接口、「未设置」合并语义与离线冲突处理，M1 收益不足。
- **Tailwind/UI 框架** — 与仓库「CSS Modules + 不引 UI 框架」约定冲突，收益主要是开发速度而非产品质量。

## Consequences

- 每次改色只改 token；脚本会在对比度或硬编码颜色处失败，避免回归。
- 双主题下所有新组件必须同时考虑两套值，组件评审多一项「两主题都看过」。
- 主题偏好在设备间不同步；换设备需要重选（可接受）。
- 内联主题脚本是唯一允许在 `layout.tsx` 出现的首帧副作用，需要注释说明。
