# Load-test harness（M1 closeout）

50 并发活跃会话的压测工具。规格见 `docs/specs/2026-10-25-m1-load-test.md`，
票见 `docs/tickets/m1-load-test/`。

## 关键约束

- **只打公开 Java HTTP API**（与 web 客户端同缝），不碰 gateway 内部接口。
- 栈是**一次性**的：容器数据只在容器内，`stack:down` 后全部消失；
  不读写任何既有本地数据库。
- DeepSeek key 只经 `LOADTEST_MODEL_API_KEY` 环境变量注入，不落文件、不进日志、
  不出现在报告里。

## 一次性栈

```bash
cd tools/loadtest
npm install

# 起栈（mysql/redis/qdrant/rabbitmq/silo + 假 embedding/rerank）
npm run stack:up

# 两个终端分别起 Java 与 gateway（前台运行）
./scripts/run-server.sh
./scripts/run-gateway.sh
```

端口默认：mysql `13306`、redis `16379`、qdrant `16333`、rabbitmq `15673`
（管理台 `15674`）、silo `19000`（控制台 `19001`）、假 embedding/rerank
`18099`。都可用 `LOADTEST_*_PORT` 覆盖；Java 连接串由
`scripts/stack-env.sh` 里的 `SPRING_DATASOURCE_URL` 覆盖。

## 单用户冒烟

```bash
LOADTEST_MODEL_API_KEY=... npm run smoke
```

流程：注册 → 登录 → 建 DeepSeek provider → 按 catalog 的模型 id 建会话 →
SSE 一轮对话 → 收到 `run_settled`，原始指标写入 `.run/metrics.jsonl`。

## 拆栈

```bash
npm run stack:down
```

## 单元测试

```bash
npm test        # SSE 解析 / 指标聚合 / API 请求形状 / 假 embedding 服务
npm run typecheck
```
