# vector-sidecar-service

Altibase + Qdrant 旁路架构的向量控制面 Sidecar。

本服务负责管理向量列元数据、Qdrant collection/index 元数据、同步任务进度与错误记录，并在需要时编排关系表建表、Qdrant 物理资源创建和同步式向量数据写入。当前不负责 embedding 生成，也不承担在线向量检索流量。

架构分层与目录约束见：[ARCHITECTURE.md](ARCHITECTURE.md)

## 架构设计结论

当前设计采用分层架构和端口适配器风格，整体方向是合理的，适合作为向量控制面的 MVP 基础：

- `interfaces`: REST API（Controller + DTO）
- `application`: UseCase、业务编排、状态生命周期
- `domain`: 领域数据模型
- `infrastructure`: JDBC 持久化适配器、Qdrant 适配器

依赖方向保持单向：

- `interfaces` 只调用 `application.port.in`
- `application` 只依赖 `application.port.out`、`domain`、`common`
- `infrastructure` 实现 `application.port.out`
- `domain` 不依赖 Spring、JDBC、Qdrant SDK 或 Controller DTO

这种拆分的核心价值是：业务规则不会被 Controller、JDBC SQL 或 Qdrant HTTP 调用绑死。后续如果替换向量引擎、调整数据库脚本、增加异步 worker，核心 UseCase 和状态规则仍可以保留。

## 模块边界

### Registry：定义“是什么”

Registry 负责向量侧元数据定义：

- `SYS_VECTOR_COLUMNS_`: 业务表中的向量列
- `SYS_VECTOR_COLLECTIONS_`: 向量引擎 collection 元数据
- `SYS_VECTOR_INDEXES_`: index/profile 元数据
- `SYS_VECTOR_PAYLOAD_FIELDS_`: payload 字段元数据

相关目录：

- `domain/registry`
- `application/registry`
- `application/registry/lifecycle`
- `infrastructure/persistence/registry`

### Sync：定义“跑到哪了/失败了什么”

Sync 负责同步任务运行态：

- `SYS_VECTOR_SYNC_JOBS_`: 同步任务
- `SYS_VECTOR_SYNC_PROGRESS_`: 分区/批次进度
- `SYS_VECTOR_SYNC_ERRORS_`: 同步错误与重试信息

相关目录：

- `domain/sync`
- `application/sync`
- `infrastructure/persistence/sync`

### Data：定义“写入哪一行/哪个 point”

Data 负责同步式数据写入：

- 通过业务身份 `tenantId + schemaName + tableName + vectorColumn` 定位内部 `columnId`
- 校验 `column=ACTIVE`、`collection=ACTIVE/READY`
- 将向量按 `VECTOR_ENCODING` 编码为关系表中的 `VARBYTE/VARBINARY`
- 将同一向量 upsert 到 Qdrant points
- payload 只接受已注册且 ACTIVE 的 payload key，并通过 `SOURCE_COLUMN_NAME` 写入关系表标量列

相关目录：

- `application/data`
- `infrastructure/persistence/data`

### System：跨表编排

`application/system` 只放跨模块流程，例如：

- 创建关系业务表
- 注册向量列
- 自动注册 collection/index
- 调用 Qdrant provisioning
- 根据 provisioning 结果推进状态

单表 CRUD 不应放进 `system` 层，避免退化成一个“大而全”的业务类。

## 状态生命周期原则

向量列自身使用 `SYS_VECTOR_COLUMNS_.STATUS` 表达整体可用性，并通过 `VectorColumnLifecycleService` 推进状态：

- `BUILDING`: 建表/provisioning 流程进行中，可被相同 `DEFINITION_HASH` 的请求恢复
- `ACTIVE`: 关系表结构已校验；若启用了自动 collection/index，则 Qdrant collection、alias、index 也已经校验为可用
- `FAILED`: 流程失败，普通 create 请求不会自动覆盖，需要后续显式 retry/repair 动作
- `DISABLED`: 已禁用，普通 create 请求拒绝复用

collection/index 的状态不应在业务代码中到处传裸字符串。当前已经将状态组合和流转规则收敛到 lifecycle 组件：

- collection lifecycle:
  - `BUILDING/CREATING`
  - `ACTIVE/READY`
  - `DEPRECATED/READY`
  - `BUILDING/FAILED`
  - `DEPRECATED/DROPPED`
