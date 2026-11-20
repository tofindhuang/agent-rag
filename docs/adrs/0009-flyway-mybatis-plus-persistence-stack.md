# 持久化栈定为 Flyway（up-only）+ MyBatis-Plus（BaseMapper + 显式 XML）

Date: 2026-10-23

## Status

Accepted

## Context

agent-core 的 server 是 Java 21 / Spring Boot 3，需要为用户/凭证/会话/消息投影/runs 定迁移工具与 ORM。两个现实输入：

- 用户在其他仓库（ACC）的习惯是 `YYYYMMDDHHMMSS__slug.up.sql` + `migrate` CLI，且有一条全局铁律：**仓库迁移 up-only，`.down.sql` 一律不得推送**（migrate 工具对 down 文件会直接报错，曾出过 CI 红灯事故）。
- 用户在本仓 grilling 中两次明确选择 MyBatis 系（Q9 选 MyBatis，Q10 选 MyBatis-Plus），否掉了推荐的 JPA 与纯 XML 方案；随后 Q20 又确认了使用方式的收敛。

同时本仓分层规范是「service 接口 + impl + dao」，不能让 ORM 的模板服务层把它冲掉。

## Decision

- **迁移用 Flyway**：Java 原生，`mvn flyway:info/migrate` 与 CI `mvn verify` 一体。文件命名 `V<YYYYMMDDHHMMSS>__<slug>.up.sql`——`.up` 作为描述的一部分，Flyway 原生兼容，同时保留 up-only 的文件习惯与肌肉记忆；**绝不生成 `.down.sql`**，错误迁移靠新增迁移修正，不靠回滚。schema 归 Flyway 所有，ORM 不做任何 DDL。
- **ORM 用 MyBatis-Plus，但收敛用法**：
  - `dao` 层接口 `extends BaseMapper<T>` 承载简单 CRUD；
  - 关键路径 SQL（消息投影幂等 upsert、历史游标分页、批量写入、统计）写 XML 显式声明，SQL 可见可审；
  - 不引入 `IService`/`ServiceImpl`/Wrapper 链式构造复杂查询/ActiveRecord，保持「service 接口 + impl」分层与 SQL 透明度；
  - 软删不用 `@TableLogic` 自动改写，归档用显式 `status`/`deleted_at` 条件。
- **ID 策略**：DB 自增列 `IdType.AUTO`（users、messages、refresh_tokens）；外部生成的 UUID `IdType.INPUT`（sessions、runs、turnId 由 Java 生成）。
- `mapUnderscoreToCamelCase` 开启；依赖版本精确锁并与 Spring Boot 3.4.x 兼容验证。

## Considered Options(被否的备选)

- **golang-migrate CLI + ACC 命名** — 习惯完全一致，但 Java 仓库引入 Go 工具链、CI/本地都要装 binary，得不偿失。
- **Liquibase** — 功能全但更重，XML/YAML 变更集对当前规模无收益。
- **手写 schema.sql** — 无版本记录，直接排除。
- **Spring Data JPA（Hibernate）** — 生态主流，但用户明确选择 MyBatis 系；且投影表要精确控制幂等 upsert 与事务边界，显式 SQL 更可控。
- **原生 MyBatis 3 纯 XML** — SQL 全显式，但简单 CRUD 样板多；用户选了 Plus，用「BaseMapper 只管简单 CRUD」的收敛用法兼得两边。
- **MyBatis-Plus 全套 IService/ServiceImpl + Wrapper** — CRUD 最快，但 SQL 藏进链式构造、多一层模板服务与仓库分层冲突，关键路径不透明。
- **`@TableLogic` 逻辑删除** — 查询全局自动改写很省事，但隐藏 SQL 行为，与「关键路径显式」的原则相反。

## Consequences

- 迁移命令从 `migrate up/status` 换成 `mvn flyway:migrate/info`；Flyway 有自己的 history 表与 repair 语义，用户需要一次习惯迁移。
- up-only 意味着坏迁移只能正向补丁，评审时对 DDL 的正确性要求更高；新迁移保持幂等守卫（`WHERE NOT EXISTS` 等），以便手工补执行后正统重放不写重复行（全局铁律）。
- MyBatis-Plus 的隐式功能（字段自动注入、逻辑删除、乐观锁插件）默认关闭不启用，团队成员不得按习惯打开，避免破坏 SQL 透明度。
- BaseMapper + XML 混用意味着同仓两种查询风格，边界已在本文写死：简单 CRUD vs 关键 SQL，review 按此判。
- 后续复杂检索（RAG 元数据、用量统计）天然落 XML，不需要换栈。
