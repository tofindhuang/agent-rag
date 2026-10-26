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

端口默认：mysql `23306`、redis `23379`、qdrant `23333`、rabbitmq `23673`
（管理台 `23674`）、silo `29000`（控制台 `29001`）、假 embedding/rerank
`28099`。都可用 `LOADTEST_*_PORT` 覆盖；Java 连接串由
`scripts/stack-env.sh` 里的 `SPRING_DATASOURCE_URL` 覆盖。

## 单用户冒烟

```bash
LOADTEST_MODEL_API_KEY=... npm run smoke
```

流程：注册 → 登录 → 建 DeepSeek provider → 按 catalog 的模型 id 建会话 →
SSE 一轮对话 → 收到 `run_settled`，原始指标写入 `.run/metrics.jsonl`。

## 多实例 + 滚动重启

```bash
LOADTEST_MANAGE_APP=1 \
LOADTEST_GATEWAY_INSTANCES=5 \
LOADTEST_USERS=200 \
LOADTEST_RESTART_GATEWAY_AFTER_MS=5000 \
LOADTEST_MODEL_API_KEY=... npm run run
```

五个 gateway 进程共享同一个 `GATEWAY_SESSION_DIR`，Java 通过
`GATEWAY_URLS` 发现它们。默认从 `:3001` 起；本机端口被占用时用
`LOADTEST_GATEWAY_BASE_PORT=3101` 换一组连续端口。重启阶段逐个调用 `POST /admin/drain`，等在飞
轮次结束后再退出并启动新进程，验证滚动更新不断流。

## 托管模式 + gateway 滚动重启

让 harness 自己起停 Java/gateway，并在压测中段重启 gateway 一次：

```bash
LOADTEST_MANAGE_APP=1 \
LOADTEST_RESTART_GATEWAY_AFTER_MS=5000 \
LOADTEST_RETRY_DELAY_MS=2000 LOADTEST_MAX_DISCONNECT_RETRIES=4 \
LOADTEST_MODEL_API_KEY=... npm run run
```

重启期间被中断/中止的 turn 会以同一 `turnId` 重试；跑完后逐会话读取消息投影，
校验每个 completed turn 恰好一条 user 消息、至少一条 assistant 消息，且 assistant
文本总量与流式 `message_end` 一致（不丢不重）。

重启 Java（验证 §4「任一重启」）用 `LOADTEST_RESTART_SERVER_AFTER_MS` 替代
`LOADTEST_RESTART_GATEWAY_AFTER_MS`，其余参数相同。

压测沙箱预热池时用 `LOADTEST_WARMUP_MS=30000` 等池填满再开始；否则开跑瞬间
的执行会走冷创建兜底，测到的是混合路径。配合 `SANDBOX_POOL_ENABLED` /
`SANDBOX_POOL_TARGET` 分别跑池开/关基线。设置
`LOADTEST_SANDBOX_METRICS_URL=http://127.0.0.1:9091/actuator/prometheus`
会把本次运行的 `sandbox_*` 指标摘录到 `.run/sandbox-metrics.txt`。

## 拆栈

```bash
npm run stack:down
```

## 单元测试

```bash
npm test        # SSE 解析 / 指标聚合 / API 请求形状 / 假 embedding 服务
npm run typecheck
```