- index lifecycle:
  - `OFFLINE/CREATING`
  - `ONLINE/READY`
  - `OFFLINE/READY`
  - `CANARY/READY`
  - `OFFLINE/REBUILDING`
  - `OFFLINE/FAILED`

设计约束：

- `ACTIVE` 是严格可用态，不用于表达“关系表已存在但向量引擎还没准备好”。Qdrant disabled 或 provisioning 未完成时，column 保持 `BUILDING`。
- 正常业务入口只能消费 `column=ACTIVE`、`collection=ACTIVE/READY`、`index=ONLINE/READY` 的资源；同步任务创建已通过 `VectorServingReadinessGuard` 做就绪校验。
- `SYS_VECTOR_COLUMNS_.DEFINITION_HASH` 用于区分幂等重试和冲突请求：同一 `schema.table.column` 下 hash 相同可恢复，hash 不同直接拒绝。
- 注册 collection/index 时必须校验 `servingState + status` 组合是否合法。
- 系统流程推进状态时，应调用 lifecycle service，例如 `markBuilding()`、`markActive()`、`markFailed()`、`markCreating()`、`markReady()`，不要直接在业务编排代码中写 `"ACTIVE"`、`"READY"` 这类裸字符串。
- DDL 层保留枚举和关键数值约束，防止绕过 API 时写入明显非法的数据。
- 后续如果增加重建索引、灰度切换、删除、重试、异步 provisioning，应继续扩展 lifecycle 组件，而不是把状态判断散落到多个 service。

## ID 生成策略

系统表主键由数据库 sequence `SYS_VECTOR_ID_SEQ` 统一生成，应用层通过 `IdGeneratorPort` 获取 ID，基础设施层由 JDBC sequence 实现。

这样设计的原因：

- 多个 sidecar 实例共享同一个控制面数据库，DB sequence 可以避免进程内自增在多实例/重启场景下产生主键冲突。
- 应用层只依赖 ID 生成端口，不直接依赖 JDBC 或具体数据库语法。
- ID 只保证唯一和递增，不承诺连续；sequence cache、事务回滚或失败请求都可能造成跳号，这是正常语义。

新库初始化脚本已经包含 `SYS_VECTOR_ID_SEQ`。如果是已有库，需要先统计所有系统表当前最大 ID，再用 `max_id + 1` 作为 sequence 起始值，不能直接从 1 开始创建。

## 当前架构边界与后续演进

当前代码是清晰的控制面 MVP。insert 链路已经改为 outbox 最终一致；provisioning 仍是同步编排。需要重点关注这些演进点：

- provisioning 目前是同步编排：建表/注册元数据后直接调用 Qdrant，再写 `READY` 或 `FAILED`。如果后续 provisioning 耗时变长，应改为异步 worker/outbox 模式。
- insert 当前以关系库为事实源：API 事务内写业务表和 `SYS_VECTOR_OUTBOX_EVENTS_`，worker 异步 upsert Qdrant。
- `collection/index` 已有 lifecycle，`sync job` 后续也应引入类似组件，统一 `PENDING -> RUNNING -> SUCCESS/FAILED/PAUSED/CANCELED` 的流转规则。
- DDL 当前以初始化脚本维护；系统表开始频繁变更后，建议引入 Flyway 或 Liquibase 管理版本迁移。
- 对外 API 后续最好区分“注册元数据”和“状态动作”。外部调用方不宜长期直接传状态字段，状态变化应通过明确动作接口完成。

## 系统表引用完整性

当前已经加入两层保护：

- 应用层 `VectorMetadataReferenceGuard`：注册 collection/index/payload field 以及写 sync progress/error 时，会校验父记录存在、父子归属一致，并校验 collection/index 的维度和 metric 与 column 定义匹配。
- 数据库层单列 FK：新库初始化脚本已为 collection/index/payload field/outbox/sync job/progress/error 加入父记录外键，防止绕过 API 写入不存在父记录的悬挂数据。

边界说明：

