# web — Next.js 前端规范

Next.js 15 App Router + React 19 + TypeScript strict。职责：聊天流式界面、知识库与记忆管理界面；不承载业务逻辑。

## 目录结构

```
web/
├── app/                路由、布局、metadata、全局样式；页面只做组合
├── components/         UI 组件，按功能分子包（chat/…）
├── hooks/              可复用的 client hooks（状态、副作用、SSE 生命周期）
├── lib/
│   ├── api/            所有 HTTP 调用与错误映射
│   ├── sse/            text/event-stream 解析
│   └── env.ts          环境变量唯一出口
├── types/              共享类型（领域类型与契约类型分开）
└── app/globals.css     reset + 设计 token
```

## 渲染边界

- 默认使用 Server Component；只有需要状态、副作用或事件处理的文件才加 `"use client"`。
- `"use client"` 标在最小交互边界（页面组合层或交互组件），不要给整棵 `app/` 树加。
- `app/` 只放路由、布局、metadata 与页面组合；不在页面里写 fetch、SSE 解析或复杂状态。
- 展示组件不直接取数；数据和副作用放 hooks，HTTP 放 `lib/api`。

## 数据与网络

- 禁止在组件里直接 `fetch`；统一走 `lib/api`。
- SSE 帧解析放 `lib/sse`，与 React 状态解耦，便于单独测试。
- 环境变量只从 `lib/env.ts` 读取；只允许 `NEXT_PUBLIC_*`，不硬编码 API 地址，不放密钥。
- 非 2xx 响应统一转成 `ApiError`，解析业务层的 `{error:{code,message,retryable}}` envelope。
- UI 只展示可读的 `message`；堆栈、上游地址等细节进 `console` 或网络日志。

## 状态与契约

- 优先局部状态与 hooks；在出现跨页面共享需求前不引入全局状态库。
- 契约事件类型放 `types/`，当前 v0/v1 的差异只在 hooks 的映射层处理，组件不感知协议细节。
- 边界数据用 `unknown` + 收窄，禁止 `any`；确需断言时注释理由。

## 样式

- `app/globals.css` 只放 reset、`body` 与设计 token。
- 组件样式用 CSS Modules（`*.module.css`），类名 camelCase。
- 不写内联样式，不引入 UI 框架依赖除非单独评审。

## 命名

| 对象 | 规则 | 示例 |
|---|---|---|
| React 组件 | `PascalCase.tsx`，命名导出 | `ChatPage.tsx` |
| Next 页面/布局 | 默认导出 | `app/page.tsx` |
| hook | `useXxx.ts` | `useChat.ts` |
| lib / 类型模块 | `kebab-case.ts` 或单词小写 | `parse-sse.ts`、`chat.ts` |
| CSS Module | `*.module.css` | `chat.module.css` |

跨目录导入用 `@/` 别名；同目录用 `./`。默认使用命名导出，只有 Next 约定的 page/layout 使用默认导出。

## 可访问性

- 按钮显式 `type="button"`；输入框有 `aria-label` 或关联 label。
- 进行中状态用 `role="status"`，错误用 `role="alert"`。
- 交互元素必须可用键盘操作，不依赖鼠标 hover。

## 测试

- 引入测试框架时优先 Vitest + Testing Library。
- 优先测试 `lib/sse`、`lib/api` 与 hooks 的行为；不依赖真实后端或模型。
- 组件测试断言用户可见行为（文本、角色、禁用态），不断言内部 state。

## 命令

```bash
cd web
npm run dev        # :3000
npm run typecheck
npm run build
```
