# vector-sidecar-service

最小可运行的向量 Sidecar 服务骨架，采用分层架构：

架构分层与目录约束见：[ARCHITECTURE.md](/Users/krystal/Downloads/vector-sidecar-service/ARCHITECTURE.md)

- `interfaces`: REST API（Controller + DTO）
- `application`: UseCase 与业务编排
- `domain`: 领域模型
- `infrastructure`: JDBC 持久化适配器

当前打通的一条垂直链路：

- `POST /api/v1/vector-schemas/tables` 结构化建表（Altibase/H2）并自动注册向量列（可选自动注册默认 collection/index）
- `POST /api/v1/vector-columns` 注册向量列元数据（落表 `SYS_VECTOR_COLUMNS_`）
- `GET /api/v1/vector-columns` 查询已注册元数据
- `POST/GET /api/v1/vector-collections` 管理 `SYS_VECTOR_COLLECTIONS_`
- `POST/GET /api/v1/vector-indexes` 管理 `SYS_VECTOR_INDEXES_`
- `POST/GET /api/v1/vector-payload-fields` 管理 `SYS_VECTOR_PAYLOAD_FIELDS_`
- `POST/GET /api/v1/vector-sync-jobs` 管理 `SYS_VECTOR_SYNC_JOBS_`
- `POST/GET /api/v1/vector-sync-progress` 管理 `SYS_VECTOR_SYNC_PROGRESS_`
- `POST/GET /api/v1/vector-sync-errors` 管理 `SYS_VECTOR_SYNC_ERRORS_`

## Create Table 执行边界

- `POST /api/v1/vector-schemas/tables` 会在关系库中执行真实 `CREATE TABLE`（通过 JDBC）
- 不是仅注册系统表；返回里 `ddlExecuted=true/false` 用于表示是否真的执行了 DDL
- 当 `ifNotExists=true` 且表已存在时，不再执行 DDL（`ddlExecuted=false`）
- 向量列在 Altibase 中落为 `VARBYTE(N)`，`N=dimension*bytesPerElement`
  - `FLOAT32 -> 4` 字节
  - `FLOAT16 -> 2` 字节
  - `INT8 -> 1` 字节
- 若同时开启 `autoRegisterCollection` + `autoRegisterIndex`，会继续注册系统表并调用 Qdrant 创建物理资源

### Create Table 时的 Qdrant 侧行为

- 触发条件：`autoRegisterCollection=true` 且 `engineType=QDRANT`
- 若同时 `autoRegisterIndex=true`，会一起处理 index 元数据
- 当 `SIDECAR_QDRANT_ENABLED=true` 时，sidecar 会调用 Qdrant API：
  - `ensureCollection`
  - `ensureAlias`（默认 alias 存在时）
  - `ensureIndex`（仅当 `indexParamsJson` 非空；为空则 `SKIPPED_NOOP`）
- 当 `SIDECAR_QDRANT_ENABLED=false` 时，不调用 Qdrant API，只保留系统表注册与 `CREATING` 状态
- 任何 Qdrant 调用失败，`collection/index` 状态会写为 `FAILED` 并返回错误
- 成功后状态流转：
  - collection：`BUILDING/CREATING -> ACTIVE/READY`
  - index：`OFFLINE/CREATING -> ONLINE/READY`

## 推荐顺序（先建表）

1. 先在目标数据库执行 sidecar 系统表 DDL
2. 再启动 sidecar 服务
3. 最后调用 API（可通过 `POST /api/v1/vector-schemas/tables` 建业务表并自动注册向量列）

## 启动方式

### 1) 默认本地启动（`local`）

默认 profile 为 `local`，使用 H2 内存库。

```bash
./mvnw spring-boot:run
```

### 2) 连接 Altibase 启动（`altibase`）

先执行 Altibase DDL（轻量产品版 7 张系统表）：

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
- Altibase 建表脚本见：[001_init_sidecar.sql](/Users/krystal/Downloads/vector-sidecar-service/src/main/resources/db/altibase/001_init_sidecar.sql)。
- Altibase `TIMESTAMP` 兼容脚本见：[001_init_sidecar_altibase_epoch.sql](/Users/krystal/Downloads/vector-sidecar-service/src/main/resources/db/altibase/001_init_sidecar_altibase_epoch.sql)。
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
  - 资源命名默认规则：
    - collection：`{table}_{vectorColumn}_V1`
    - alias：`{table}_{vectorColumn}_ACTIVE`
- 当前默认 index 行为：
  - 若 `indexParamsJson` 为空，Qdrant 的 `ensureIndex` 会 `SKIPPED_NOOP`
  - 这表示不额外下发 HNSW patch 参数，使用 collection 侧默认配置
- 幂等策略：
  - collection：先查存在；存在则视为成功（`ALREADY_EXISTS`）
  - alias：409 视为已存在
  - index patch：重复调用为幂等更新
- 失败语义：
  - 任何 Qdrant API 失败都会把系统表状态写为 `FAILED`，并返回错误给调用方

## 本地快速验证

### 1) 结构化建表（推荐）

```bash
curl -X POST 'http://localhost:8080/api/v1/vector-schemas/tables' \
  -H 'Content-Type: application/json' \
  -d '{
    "tenantId":"T1",
    "schemaName":"SYS",
    "tableName":"DOC_STORE_VT_DEMO",
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

### 1.1) 校验 Qdrant 侧是否已创建资源

`POST /api/v1/vector-schemas/tables` 的响应里会返回 `columnId / collectionId / indexId / tableName`。

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

## 依赖约束（Jackson / Boot 4）

- 当前项目基于 Spring Boot `4.x`，JSON 运行时由 Boot 自带的 `tools.jackson` 提供。
- 不要再额外引入 `com.fasterxml.jackson` `2.x` 依赖，否则可能出现运行时冲突（例如 `NoSuchFieldError: POJO`）。
- 在业务代码中，若需要直接使用 Jackson API，请统一使用 `tools.jackson.*` 包名。
