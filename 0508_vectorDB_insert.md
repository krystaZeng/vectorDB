# 2026-05-08 vectorDB Insert 工作记录

本文记录 2026-05-08 对 `vector-sidecar-service` 的主要功能实现、设计调整、验证结果与后续风险。

## 1. 今日目标

今天主要围绕两个方向推进：

- 简化业务侧建表与写入 API，避免让调用方直接理解过多系统表和内部 ID。
- 将 insert 链路从“同步写关系库 + 同步写 Qdrant”调整为更适合生产的 outbox 最终一致模型。

核心目标是让业务调用方可以按自然语义操作：

- `POST /api/v1/vector-tables` 创建带向量能力的业务表。
- `POST /api/v1/vector-data/insert` 插入记录。
- 有向量数据时，关系库事务内写业务表和 outbox，Qdrant 由 worker 异步 upsert。
- 纯标量数据时，只写关系库，不强制要求 Qdrant collection READY。

## 2. 简化建表 API

### 问题

原先建表入口偏控制面，调用方需要理解较多内部概念，例如 collection、index、engine、sync 配置等。对于业务侧来说，这会显得冗余。

### 解决方案

新增推荐入口：

- `POST /api/v1/vector-tables`

该接口隐藏大部分默认值：

- 默认 `engineType = QDRANT`
- 默认 `ifNotExists = true`
- 默认自动注册 collection
- 默认自动注册 index
- 默认 index profile 为 `default`
- 默认向量元素类型为 `FLOAT32`
- 默认距离度量为 `COSINE`
- 默认同步模式为 `FULL_AND_INCREMENTAL`

高级入口仍保留：

- `POST /api/v1/vector-schemas/tables`

### 当前语义

`/api/v1/vector-tables` 面向业务调用方，适合常规场景。

`/api/v1/vector-schemas/tables` 面向控制面或高级配置场景，适合后续需要手动指定 engine、collection、index、payload、provisioning 策略的情况。

## 3. Insert API 设计调整

### 问题

最初讨论 insert 请求时，曾考虑让调用方直接传 `columnId`：

```json
{
  "columnId": 1001,
  "pk": 123,
  "vector": [0.1, 0.2, 0.3],
  "payload": {
    "title": "doc title"
  }
}
```

这个设计对内部实现方便，但对业务调用方不自然。`columnId` 是 sidecar 的系统元数据 ID，不应该成为普通业务 API 的主要入参。

### 解决方案

最终 insert API 使用业务身份解析内部元数据：

- `tenantId`
- `schemaName`
- `tableName`
- `vectorColumn`
- `pk`
- `vector`
- `payload`

推荐入口：

- `POST /api/v1/vector-data/insert`

示例：

```json
{
  "tenantId": "default",
  "schemaName": "public",
  "tableName": "documents",
  "vectorColumn": "embedding",
  "pk": 501,
  "vector": [0.11, 0.22, 0.33],
  "payload": {
    "title": "real qdrant outbox e2e",
    "docType": "e2e"
  }
}
```

如果表下只有一个向量列，`vectorColumn` 可以不传。服务会通过 `tenantId + schemaName + tableName` 自动解析唯一向量列。

如果表下存在多个向量列，必须传 `vectorColumn`，否则返回明确错误，避免写错向量列。

## 4. Insert 三种数据形态

### 纯标量记录

请求不包含 `vector`，只包含 `pk` 和 `payload`。

处理逻辑：

1. 根据业务身份解析 vector table 元数据。
2. 校验业务表存在。
3. 校验 payload key 已注册且为 `ACTIVE`。
4. 将 `payloadKey` 映射到关系表的 `SOURCE_COLUMN_NAME`。
5. 只向关系数据库业务表执行 insert。
6. 不写 outbox。
7. 不要求 Qdrant collection/index/alias READY。

返回语义：

- `relationalInserted = true`
- `vectorInserted = false`
- `vectorUpsertStatus = SKIPPED_SCALAR_ONLY`
- `outboxEventId = null`

### 纯向量记录

请求包含 `vector`，不包含业务 payload。

处理逻辑：

1. 校验 vector column 为 `ACTIVE`。
2. 校验存在 `ACTIVE + READY` collection。
3. 将向量编码后写入关系表向量列。
4. 在同一个关系库事务内写入 `SYS_VECTOR_OUTBOX_EVENTS_`。
5. worker 异步消费 outbox 并 upsert Qdrant。

