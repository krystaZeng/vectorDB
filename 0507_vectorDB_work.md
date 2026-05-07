# 2026-05-07 vectorDB 工作记录

本文记录 2026-05-07 对 `vector-sidecar-service` 的主要问题分析、设计判断与代码落地方案。

## 1. 状态机设计收敛

### 问题

原先 collection/index 状态在业务代码中直接传字符串，例如 `ACTIVE`、`READY`、`FAILED`。如果后续支持重建索引、灰度切换、删除、重试、异步 provisioning，裸字符串会导致状态规则分散，容易出现非法流转。

### 解决方案

将状态收敛到 lifecycle 组件中：

- `VectorColumnLifecycle`
- `VectorColumnLifecycleService`
- `VectorCollectionLifecycle`
- `VectorCollectionLifecycleService`
- `VectorIndexLifecycle`
- `VectorIndexLifecycleService`

业务编排代码通过 `markActive()`、`markFailed()`、`markReady()`、`markCreating()` 等方法推进状态，而不是直接写裸字符串。

### 关键语义

- `SYS_VECTOR_COLUMNS_.STATUS = ACTIVE` 只表示整体向量能力可用。
- 如果启用了 Qdrant provisioning，必须在 collection、alias、index 都校验通过后，column 才能进入 `ACTIVE`。
- `BUILDING` 可自动 resume。
- `FAILED` 不允许普通 create 请求自动覆盖，必须后续显式 retry/repair。

## 2. 计数字段非负校验

### 问题

同步进度和错误计数字段允许负数，可能污染统计语义。

涉及对象：

- `UpsertVectorSyncProgressService`
- `RecordVectorSyncErrorRequest`

### 解决方案

- API DTO 增加 `@Min(0)`。
- service 层通过 `FieldValidator.nonNegativeOrDefault()` 兜底。
- DDL 增加 CHECK 约束，防止绕过 API 写入非法数据。

## 3. ID 生成器多实例冲突

### 问题

原 `IdGenerator` 使用进程内 `AtomicLong + 时间种子`，多实例 sidecar 或进程重启后存在主键冲突风险。

### 解决方案

采用数据库 sequence：

- 新增 `IdGeneratorPort`
- 新增 `JdbcSequenceIdGenerator`
- 删除进程内 `IdGenerator`
- 初始化脚本新增 `SYS_VECTOR_ID_SEQ`

### 设计判断

DB sequence 更适合多副本 sidecar：

- 全局唯一由数据库保证。
- 应用层只依赖 `IdGeneratorPort`。
- sequence 只保证唯一和递增，不承诺连续。

## 4. Qdrant alias 409 处理不安全

### 问题

原逻辑把 Qdrant alias 创建时的 409 当成成功，没有校验 alias 当前指向。如果 alias 已经指向旧 collection，可能把系统状态错误标为 READY。

### 解决方案

`QdrantVectorEngineAdminAdapter.ensureAlias()` 改为：

1. 先查询 alias 当前指向。
2. 如果 alias 已指向目标 collection，视为幂等成功。
3. 如果 alias 不存在，创建 alias。
4. 如果创建时遇到 409，再查一次 alias 指向。
5. 如果指向目标 collection，视为成功；否则失败。

## 5. CREATE TABLE 与元数据事务原子性问题

### 问题

`CREATE TABLE` 和系统表注册原先被放在同一事务模板中，但 DDL 在部分数据库中会隐式提交，因此事务原子性不成立，可能出现：

- 业务表已创建
- 系统表未注册或状态未推进

### 解决方案

将 create workflow 改为显式分阶段：

1. 先写 `BUILDING` 元数据。
2. 再执行关系表 DDL。
3. 如果表已存在，校验结构是否匹配。
4. 执行 Qdrant collection/index/alias provisioning。
5. 全部成功后才将 column 标为 `ACTIVE`。
6. 失败时将 column/collection/index 标为 `FAILED`。

### 恢复规则

`BUILDING` 状态下，相同 `DEFINITION_HASH` 的请求可以自动 resume：

- 表不存在：重新执行 DDL。
- 表存在：校验结构。
- 结构匹配：继续 Qdrant provisioning。
- 结构不匹配：失败。

`FAILED` 状态下，普通 create 请求不会自动重试。

## 6. DEFINITION_HASH / DDL hash

### 问题

如果同一个 `schema.table.vectorColumn` 被重复 create，但请求定义不同，系统无法区分是幂等重试还是冲突请求。

例如：

- 第一次 vector 维度是 768。
- 第二次 vector 维度是 1536。

### 解决方案

在 `SYS_VECTOR_COLUMNS_` 增加 `DEFINITION_HASH`：

- 同一 `tenant/schema/table/vectorColumn` 且 hash 相同：允许幂等恢复。
- hash 不同：直接拒绝，报定义冲突。

hash 内容包含 tenant、schema、table、主键、普通列、向量列、engine、auto provisioning 配置和 DDL。

## 7. ifNotExists 并发竞态

### 问题

`ifNotExists` 原本是先查表再建表。并发下两个请求可能同时看到表不存在，后一个请求执行 DDL 失败并返回错误，不是严格幂等。

### 解决方案

将 DDL 执行结果改为内部枚举：

- `CREATED_BY_THIS_ATTEMPT`
- `ALREADY_EXISTS_AND_MATCHED`

并新增 DDL 失败分类：

- `OBJECT_ALREADY_EXISTS`
- `UNKNOWN_EXECUTION_STATE`
- `NON_RETRYABLE`

只有以下情况允许复查表结构并转为幂等成功：

- duplicate table / object already exists
- DDL 执行结果不确定，例如超时、连接中断