- `VectorMetadataReferenceGuard` 只处理引用完整性，不要求父资源已经 READY。create workflow 中 column 处于 `BUILDING` 时仍然可以注册 collection/index。
- `VectorServingReadinessGuard` 只处理业务可用性，sync job 等正常业务入口必须使用 `column=ACTIVE`、`collection=ACTIVE/READY`、`index=ONLINE/READY` 的资源。
- 当前 DDL 先落单列 FK；跨 column 归属一致性由应用层 Guard 兜底。已有库加 FK 前需要先清理存量悬挂数据，后续可再通过复合唯一键/复合 FK 逐步把归属一致性下沉到数据库。
- FK 默认使用 `RESTRICT / NO ACTION` 语义，不使用 `ON DELETE CASCADE`。删除/drop 应走显式 workflow。

当前打通的一条垂直链路：

- `POST /api/v1/vector-tables` 简化建表接口，面向业务调用方；默认注册 Qdrant collection/index，并可在 scalar column 上声明 payload key
- `POST /api/v1/vector-schemas/tables` 结构化建表（Altibase/H2）并自动注册向量列（可选自动注册默认 collection/index）
- `POST /api/v1/vector-columns` 注册向量列元数据（落表 `SYS_VECTOR_COLUMNS_`）
- `GET /api/v1/vector-columns` 查询已注册元数据
- `POST/GET /api/v1/vector-collections` 管理 `SYS_VECTOR_COLLECTIONS_`
- `POST/GET /api/v1/vector-indexes` 管理 `SYS_VECTOR_INDEXES_`
- `POST/GET /api/v1/vector-payload-fields` 管理 `SYS_VECTOR_PAYLOAD_FIELDS_`
- `GET /api/v1/vector-outbox-events` 查询 insert 到 Qdrant 的 outbox 事件状态
- `POST/GET /api/v1/vector-sync-jobs` 管理 `SYS_VECTOR_SYNC_JOBS_`
- `POST/GET /api/v1/vector-sync-progress` 管理 `SYS_VECTOR_SYNC_PROGRESS_`
- `POST/GET /api/v1/vector-sync-errors` 管理 `SYS_VECTOR_SYNC_ERRORS_`
- `POST /api/v1/vector-data/insert` 同步写入关系表；有 vector 时写入 outbox，Qdrant point 由 worker 异步 upsert

## API 使用建议

面向业务调用方，优先使用简化接口：

- 建表：`POST /api/v1/vector-tables`
- 写入：`POST /api/v1/vector-data/insert`

`/api/v1/vector-tables` 会隐藏控制面默认值。传入 `vectorColumn` 时，默认值为：

- `engineType=QDRANT`
- `ifNotExists=true`
- `autoRegisterCollection=true`
- `autoRegisterIndex=true`
- `defaultIndexProfileName=default`
- `elementType=FLOAT32`
- `metricType=COSINE`
- `syncMode=FULL_AND_INCREMENTAL`

`vectorColumn` 现在是可选项。不传 `vectorColumn` 时，接口只创建纯标量关系表，不注册 `SYS_VECTOR_COLUMNS_`、collection、index 或 payload field 元数据；此时 scalar column 不允许声明 `payloadKey`、`payloadSyncEnabled`、`payloadFieldType`。

传入 `vectorColumn` 时，scalar column 可以声明 `payloadKey`。声明后服务会自动注册 `SYS_VECTOR_PAYLOAD_FIELDS_`，后续 insert 请求里的 `payload` 就可以使用这个 key。`payloadSyncEnabled=false` 表示该字段只写关系表，不同步到 Qdrant payload。

`/api/v1/vector-schemas/tables`、`/api/v1/vector-columns`、`/api/v1/vector-collections`、`/api/v1/vector-indexes`、`/api/v1/vector-payload-fields` 保留为高级/控制面接口，用于自定义 engine、index profile、手工注册或排障修复，不建议普通业务链路直接依赖这些细粒度接口。

## Create Table 执行边界