返回语义：

- `relationalInserted = true`
- `vectorInserted = true`
- `vectorUpsertStatus = PENDING_OUTBOX`
- `outboxEventId` 有值

### 标量 + 向量混合记录

请求同时包含 `vector` 和 `payload`。

处理逻辑：

1. 校验 vector column 为 `ACTIVE`。
2. 校验 collection 为 `ACTIVE + READY`。
3. 校验 payload key 已注册且为 `ACTIVE`。
4. 将 payload 写入关系表对应标量列。
5. 将 vector 写入关系表向量列。
6. 同事务写入 outbox。
7. worker 异步读取业务表当前行，组装 Qdrant point payload，再 upsert Qdrant。

返回语义：

- `relationalInserted = true`
- `vectorInserted = true`
- `vectorUpsertStatus = PENDING_OUTBOX`
- `outboxEventId` 有值

## 5. Payload Key 校验

### 为什么需要校验

insert 请求里的 `payload` 是对外 API 字段，不等同于关系表列名。它需要通过系统表注册信息映射到真实关系表列。

例如：

- API payload key: `docType`
- 关系表列: `DOC_TYPE`
- Qdrant payload key: `docType`

如果不校验 payload key，可能出现几个问题：

- 调用方传错 key，关系表 insert 时才报底层 SQL 错误。
- 未注册字段被写入，破坏控制面定义。
- Qdrant payload 与关系表字段映射失控。
- 后续同步、过滤、索引策略无法依赖系统表元数据。

### 当前规则

insert 时只允许写入已注册且 `ACTIVE` 的 payload key。

payload field 的两个职责：

- `SOURCE_COLUMN_NAME` 决定写入关系表哪个标量列。
- `SYNC_ENABLED = Y` 决定 worker 是否把该字段同步到 Qdrant payload。

## 6. Outbox 最终一致改造

### 问题

如果 insert API 在一个请求里同时写关系库和 Qdrant，会遇到跨系统一致性问题：

- 关系库 insert 成功，Qdrant upsert 失败。
- Qdrant upsert 成功，关系库事务回滚。
- 请求超时后调用方不知道 Qdrant 是否已经写入。
- 重试时容易产生重复写或状态不一致。

### 解决方案

采用关系库 outbox 模式：

1. insert API 在一个关系库事务内写业务表。
2. 同一个事务内写 `SYS_VECTOR_OUTBOX_EVENTS_`。
3. API 返回 `PENDING_OUTBOX`。
4. `VectorOutboxWorker` 异步扫描待处理事件。
5. worker 读取业务表当前行，构造 Qdrant point。
6. worker 调用 Qdrant upsert。
7. 成功后标记 outbox 为 `DONE`。
8. 失败后按退避策略重试。
9. 超过最大重试次数后标记为 `DEAD`。

### 设计判断

这个方案比同步双写更适合生产：

- 关系库仍是事实源。
- 业务表和 outbox 处于同一个本地事务。
- Qdrant upsert 使用稳定 point id，重复执行具备幂等性。
- 失败事件可观测、可重试、可人工处理。

代价是实时可见性变成最终一致。当前 worker 默认 1 秒扫描一次，正常情况下 Qdrant 可见性延迟约为毫秒级到 1 秒级，失败或 Qdrant 不可用时会按重试策略延迟。

## 7. 新增 outbox 系统表

新增系统表：

- `SYS_VECTOR_OUTBOX_EVENTS_`

主要字段语义：

- `EVENT_ID`: outbox 事件 ID。
- `COLUMN_ID`: 对应 vector column。
- `EVENT_TYPE`: 当前为 `UPSERT`。
- `EVENT_STATUS`: `PENDING`、`PROCESSING`、`DONE`、`RETRYING`、`DEAD`。
- `SOURCE_PK`: 关系表主键文本值。
- `POINT_ID`: Qdrant point id。
- `PK_VALUE_TYPE`: `NUMBER` 或 `STRING`。
- `DEDUPE_KEY`: 去重键，当前语义为 `columnId:sourcePk:UPSERT`。
- `RETRY_COUNT`: 已重试次数。
- `NEXT_RETRY_AT_EPOCH_MS`: 下次可重试时间。
- `LOCKED_BY`: 当前处理 worker。
- `LOCKED_AT_EPOCH_MS`: 锁定时间。
- `LAST_ERROR_CODE`: 最近一次错误码。
- `LAST_ERROR_MESSAGE`: 最近一次错误信息。

