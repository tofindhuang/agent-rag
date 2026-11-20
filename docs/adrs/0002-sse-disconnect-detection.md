# SSE 断连检测用 res close 而非 req close

Date: 2026-02-06

## Status

Accepted

## Context

gateway 的 prompt 端点在 SSE 流上转发 pi 会话事件，需要在客户端断连时 abort 进行中的 run（需求 FR-1「中断」）。Node 的 `IncomingMessage`（req）`close` 事件语义是「请求已完成，或底层连接被提前终止」——对带请求体的 POST，body 读完后 `req` close 立即触发，与客户端是否断连无关。骨架阶段用它做断连检测，导致每个 run 在发起瞬间即被 abort，SSE 返回空流。

## Decision

断连检测统一用 `res.on("close")` 并以 `res.writableEnded === false` 区分：正常 `res.end()` 后触发的是完成；未结束就 close 才是客户端中途断连，此时才 abort run。代码内已留注释（`gateway/src/index.ts`）。

## Considered Options(被否的备选)

- **`req.on("close")`** — 事故方案，body 读毕即触发。
- **应用层心跳/超时判活** — 更重，且解决不了「立即误 abort」这个具体问题；留作代理层不透传 close 时的后备。

## Consequences

- 依赖 HTTP/1.1 连接语义；中间若加反向代理，必须透传客户端断连（禁用响应缓冲：已设 `x-accel-buffering: no`）。
- HTTP/2 下 res close 语义不同，未来上 h2 时此 ADR 需要重审。