- `POST /api/v1/vector-tables` 是推荐业务入口，本质上复用结构化建表编排。传入 `vectorColumn` 时会自动补齐默认 collection/index/payload metadata；不传 `vectorColumn` 时只创建纯标量关系表。
- `POST /api/v1/vector-schemas/tables` 会在关系库中执行真实 `CREATE TABLE`（通过 JDBC）
- 不是仅注册系统表；返回里 `ddlExecuted=true/false` 用于表示是否真的执行了 DDL
- 当 `ifNotExists=true` 且表已存在时，不再执行 DDL（`ddlExecuted=false`）
- 有 `vectorColumn` 时，create workflow 会先写 `BUILDING` 元数据，再执行 DDL/provisioning；DDL 隐式提交导致的半成功可通过相同 `DEFINITION_HASH` 的请求恢复
- 恢复 `BUILDING` 时不会简单认为“表存在就是成功”，而是通过 JDBC metadata 校验表名、主键、向量列、维度对应的存储长度、类型和 nullable
- 同一向量列如果传入不同定义，`DEFINITION_HASH` 不一致，会按冲突拒绝；`FAILED` 状态不会被普通 create 请求自动重试
- 无 `vectorColumn` 时没有 vector metadata 和 `DEFINITION_HASH`，只做关系表 DDL 执行和既有表结构校验。
- DDL 幂等恢复只处理可恢复失败：`OBJECT_ALREADY_EXISTS` 或执行结果不确定的超时/连接中断。语法错误、权限不足、类型不支持等 `NON_RETRYABLE` 错误不会被“表存在”轻易吞掉
- 服务内部使用 `DdlResult.CREATED_BY_THIS_ATTEMPT / ALREADY_EXISTS_AND_MATCHED` 表达 DDL 结果；API 里的 `ddlExecuted=false` 只表示本次请求没有新建表，但后续 metadata 修复、Qdrant provisioning 和状态推进仍会继续
- 向量列在 Altibase 中落为 `VARBYTE(N)`，`N=dimension*bytesPerElement`
  - `FLOAT32 -> 4` 字节
  - `FLOAT16 -> 2` 字节
  - `INT8 -> 1` 字节
- 若同时开启 `autoRegisterCollection` + `autoRegisterIndex`，会继续注册系统表并调用 Qdrant 创建物理资源

## Insert 执行边界

- `POST /api/v1/vector-data/insert` 对外不要求传 `columnId`。若传 `vectorColumn`，使用 `tenantId + schemaName + tableName + vectorColumn` 解析内部元数据；若不传 `vectorColumn`，表下必须只有一个已注册向量列。
- insert 支持三种形态：
  - 纯标量：不传 `vector`，只写关系表，不要求 READY collection，不调用 Qdrant。
  - 纯向量：传 `pk + vector`，写关系表向量列，并写入 outbox 事件。
  - 标量 + 向量：传 `pk + vector + payload`，写关系表并写入 outbox 事件。
- 有 `vector` 时只消费严格可用资源：`SYS_VECTOR_COLUMNS_.STATUS=ACTIVE`，且存在 `SERVING_STATE=ACTIVE / COLLECTION_STATUS=READY` 的 collection。无 `vector` 时只要求 column 未 DISABLED 且关系表存在。
- 请求里的 `payload` key 必须已经注册到 `SYS_VECTOR_PAYLOAD_FIELDS_` 且 `FIELD_STATUS=ACTIVE`。服务会用 `SOURCE_COLUMN_NAME` 写关系表列；worker 处理 outbox 时会重新读取 `SYNC_ENABLED=Y` 的字段构造 Qdrant payload。
- 关系表向量列按 `SYS_VECTOR_COLUMNS_.VECTOR_ENCODING` 编码：`FLOAT32_LE`、`FLOAT16_LE` 或 `INT8`。
- Qdrant 写入目标优先使用 collection alias；若 alias 为空，则直接写 collection name。
- insert API 不在请求事务里调用 Qdrant；有 vector 时返回 `vectorUpsertStatus=PENDING_OUTBOX` 和 `outboxEventId`。
- 关系库业务行和 outbox 事件在同一个 DB 事务内提交。Qdrant 是派生索引，由 worker 最终一致写入。
- worker 至少一次消费 outbox。Qdrant point id 使用稳定主键，重复 upsert 是幂等的。
- outbox 状态包括 `PENDING / PROCESSING / DONE / RETRYING / DEAD`；失败会记录 `RETRY_COUNT / NEXT_RETRY_AT / ERROR_CODE / ERROR_MESSAGE`。
- 如果 worker 未启用，vector insert 只会留下 `PENDING` outbox 事件，不会写 Qdrant。无 `vector` 时返回 `SKIPPED_SCALAR_ONLY`。

## Outbox Worker

`SYS_VECTOR_OUTBOX_EVENTS_` 用于承接 insert 到 Qdrant 的最终一致同步：