DDL 已同步到：

- `src/main/resources/schema.sql`
- `src/main/resources/db/altibase/001_init_sidecar.sql`
- `src/main/resources/db/altibase/001_init_sidecar_altibase_epoch.sql`

## 8. Outbox Worker

新增 worker：

- `VectorOutboxWorker`

核心行为：

1. 定时释放过期的 `PROCESSING` 锁。
2. 扫描 `PENDING` 和到期的 `RETRYING` 事件。
3. 将事件 claim 为 `PROCESSING`。
4. 读取对应业务表行。
5. 解码关系表中的 vector bytes。
6. 根据 active payload fields 组装 Qdrant payload。
7. 调用 vector engine data router upsert Qdrant point。
8. 成功后标记为 `DONE`。
9. 失败后进入 `RETRYING` 或 `DEAD`。

配置项：

- `SIDECAR_OUTBOX_WORKER_ENABLED`
- `SIDECAR_OUTBOX_WORKER_FIXED_DELAY_MS`
- `SIDECAR_OUTBOX_WORKER_BATCH_SIZE`
- `SIDECAR_OUTBOX_WORKER_MAX_RETRIES`
- `SIDECAR_OUTBOX_WORKER_INITIAL_RETRY_DELAY_MS`
- `SIDECAR_OUTBOX_WORKER_MAX_RETRY_DELAY_MS`
- `SIDECAR_OUTBOX_WORKER_LOCK_TIMEOUT_MS`

默认 worker 是关闭的，需要显式开启：

```bash
SIDECAR_OUTBOX_WORKER_ENABLED=true
```

## 9. Outbox 查询 API

新增只读查询入口：

- `GET /api/v1/vector-outbox-events`

用于查看 insert 到 Qdrant 的异步同步状态。

典型用途：

- 查询某个事件是否已经 `DONE`。
- 查看失败事件的错误原因。
- 排查 Qdrant 不可用导致的堆积。
- 后续支持人工 retry / dead letter 管理。

当前只做查询，不做人工 retry 或状态强制修改。

## 10. 涉及的主要代码

### Create Table

- `CreateSimpleVectorTableService`
- `CreateSimpleVectorTableUseCase`
- `VectorTableController`

### Insert Data

- `InsertVectorDataService`
- `InsertVectorDataUseCase`
- `InsertVectorDataRequest`
- `InsertVectorDataResponse`
- `VectorDataController`
- `JdbcVectorDataRepository`
- `VectorDataRelationalPort`
- `VectorValueEncoder`
- `VectorPointIdNormalizer`

### Outbox

- `VectorOutboxWorker`
- `VectorOutboxEventMeta`
- `VectorOutboxEventPort`
- `JdbcVectorOutboxEventRepository`
- `ListVectorOutboxEventUseCase`
- `ListVectorOutboxEventService`
- `VectorOutboxEventController`
- `VectorOutboxEventResponse`

### Qdrant Data

- `VectorEngineDataRouter`
- `QdrantVectorEngineAdminAdapter.upsertPoint`

## 11. 测试覆盖

新增或强化的测试包括：

- `VectorTableControllerIT`
- `VectorDataControllerIT`
- `VectorOutboxWorkerIT`

覆盖场景：

- 简化建表接口。
- 自动注册 payload fields。
- create table 幂等重试。
- scalar-only insert。
- vector-only insert。
- scalar + vector insert。
- 未注册 payload key 拒绝。
- insert 写关系表和 outbox。
- worker 消费 outbox 并标记 `DONE`。
- worker 失败后进入 retry/dead 逻辑。

完整验证结果：

```text
./mvnw verify
Unit tests: 15 passed
Failsafe integration tests: 36 passed
BUILD SUCCESS
```

代码格式检查：

```text
git diff --check
passed
```

## 12. 真实 Qdrant 端到端验证

今天已做真实 Qdrant insert/outbox 端到端验证。

验证环境：

- Qdrant: `qdrant/qdrant:v1.17.0`
- Sidecar: 本地 Spring Boot 应用
- 关系型数据库: H2 in-memory local profile
- JDBC URL: `jdbc:h2:mem:vector_sidecar`

