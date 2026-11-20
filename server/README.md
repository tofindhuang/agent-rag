# server — Java 业务层规范

Java 21 + Spring Boot 3。职责：认证、RAG 管线、沙箱调度、画像记忆与业务编排；**不直接调用 LLM**，所有模型流量经 gateway。

## 包结构

根包 `com.agentcore.server`，按层分包：

```
com.agentcore.server
├── AgentCoreApplication.java
├── controller/        HTTP 入口：参数校验、状态码、SSE 包装
├── service/           Service 接口，定义业务用例
│   └── impl/          Service 实现（@Service）
├── dao/               持久化接口（M1 起）；MySQL/Redis 访问只在这里
├── domain/            领域模型与持久化实体（M1 引入持久化时创建）
├── dto/               请求与响应 record，按功能分子包（chat/kb/sandbox/system）
├── config/            @ConfigurationProperties record
└── common/            错误码、业务异常、全局异常处理等横切设施
```

依赖方向严格单向：

```
controller ──> service（接口）──> dao
      │
      └──> common / config

service.impl ──实现──> service
service / dao ──> domain
```

- controller 只依赖 service 接口，不引用 `service.impl` 包；`@Service` 只标在实现类上。

- controller 不得直接调用 dao，不得自己拼 HTTP 请求或数据库访问。
- service 不得出现 Spring MVC 类型（`StreamingResponseBody` 等只属于 controller）。
- dao 不得依赖 controller/service/dto，只返回领域数据或持久化模型。
- domain 只放领域模型/实体，不依赖 controller/service/dto/dao；不要把实体直接当 DTO 暴露给前端。
- common 不承载业务逻辑；config 只放配置绑定。

## 命名

| 层 | 命名 | 示例 |
|---|---|---|
| controller | `XxxController` | `ChatController` |
| service 接口 | `XxxService` | `ChatService` |
| service 实现 | `XxxServiceImpl` | `ChatServiceImpl` |
| dao | `XxxDao` | `SessionDao`（M1） |
| 请求 DTO | `XxxRequest` | `SendMessageRequest` |
| 响应 DTO | `XxxResponse` | `KbSearchResponse` |
| 配置 | `XxxProperties` | `GatewayProperties` |

## DTO

- 一律用 `record`，放在 `dto/<功能>/`，不嵌套在 controller 里。
- DTO 只描述跨边界的输入/输出，不放业务逻辑、不引用实体或 Spring 类型。
- 参数校验放在 controller（项目约定），校验失败抛 `ApiException(ErrorCode.INVALID_REQUEST, ...)`。
- 序列化字段用 camelCase，与 Java↔gateway 契约一致。

## 错误处理

- 业务代码统一抛 `ApiException`，携带 `ErrorCode`；不要抛裸 `RuntimeException`，新代码也不要用 `ResponseStatusException` 拼错误。
- `GlobalExceptionHandler` 统一输出：

  ```json
  { "error": { "code": "SESSION_NOT_FOUND", "message": "...", "retryable": false } }
  ```

- 4xx 与 `NOT_IMPLEMENTED` 记 warn；其余 5xx 记 error 并带堆栈。响应 message 不含堆栈、SQL、内部地址。
- 错误码与 Java↔gateway 契约共享命名；新增码先加 `ErrorCode`，再在调用方处理。

## 配置与调用

- 配置走 `@ConfigurationProperties` record，默认值在访问器里兜底，不在业务代码里散落 `@Value`。
- CORS 仅放行 `localhost` / `127.0.0.1` 任意端口用于本地开发；生产由同源反代或显式白名单替代。
- 对 gateway 的 HTTP 调用集中在 service；controller 只做参数校验与编排。
- 超时、重试、鉴权 header 在 service 内统一处理；上游不可用映射为 `UPSTREAM_UNAVAILABLE`，上游返回错误映射为 `UPSTREAM_ERROR`。
- SSE 透传由 controller 包装 `StreamingResponseBody`，客户端断连的 `IOException` 静默丢弃。
- 需要 emitter 生命周期回调来取消后台工作的流（如 `run_code` 断连杀容器）使用 `SseEmitter`，同样只在 controller 层出现。

## 测试

- 目录镜像主代码包：`src/test/java/com/agentcore/server/...`。
- 优先无 Spring 上下文的单元测试；跨层行为用 `@SpringBootTest` + `MockMvc`。
- 不对接真实模型、真实 gateway 或外部 embedding 服务；契约测试用共享 fixture。
- 新增行为先写失败测试，再实现；测试只断言外部行为（HTTP 状态、响应体、落库结果）。

## 构建

```bash
cd server
mvn -q compile
mvn -q test
mvn spring-boot:run     # :8080
```