- insert 事务内写业务表和 outbox 事件。
- worker 定时扫描 `PENDING/RETRYING` 且到期的事件。
- worker 原子抢占事件为 `PROCESSING`，避免多实例重复处理同一行。
- worker 根据 `columnId + pk` 重新读取关系表向量列和 payload 标量列。
- worker 调用 Qdrant upsert，成功后标记 `DONE`。
- 失败会按指数退避转为 `RETRYING`；超过最大次数后标记 `DEAD`。
- 进程中断遗留的 `PROCESSING` 会在锁超时后释放回 `RETRYING`，再次消费仍依赖稳定 point id 保持幂等。

默认本地关闭 worker。需要消费 outbox 时同时启用 Qdrant 和 worker：

```bash
export SIDECAR_QDRANT_ENABLED=true
export SIDECAR_OUTBOX_WORKER_ENABLED=true
```

可选参数：

- `SIDECAR_OUTBOX_WORKER_FIXED_DELAY_MS`：调度间隔，默认 `1000`
- `SIDECAR_OUTBOX_WORKER_BATCH_SIZE`：每批事件数，默认 `50`
- `SIDECAR_OUTBOX_WORKER_MAX_RETRIES`：最大重试次数，默认 `5`
- `SIDECAR_OUTBOX_WORKER_INITIAL_RETRY_DELAY_MS`：首次重试延迟，默认 `1000`
- `SIDECAR_OUTBOX_WORKER_MAX_RETRY_DELAY_MS`：最大退避延迟，默认 `60000`
- `SIDECAR_OUTBOX_WORKER_LOCK_TIMEOUT_MS`：`PROCESSING` 锁超时，默认 `60000`

### Create Table 时的 Qdrant 侧行为

- 触发条件：`autoRegisterCollection=true` 且 `engineType=QDRANT`
- 若同时 `autoRegisterIndex=true`，会一起处理 index 元数据
- 当 `SIDECAR_QDRANT_ENABLED=true` 时，sidecar 会调用 Qdrant API：
  - `ensureCollection`
  - `ensureAlias`（默认 alias 存在时）
  - `ensureIndex`（仅当 `indexParamsJson` 非空；为空则 `SKIPPED_NOOP`）
- 当 `SIDECAR_QDRANT_ENABLED=false` 时，不调用 Qdrant API，只保留系统表注册与 `CREATING` 状态
- 任何 Qdrant 调用失败，`column` 与 `collection/index` 状态会写为 `FAILED` 并返回错误
- 成功后状态流转：
  - column：`BUILDING -> ACTIVE`
  - collection：`BUILDING/CREATING -> ACTIVE/READY`
  - index：`OFFLINE/CREATING -> ONLINE/READY`

## 推荐顺序（先建表）

1. 先在目标数据库执行 sidecar 系统表 DDL
2. 再启动 sidecar 服务
3. 最后调用 API（业务优先通过 `POST /api/v1/vector-tables` 建业务表并自动注册向量列、collection/index 和 payload fields）

## 启动方式

### 1) 默认本地启动（`local`）

默认 profile 为 `local`，使用 H2 内存库。

```bash
./mvnw spring-boot:run
```

### 2) 连接 Altibase 启动（`altibase`）

先执行 Altibase DDL（轻量产品版 8 张系统表）：

`src/main/resources/db/altibase/001_init_sidecar.sql`

在 Altibase 中建议业务表时间字段使用 `DATE`，并在调度/重试/排序场景使用 `*_EPOCH_MS BIGINT`，请使用兼容脚本：

`src/main/resources/db/altibase/001_init_sidecar_altibase_epoch.sql`

```bash
export SIDECAR_DB_URL='jdbc:Altibase://127.0.0.1:20300/mydb'
export SIDECAR_DB_USERNAME='sys'
export SIDECAR_DB_PASSWORD='manager'
./mvnw spring-boot:run -Dspring-boot.run.profiles=altibase
```

说明：