启动时开启：

```bash
SIDECAR_QDRANT_ENABLED=true
SIDECAR_OUTBOX_WORKER_ENABLED=true
SIDECAR_QDRANT_URL=http://localhost:6333
```

验证链路：

1. 调用 `/api/v1/vector-tables` 创建测试表。
2. 自动创建并注册 Qdrant collection/index/alias。
3. collection 状态达到 `ACTIVE / READY`。
4. index 状态达到 `ONLINE / READY`。
5. 调用 `/api/v1/vector-data/insert` 插入关系行和向量。
6. API 返回 `PENDING_OUTBOX` 和 `outboxEventId`。
7. worker 消费 outbox。
8. outbox 状态变为 `DONE`。
9. 直接查询 Qdrant，确认 point 已存在。

验证样例：

- 表名：`DOC_OUTBOX_E2E_141903`
- columnId: `1`
- collectionId: `2`
- indexId: `3`
- insert pk: `501`
- outboxEventId: `6`
- outbox 最终状态: `DONE`
- Qdrant collection: `DOC_OUTBOX_E2E_141903_EMBEDDING_V1`
- Qdrant point id: `501`
- Qdrant payload: `docType=e2e`，`title=real qdrant outbox e2e`

注意：这次真实 Qdrant 验证使用的是 H2 关系库，不是 Altibase。Altibase 方向的 DDL 已同步，但仍需要单独做 Altibase + Qdrant 端到端实测。

## 13. 当前设计判断

目前 create table 和 insert record 的 API 方向是合理的：

- 业务 API 不暴露 `columnId` 作为主要入参。
- 系统内部仍保留 `columnId` 作为元数据主键。
- create table 推荐使用简化入口，高级入口保留。
- insert 支持纯标量、纯向量、标量向量混合三种形态。
- 有 vector 的 insert 不再同步双写 Qdrant，而是通过 outbox 达成最终一致。
- 关系库是事实源，Qdrant 是可重建的检索副本。

这符合当前 sidecar 的定位：关系数据库负责事务与主数据，Qdrant 负责向量检索能力。

## 14. 当前限制与后续建议

### Outbox fencing

当前 worker 已有 claim 和 lock timeout 机制，但后续应继续加强 fencing：

- `markDone`
- `markRetry`
- `markDead`

这些状态更新最好带上 `lockedBy` 或版本条件，避免极端情况下旧 worker 覆盖新 worker 处理结果。

### Insert 幂等性

当前 outbox 有 `DEDUPE_KEY = columnId:sourcePk:UPSERT`，可以避免同一主键重复创建多个 UPSERT 事件。

后续如果要支持调用方级别的安全重试，建议新增：

- `idempotencyKey`
- 或者明确 insert/upsert 语义分离

否则重复调用 insert 可能在关系表主键层面失败。

### Update / Delete

今天主要完成 insert。

后续还需要补：

- update record
- delete record
- Qdrant delete point
- outbox event type 扩展为 `UPSERT` / `DELETE`
- 重放和修复 API

### Altibase 实测

Altibase 初始化脚本已同步 outbox 表，但真实链路还需要验证：

- DB sequence 行为
- DDL 兼容性
- 二进制 vector bytes 写入与读取
- outbox worker 查询与锁语义
- Altibase + Qdrant 的端到端 insert

### 运维能力

生产前还建议补：

- outbox 堆积指标
- retry/dead 指标
- worker 成功率指标
- Qdrant upsert 延迟指标
- dead event 查询与人工 retry API
- 结构化日志中的 eventId、columnId、sourcePk

## 15. 当前结论

截至 2026-05-08，项目已经具备较完整的核心链路：

1. 创建向量业务表。
2. 注册向量列、collection、index、payload fields。
3. 严格推进 lifecycle 状态。
4. 插入纯标量记录。
5. 插入纯向量记录。
6. 插入标量 + 向量混合记录。
7. 关系库事务内写 outbox。
8. worker 异步 upsert Qdrant。
9. 查询 outbox 同步状态。
10. H2 + 真实 Qdrant 端到端验证通过。

整体上，insert 链路已经从 demo 级同步双写，推进到了更接近生产的最终一致架构。下一阶段的重点不再是能否跑通，而是补齐幂等、fencing、运维、Altibase 实测和 update/delete 等生产能力。