语法错误、权限不足、类型不支持等 `NON_RETRYABLE` 错误不会被吞掉。

### 语义澄清

API 中的 `ddlExecuted=false` 只表示：

> 当前请求没有新建表，但目标表已经存在且结构匹配。

它不表示整个 create workflow 结束。后续 metadata 修复、Qdrant provisioning、alias 校验、状态推进仍会继续执行。

## 8. 元数据唯一键并发

### 问题

两个请求可能同时注册同一个 vector column。后插入方会遇到唯一键冲突，如果直接失败，会影响幂等恢复。

### 解决方案

捕获注册冲突后重新查询已有 metadata：

- 如果 `DEFINITION_HASH` 相同，按幂等恢复处理。
- 如果 hash 不同，报定义冲突。

## 9. 系统表引用完整性

### 问题

系统表之间有父子关系，但原先主要只校验 ID 是否为正数，缺少存在性和归属校验，容易产生悬挂元数据。

典型风险：

- collection 指向不存在的 column。
- index 指向不存在的 collection。
- index 的 collection 不属于同一个 column。
- sync progress 的 job 存在，但请求里的 columnId 和 job.columnId 不一致。

### 解决方案

新增 `VectorMetadataReferenceGuard`，集中做引用完整性校验：

- column/job/collection/index 是否存在
- collection/index/job 是否属于传入 column
- collection/index 的 dimension、metric 是否和 column 定义一致
- DISABLED column 不允许继续挂子元数据

接入点：

- `RegisterVectorCollectionService`
- `RegisterVectorIndexService`
- `RegisterVectorPayloadFieldService`
- `UpsertVectorSyncProgressService`
- `RecordVectorSyncErrorService`

### 与 ServingReadinessGuard 的职责区分

`VectorMetadataReferenceGuard`：

- 解决引用完整性。
- 不要求父资源 READY。
- 支持 create workflow 中 column 处于 `BUILDING` 时注册 collection/index。

`VectorServingReadinessGuard`：

- 解决业务可用性。
- sync job 等正常业务入口必须使用：
  - column = `ACTIVE`
  - collection = `ACTIVE/READY`
  - index = `ONLINE/READY`

## 10. 数据库外键兜底

### 问题

应用层 Guard 可以阻止 API 写入脏数据，但无法阻止绕过 API 直接写库。

### 解决方案

在新库初始化脚本中增加单列 FK：

- `SYS_VECTOR_COLLECTIONS_.COLUMN_ID -> SYS_VECTOR_COLUMNS_.COLUMN_ID`
- `SYS_VECTOR_INDEXES_.COLUMN_ID -> SYS_VECTOR_COLUMNS_.COLUMN_ID`
- `SYS_VECTOR_INDEXES_.COLLECTION_ID -> SYS_VECTOR_COLLECTIONS_.COLLECTION_ID`
- `SYS_VECTOR_PAYLOAD_FIELDS_.COLUMN_ID -> SYS_VECTOR_COLUMNS_.COLUMN_ID`
- `SYS_VECTOR_SYNC_JOBS_.COLUMN_ID -> SYS_VECTOR_COLUMNS_.COLUMN_ID`
- `SYS_VECTOR_SYNC_JOBS_.COLLECTION_ID -> SYS_VECTOR_COLLECTIONS_.COLLECTION_ID`
- `SYS_VECTOR_SYNC_JOBS_.INDEX_ID -> SYS_VECTOR_INDEXES_.INDEX_ID`
- `SYS_VECTOR_SYNC_PROGRESS_.JOB_ID -> SYS_VECTOR_SYNC_JOBS_.JOB_ID`
- `SYS_VECTOR_SYNC_PROGRESS_.COLUMN_ID -> SYS_VECTOR_COLUMNS_.COLUMN_ID`
- `SYS_VECTOR_SYNC_ERRORS_.JOB_ID -> SYS_VECTOR_SYNC_JOBS_.JOB_ID`
- `SYS_VECTOR_SYNC_ERRORS_.COLUMN_ID -> SYS_VECTOR_COLUMNS_.COLUMN_ID`

### 删除策略

不使用 `ON DELETE CASCADE`。

控制面删除/drop 应走显式 workflow，不应因为误删父表自动级联删除大量子元数据。

### 后续演进

当前先加单列 FK。跨 column 归属一致性目前由应用层 Guard 保证。后续已有库清理完存量脏数据后，可以再考虑复合唯一键和复合 FK，例如：

- `SYNC_PROGRESS(JOB_ID, COLUMN_ID) -> SYNC_JOBS(JOB_ID, COLUMN_ID)`
- `INDEXES(COLLECTION_ID, COLUMN_ID) -> COLLECTIONS(COLLECTION_ID, COLUMN_ID)`

## 11. README 更新

README 已补充：

- 架构边界
- 状态生命周期原则
- DB sequence ID 策略
- Create Table 分阶段语义
- DDL 幂等恢复语义
- Qdrant alias 幂等校验
- 系统表引用完整性设计

## 12. 测试验证

今日修改后已执行：

```bash
./mvnw verify
```

最终结果：

- 测试总数：29
- Failures：0
- Errors：0
- 构建结果：BUILD SUCCESS

新增或扩展的测试覆盖包括：

- lifecycle 状态流转
- DB sequence ID 生成
- Qdrant alias 409 指向校验
- DDL duplicate table 与 bad syntax 分类
- create table 幂等恢复
- `DEFINITION_HASH` 冲突
- FAILED 普通重试拒绝
- 引用不存在父记录
- collection/index 跨 column 归属错误
- sync progress/error 的 job 与 column 不一致
