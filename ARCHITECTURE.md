# Architecture Guide

本项目是 Altibase + Qdrant 旁路架构的元数据、数据写入与同步控制面，当前不包含 embedding 生成流程，也不承担在线向量检索流量。

## 1. 分层依赖规则

依赖方向必须单向：

- `interfaces` -> `application.port.in`
- `application` -> `application.port.out` + `domain` + `common`
- `infrastructure` -> `application.port.out` + `domain`
- `domain` -> 仅 JDK 类型

禁止：

- Controller 直接调用 JDBC Repository
- `application` 反向依赖 `infrastructure`
- 将多张系统表逻辑重新合并成一个“大而全” service/repository

## 2. 模块边界（Registry vs Data vs Sync）

### Registry（定义“是什么”）

- 表：`SYS_VECTOR_COLUMNS_`、`SYS_VECTOR_COLLECTIONS_`、`SYS_VECTOR_INDEXES_`、`SYS_VECTOR_PAYLOAD_FIELDS_`
- 目录：
  - `domain/registry`
  - `application/registry`
  - `infrastructure/persistence/registry`

### Sync（定义“跑到哪了/失败了什么”）

- 表：`SYS_VECTOR_SYNC_JOBS_`、`SYS_VECTOR_SYNC_PROGRESS_`、`SYS_VECTOR_SYNC_ERRORS_`
- 目录：
  - `domain/sync`
  - `application/sync`
  - `infrastructure/persistence/sync`

### Data（定义“写入哪一行/哪个 point”）

- 表：`SYS_VECTOR_OUTBOX_EVENTS_`
- 业务表：由 create table API 创建的用户关系表，向量列在 Altibase 中落为 `VARBYTE(N)`，本地 H2 使用 `VARBINARY(N)`
- 目录：
  - `domain/data`
  - `application/data`
  - `infrastructure/persistence/data`

Data 模块的职责：

- `InsertVectorDataService` 负责同步写入关系库业务表。
- 有 vector 的 insert 在同一个关系库事务内写入 `SYS_VECTOR_OUTBOX_EVENTS_`。
- `VectorOutboxWorker` 异步消费 outbox，重新读取关系表向量和 payload，并 upsert Qdrant。
- Qdrant 是关系库事实源的派生索引，采用最终一致语义。
- `VectorPointIdNormalizer` 负责把业务主键稳定映射为 Qdrant point id，保证重复 upsert 幂等。
- `VectorValueEncoder` 负责关系表向量列的编码/解码，不应散落在 Controller 或 Repository 中。

### Shared

- `infrastructure/persistence/support`：跨仓储复用能力（例如 `JdbcTimeSupport`）
- `application/support`：通用参数校验、格式校验等，不承载业务状态

## 3. 一表一链路（新增系统表时必须补齐）

新增任意系统表时，按下列最小闭环补齐：

1. `domain` 新增对应 `Meta` 记录模型（按 registry/sync 归属放置）
2. `application.port.out` 新增读写 Port
3. `application.port.in` + `application/{registry|data|sync}` 新增 UseCase/Service
4. `infrastructure/persistence/{registry|data|sync}` 新增 JDBC Repository 实现
5. `interfaces/rest` 新增 Controller + Request/Response DTO
6. `db/altibase/001_init_sidecar.sql` 补齐 DDL（主键、唯一约束、状态约束、必要索引）
7. 增加至少一条集成测试覆盖新增 API

## 4. 当前 8 张系统表归属

- Registry：
  - `SYS_VECTOR_COLUMNS_` -> `VectorColumnMeta` / `RegisterVectorColumnService` / `JdbcVectorMetadataRepository`
  - `SYS_VECTOR_COLLECTIONS_` -> `VectorCollectionMeta` / `RegisterVectorCollectionService` / `JdbcVectorCollectionRepository`
  - `SYS_VECTOR_INDEXES_` -> `VectorIndexMeta` / `RegisterVectorIndexService` / `JdbcVectorIndexRepository`
  - `SYS_VECTOR_PAYLOAD_FIELDS_` -> `VectorPayloadFieldMeta` / `RegisterVectorPayloadFieldService` / `JdbcVectorPayloadFieldRepository`