- `application-altibase.properties` 中驱动类配置为 `Altibase.jdbc.driver.AltibaseDriver`。
- 需要确保 Altibase JDBC 驱动已在运行时可加载（例如通过企业私库、`~/.m2` 本地安装或容器镜像内置）。
- Altibase profile 默认关闭了 `schema.sql` 自动初始化，建议由 DBA 预先建表。
- Altibase 建表脚本会创建 `SYS_VECTOR_ID_SEQ`；已有库迁移时需按存量最大 ID 调整 sequence 起始值。
- Altibase 建表脚本见：[001_init_sidecar.sql](src/main/resources/db/altibase/001_init_sidecar.sql)。
- Altibase `TIMESTAMP` 兼容脚本见：[001_init_sidecar_altibase_epoch.sql](src/main/resources/db/altibase/001_init_sidecar_altibase_epoch.sql)。
- JSON/快照字段长度预算（当前版本）：
  - collection/index 配置 JSON：`VARCHAR(4000)`
  - payload field 的 `indexParamsJson`：`VARCHAR(2000)`
  - sync `checkpointData`：`VARCHAR(2000)`（要求精简）
  - sync `payloadSnapshot`：`VARCHAR(2000)`（仅摘要/截断片段）
- Qdrant 物理资源创建默认关闭，如需启用：
  - `export SIDECAR_QDRANT_ENABLED=true`
  - `export SIDECAR_QDRANT_URL='http://127.0.0.1:6333'`
  - `export SIDECAR_QDRANT_API_KEY='<optional>'`

## Qdrant 说明

- 当前向量引擎管理通过统一抽象 `VectorEngineAdminPort` 路由，现有实现为 Qdrant
- `engineType` 传 `QDRANT` 时会进入 Qdrant 适配器
- `SIDECAR_QDRANT_ENABLED=false`：
  - 仍会创建关系库业务表和系统表注册
  - 但不会调用 Qdrant API
  - `collection/index` 状态保持在 `CREATING`（不会误标 `READY`）
- `SIDECAR_QDRANT_ENABLED=true`：
  - 会按顺序执行：`ensureCollection -> ensureAlias -> ensureIndex`
  - 当 `SIDECAR_OUTBOX_WORKER_ENABLED=true` 时，worker 会消费 outbox 并 upsert points
  - 资源命名默认规则：
    - collection：`{table}_{vectorColumn}_V1`
    - alias：`{table}_{vectorColumn}_ACTIVE`
- 当前默认 index 行为：
  - 若 `indexParamsJson` 为空，Qdrant 的 `ensureIndex` 会 `SKIPPED_NOOP`
  - 这表示不额外下发 HNSW patch 参数，使用 collection 侧默认配置
- 幂等策略：
  - collection：先查存在；存在则视为成功（`ALREADY_EXISTS`）
  - alias：先查询当前指向；已指向目标 collection 才视为成功；409 后会再次查询并校验，若指向其他 collection 则失败
  - index patch：重复调用为幂等更新
- 失败语义：
  - 任何 Qdrant API 失败都会把系统表状态写为 `FAILED`，并返回错误给调用方

## 本地快速验证

### 1) 简化建表（业务推荐）

```bash
curl -X POST 'http://localhost:8080/api/v1/vector-tables' \
  -H 'Content-Type: application/json' \
  -d '{
    "tenantId":"T1",
    "schemaName":"PUBLIC",
    "tableName":"DOC_INSERT_DEMO",
    "primaryKey":{"name":"ID","type":"BIGINT"},
    "scalarColumns":[
      {"name":"TITLE","type":"VARCHAR","length":200,"nullable":true,"payloadKey":"title"},
      {"name":"DOC_TYPE","type":"VARCHAR","length":50,"nullable":true,"payloadKey":"docType"},
      {"name":"SCORE","type":"DOUBLE","nullable":true,"payloadKey":"score","payloadSyncEnabled":false}
    ],
    "vectorColumn":{
      "name":"EMBEDDING",
      "dimension":3,
      "nullable":true
    }
  }'
```

这个接口默认使用 Qdrant、FLOAT32、COSINE、`FULL_AND_INCREMENTAL`，并自动注册默认 collection/index。带 `payloadKey` 的 scalar column 会自动注册为 payload field。

创建纯标量表时省略 `vectorColumn`，并且不要在 scalar column 上声明 payload metadata：

```bash
curl -X POST 'http://localhost:8080/api/v1/vector-tables' \
  -H 'Content-Type: application/json' \
  -d '{
    "tenantId":"T1",
    "schemaName":"PUBLIC",
    "tableName":"DOC_SCALAR_DEMO",
    "primaryKey":{"name":"ID","type":"BIGINT"},
    "scalarColumns":[
      {"name":"TITLE","type":"VARCHAR","length":200,"nullable":true},
      {"name":"DOC_TYPE","type":"VARCHAR","length":50,"nullable":true}
    ]
  }'
```

