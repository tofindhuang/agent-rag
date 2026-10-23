# 视觉重做（切片 2）：设计 token、双主题与界面收敛

日期：2026-10-23
状态：Phase 1 grilling 已完成（Q1–Q12），待实现
关联需求：FR-1（会话 UI）、FR-6（认证界面）
关联 ADR：[0012](../adrs/0012-design-tokens-dual-theme.md)
上游 spec：[2026-10-23-model-providers-account-archive.md](./2026-10-23-model-providers-account-archive.md)（其中「视觉重做、浅深主题」列为切片 2）

## Problem Statement

切片 1 的功能已完整，但视觉层是「能看」级别：

- `app/globals.css` 只有 `color-scheme: dark` 与 body 两个颜色，没有 token；
- 3 个 CSS module 共 113 处硬编码色值，字号 13/14/15/18/20 混用，间距是随手值；
- 深色写死，没有浅色主题；
- 弹窗在 `NewSessionDialog` / `SwitchModelDialog` 各复制一套样式，键盘焦点管理缺失；
- 登录/注册、聊天、设置三套界面各自维护按钮与输入样式。

## Decisions（grilling 结论）

1. **设计方向**：克制的工具型，Linear 式紧凑优先；不做品牌化插画/渐变/强动效。
2. **主题范围**：深浅双主题，默认跟随系统，一次做完。
3. **开关与持久化**：设置页新增「外观」区，三态（跟随系统/浅色/深色），localStorage 持久化；首帧前由内联脚本写入 `<html data-theme>` 防闪烁；服务端同步留待后续。
4. **色板来源**：自建小色板（不 vendor Radix/Tailwind），双层 token（primitive → semantic），组件只用 semantic；WCAG AA 为硬门槛，用脚本校验。
5. **排版与密度**：14px 基准、4pt 网格、紧凑；字号 12/13/14/16/20，间距 4/8/12/16/24/32；圆角 6/10/14；不引字体文件。布局结构不变，对话列 720→760，侧栏 240→248。
6. **组件范围**：新增最小 `components/ui/` 五原语——Button、TextField、Select、Dialog、Banner；重做登录、注册、聊天、设置四个界面；不引 UI 框架/图标库。
7. **可访问性**：焦点环统一（2px accent + 2px offset）；Dialog 内做 Esc、初始焦点、焦点陷阱、关闭归还焦点；`prefers-reduced-motion` 全量降级；地标语义与 `role=status/alert` 分工。
8. **验收**：`check-contrast` 与 `check-tokens` 脚本作为硬门禁；人工矩阵 4 界面 × 2 主题 × 3 宽度（390/768/1280）；不提交截图、不引入视觉回归依赖。
9. **范围冻结**：视觉-only——信息架构、功能入口、请求流程、错误语义、快捷键不变；例外仅为设置页「外观」区、Dialog 焦点行为、明显文案不一致修正。
10. **移动端**：保持侧栏置顶折叠，不引入抽屉；聊天区全宽、输入框固定并加 safe-area；弹窗居中、宽度 `calc(100vw - 32px)`、最大高 85vh 内部滚动。
11. **色板基调**：冷灰中性 + 蓝主色；深色面 `#0f1115/#161a23/#1c2230`，浅色面 `#f7f8fa/#ffffff/#eef1f5`；accent 文本色深 `#5b8cff`/浅 `#3b6fe0`，accent 填充色深 `#3b6fe0`/浅 `#2f5fd0`（白字 ≥4.5:1）；用户气泡深 `#243a63`/浅 `#e8efff`。

## Token 草案

- 面：`--surface-0/1/2`、`--surface-hover`
- 文字：`--text-primary/secondary/muted`、`--text-on-accent`
- 线：`--border`、`--border-strong`
- 主色：`--accent`（链接/焦点）、`--accent-fill`（按钮底）、`--accent-hover`、`--accent-soft`（选中底）
- 语义：`--danger`、`--success`、`--warning`
- 聊天：`--bubble-user`、`--bubble-assistant`
- 形状/节奏：`--radius-sm/md/lg`、`--space-1..6`、`--text-xs..lg`、`--duration-fast`、`--ease-standard`

## Out of Scope

- 知识库与记忆管理页面（尚不存在）
- 新功能、交互/信息架构调整、消息操作菜单、搜索、拖拽
- 图标库、UI 框架、字体文件、视觉回归工具
- 服务端同步主题偏好、账号级外观设置

## Acceptance

- `npm run check:contrast`、`npm run check:tokens`、`typecheck`、`build` 全绿
- 深/浅/系统三态切换即时生效、刷新不闪烁、选择持久化
- 人工矩阵逐格通过（无溢出、焦点可见、键盘可完成弹窗操作、对比度观感正常）
- 现有功能行为无回归：登录/注册、会话创建与发送、模型选择与故障切换、账号安全、归档/恢复/彻底删除

## Risks

- 行为回归：样式重构触碰组件结构，需按矩阵逐界面验收
- 对比度漂移：由脚本门禁兜底；禁用态/浮层组合需纳入检查
- 主题闪烁：内联脚本必须在 CSS 生效前运行；SSR 水合不一致需人工确认