- Sync：
  - `SYS_VECTOR_SYNC_JOBS_` -> `VectorSyncJobMeta` / `CreateVectorSyncJobService` / `JdbcVectorSyncJobRepository`
  - `SYS_VECTOR_SYNC_PROGRESS_` -> `VectorSyncProgressMeta` / `UpsertVectorSyncProgressService` / `JdbcVectorSyncProgressRepository`
  - `SYS_VECTOR_SYNC_ERRORS_` -> `VectorSyncErrorMeta` / `RecordVectorSyncErrorService` / `JdbcVectorSyncErrorRepository`
- Data：
  - `SYS_VECTOR_OUTBOX_EVENTS_` -> `VectorOutboxEventMeta` / `ListVectorOutboxEventService` + `VectorOutboxWorker` / `JdbcVectorOutboxEventRepository`

业务数据表不属于系统表，但由 Data 模块写入，由 System 模块在 create workflow 中创建。

## 5. 当前关键业务链路

### 建表链路

业务优先入口：

- `POST /api/v1/vector-tables`

高级控制面入口：

- `POST /api/v1/vector-schemas/tables`

建表链路由 `CreateVectorTableService` 编排：

- 规范化 schema/table/column 定义
- 计算 `DEFINITION_HASH`
- 注册 `SYS_VECTOR_COLUMNS_` 为 `BUILDING`
- 可选注册 collection/index 为 creating 状态
- 执行关系表 DDL
- 调用 Qdrant provisioning
- 成功后推进 column/collection/index 到可用态
- 失败后标记 `FAILED`

DDL 不应假设和系统表元数据注册具备跨数据库原子性。当前通过 `BUILDING + DEFINITION_HASH + 表结构校验` 支持可恢复的半成功流程。

### Insert 链路

入口：

- `POST /api/v1/vector-data/insert`

insert 支持：

- 纯标量：只写关系表，不写 outbox，不触发 Qdrant。
- 纯向量：写关系表向量列，并写 outbox。
- 标量 + 向量：写关系表标量列/向量列，并写 outbox。

有 vector 时，insert 不在请求事务里调用 Qdrant：

- 关系表业务行和 `SYS_VECTOR_OUTBOX_EVENTS_` 在同一个 DB 事务内提交。
- API 返回 `PENDING_OUTBOX` 和 `outboxEventId`。
- worker 至少一次消费 outbox。
- worker 用稳定 point id upsert Qdrant，重复处理必须安全。

### Outbox 查询链路

入口：

- `GET /api/v1/vector-outbox-events`

该接口只用于观测事件状态，不应承担重放、修复、删除等状态动作。后续如果需要，应新增显式 action API，例如 retry/dead-letter/replay。

## 6. 状态与一致性原则

- `SYS_VECTOR_COLUMNS_.STATUS=ACTIVE` 只能表示关系表结构和必需的向量引擎资源已经校验可用。
- 正常向量写入必须消费 `column=ACTIVE` 且存在 `collection=ACTIVE/READY` 的资源。
- collection/index 状态应通过 lifecycle service 推进，不应在业务编排中随意拼写状态组合。
- insert 以关系库为事实源，Qdrant 为最终一致派生索引。
- outbox worker 是至少一次投递模型，Qdrant upsert 必须以稳定 point id 保持幂等。
- `PENDING / PROCESSING / DONE / RETRYING / DEAD` 属于 outbox 事件生命周期，后续应收敛为独立 lifecycle/enum，避免散落裸字符串。

## 7. 演进建议

- 需要跨表编排时，再引入 `application/system` 级别编排服务；单表 CRUD 不要放入 `system` 层。
- 面向产品化，优先保持“每张表一套职责清晰的垂直链路”，而不是“按功能杂糅”。
- provisioning 仍是同步编排。若 Qdrant 创建/alias/index 后续变慢，应引入 provisioning operation/outbox，而不是让 Controller 请求长期等待。
- outbox worker 的状态更新建议增加 `lockedBy/fencing token` 条件，避免锁超时后旧 worker 覆盖新 worker 状态。
- insert 如需支持客户端安全重试，应增加请求级 `idempotencyKey` 或显式 upsert API。
- 系统表已经超过轻量 MVP，建议引入 Flyway 或 Liquibase 管理版本化 DDL。
- 需要补充 outbox 指标和告警：pending 数、retrying 数、dead 数、最老 pending 年龄、处理耗时、失败率。