### 1.1) 高级结构化建表（控制面接口）

需要显式控制 engine/index/vector 参数时使用：

```bash
curl -X POST 'http://localhost:8080/api/v1/vector-schemas/tables' \
  -H 'Content-Type: application/json' \
  -d '{
    "tenantId":"T1",
    "schemaName":"PUBLIC",
    "tableName":"DOC_ADVANCED_DEMO",
    "engineType":"QDRANT",
    "ifNotExists":true,
    "autoRegisterCollection":true,
    "autoRegisterIndex":true,
    "defaultIndexProfileName":"default",
    "primaryKey":{"name":"ID","type":"BIGINT"},
    "scalarColumns":[
      {"name":"TITLE","type":"VARCHAR","length":200,"nullable":true},
      {"name":"CATEGORY","type":"VARCHAR","length":50,"nullable":true}
    ],
    "vectorColumn":{
      "name":"EMBEDDING",
      "dimension":768,
      "elementType":"FLOAT32",
      "metricType":"COSINE",
      "syncMode":"FULL_AND_INCREMENTAL",
      "nullable":true
    }
  }'
```

### 1.2) 校验 Qdrant 侧是否已创建资源

建表响应里会返回 `columnId / collectionId / indexId / tableName`。

```bash
# 1) 查系统表里的 collection 状态（应看到 ACTIVE + READY）
curl 'http://localhost:8080/api/v1/vector-collections?columnId=<columnId>'

# 2) 查系统表里的 index 状态（应看到 ONLINE + READY）
curl 'http://localhost:8080/api/v1/vector-indexes?columnId=<columnId>'

# 3) 查 Qdrant 物理 collection（将 <tableName> 替换为响应里的 tableName）
curl "http://127.0.0.1:6333/collections/<tableName>_EMBEDDING_V1"
```

### 2) 向量列注册接口（基础接口）

```bash
# 注册
curl -X POST 'http://localhost:8080/api/v1/vector-columns' \
  -H 'Content-Type: application/json' \
  -d '{
    "tenantId":"tenant_a",
    "schemaName":"public",
    "tableName":"doc",
    "pkColumn":"id",
    "vectorColumn":"embedding",
    "dimension":768,
    "metricType":"cosine",
    "syncMode":"full_and_incremental"
  }'

# 查询
curl 'http://localhost:8080/api/v1/vector-columns'
```

### 3) 最终一致 insert

有 vector 的 insert 前需要已有 ACTIVE column、READY collection，并按需注册 payload field。下面示例假设该向量列维度为 3；实际请求长度必须等于元数据中的 `dimension`。响应中的 `vectorUpsertStatus=PENDING_OUTBOX` 表示关系表已写入，Qdrant upsert 已进入 outbox 等待 worker 消费。

```bash
curl -X POST 'http://localhost:8080/api/v1/vector-data/insert' \
  -H 'Content-Type: application/json' \
  -d '{
    "tenantId":"T1",
    "schemaName":"PUBLIC",
    "tableName":"DOC_INSERT_DEMO",
    "vectorColumn":"EMBEDDING",
    "pk":100,
    "vector":[0.1,0.2,0.3],
    "payload":{
      "title":"first doc",
      "docType":"news",
      "score":9.5
    }
  }'
```

纯标量 insert 不传 `vector`，只写关系表。若表下只有一个已注册向量列，也可以不传 `vectorColumn`。

```bash
curl -X POST 'http://localhost:8080/api/v1/vector-data/insert' \
  -H 'Content-Type: application/json' \
  -d '{
    "tenantId":"T1",
    "schemaName":"PUBLIC",
    "tableName":"DOC_INSERT_DEMO",
    "pk":101,
    "payload":{
      "title":"scalar only",
      "docType":"news"
    }
  }'
```

## 依赖约束（Jackson / Boot 4）

- 当前项目基于 Spring Boot `4.x`，JSON 运行时由 Boot 自带的 `tools.jackson` 提供。
- 不要再额外引入 `com.fasterxml.jackson` `2.x` 依赖，否则可能出现运行时冲突（例如 `NoSuchFieldError: POJO`）。
- 在业务代码中，若需要直接使用 Jackson API，请统一使用 `tools.jackson.*` 包名。
