# 0528 Select 查询链路设计

本文记录 `vector-sidecar-service` 第一版 `select` 查询链路的实现思路。

核心目标：

- 对外只暴露一个 `select` API，用户不需要区分“查关系库”还是“查向量引擎”。
- 对内按请求生成执行计划：普通关系查询读 Altibase，向量检索读 Qdrant 后回查 Altibase。
- `SELECT` 是读链路，不写 outbox。
- 关系库仍是事实源，Qdrant 是最终一致派生索引。
- 第一版保持严格语义，不做会改变查询含义的 topK 后置过滤。

## 1. 当前上下文

现有文档已经确定：

- `INSERT / UPDATE / DELETE` 通过关系库事务 + outbox 异步同步 Qdrant。
- `SELECT` 不写 outbox。
- 普通关系查询直接读 Altibase。
- 向量检索先读 Qdrant，再按 point id / source pk 回查 Altibase。

现有代码缺口：

- `VectorEngineDataPort` 只有 `upsertPoint` / `deletePoint`，没有 search/query。
- `VectorDataRelationalPort` 只有单行 `findByPk`，没有结构化查询和批量按 pk 回查。
- `SYS_VECTOR_PAYLOAD_FIELDS_` 已有 `IS_INDEXED`，但它是声明字段，不是 Qdrant payload index 已创建并验证成功的运行态。
- 业务表已有 `ROW_VERSION`，但它不适合直接作为 search hit 一致性比较版本。

## 2. 对外 API

新增统一入口：

```http
POST /api/v1/vector-data/select
```

用户只表达一次查询。是否走向量检索由 `nearest` 子句决定。

### 2.1 普通关系查询示例

```json
{
  "tenantId": "DEFAULT",
  "schemaName": "PUBLIC",
  "tableName": "DOC",
  "vectorColumn": "EMBEDDING",
  "select": ["ID", "DOC_TYPE", "TITLE"],
  "where": [
    { "field": "DOC_TYPE", "op": "EQ", "value": "news" }
  ],
  "orderBy": [
    { "field": "ID", "direction": "DESC" }
  ],
  "limit": 20,
  "offset": 0
}
```

没有 `nearest` 时，执行计划为 `RELATIONAL_ONLY`，只读 Altibase。

### 2.2 向量检索示例

```json
{
  "tenantId": "DEFAULT",
  "schemaName": "PUBLIC",
  "tableName": "DOC",
  "vectorColumn": "EMBEDDING",
  "select": ["ID", "DOC_TYPE", "TITLE"],
  "where": [
    { "field": "DOC_TYPE", "op": "EQ", "value": "news" }
  ],
  "nearest": {
    "vector": [0.1, 0.2, 0.3],
    "topK": 10,
    "scoreThreshold": 0.75
  }
}
```

存在 `nearest` 时，执行计划为 `VECTOR_FIRST`：

```text
Qdrant search
-> 取 hit payload 中的 source pk / pk type / vector index version
-> 批量回查 Altibase
-> 删除 stale hit
-> 组装返回
```

## 3. 请求模型

建议新增：

- `interfaces/rest/request/SelectVectorDataRequest.java`
- `application/port/in/SelectVectorDataUseCase.java`

请求字段：

```java
record SelectVectorDataRequest(
        String tenantId,
        String schemaName,
        String tableName,
        String vectorColumn,
        List<String> select,
        List<SelectConditionRequest> where,
        List<OrderByRequest> orderBy,
        Integer limit,
        Integer offset,
        NearestRequest nearest
) {
}
```

条件模型：

```java
record SelectConditionRequest(
        String field,
        String op,
        Object value,
        List<Object> values
) {
}
```

第一版支持的 `op`：

- `EQ`
- `IN`
- `GT`
- `GTE`
- `LT`
- `LTE`
- `IS_NULL`
- `IS_NOT_NULL`

`where` 第一版只支持隐式 `AND`。`OR`、嵌套表达式、全文检索后续再扩展。

向量检索子句：

```java
record NearestRequest(
        List<Double> vector,
        Integer topK,
        Double scoreThreshold
) {
}
```

限制建议：

- `limit` 默认 50，最大 200。
- `nearest.topK` 默认 10，最大 100。
- `offset` 只允许 `RELATIONAL_ONLY` 使用。
- `orderBy` 第一版只允许 `RELATIONAL_ONLY` 使用。
- `VECTOR_FIRST` 的返回顺序固定为 Qdrant score 排序后再经过 stale 过滤。

## 4. 响应模型

建议新增：

- `interfaces/rest/response/SelectVectorDataResponse.java`

响应：

```java
record SelectVectorDataResponse(
        String executionPlan,
        String consistency,
        List<SelectRowResponse> rows,
        SelectDiagnosticsResponse diagnostics
) {
}

record SelectRowResponse(
        Object pk,
        Double score,
        Long vectorIndexVersion,
        Map<String, Object> values
) {
}

record SelectDiagnosticsResponse(
        int qdrantHitCount,
        int relationalRowCount,
        int staleDeletedHitCount,
        int staleVersionHitCount,
        int malformedHitCount,
        int returnedRowCount
) {
}
```

`executionPlan`：

- `RELATIONAL_ONLY`
- `VECTOR_FIRST`

`consistency`：

- `RELATIONAL_STRONG_READ`
- `EVENTUAL_VECTOR_INDEX_STRICT`

`score`：

- `RELATIONAL_ONLY` 为 `null`。
- `VECTOR_FIRST` 为 Qdrant 返回的相似度分数。

## 5. 执行计划选择

```text
if nearest == null:
    RELATIONAL_ONLY
else:
    VECTOR_FIRST
```

第一版不引入多个用户 API。Controller 只负责 DTO 转换：

```text
VectorDataController.select()
-> SelectVectorDataUseCase.select(command)
-> SelectVectorDataResponse.from(result)
```

Application service 根据 command 生成 plan。

## 6. 元数据解析

P0 先限定 select 目标为已经注册 vector metadata 的表。

原因：

- 当前没有独立的 `SYS_VECTOR_TABLES_` 保存纯关系表的 pk column、系统列和投影规则。
- 现有 Data API 都通过 `tenantId + schemaName + tableName + vectorColumn` 解析 `VectorColumnMeta`。
- 如果表有多个向量列，不传 `vectorColumn` 会产生歧义。

解析规则：

1. 默认 `tenantId=DEFAULT`。
2. 默认 `schemaName=PUBLIC`。
3. 如果传了 `vectorColumn`，按完整 identity 查 `VectorColumnMeta`。
4. 如果没传 `vectorColumn`，按 table identity 查：
   - 0 条：P0 拒绝，提示表没有 vector metadata。
   - 1 条：使用该 vector column。
   - 多条：拒绝，要求显式传 `vectorColumn`。

纯标量表查询可以作为 P1：

- 方案 A：新增 `SYS_VECTOR_TABLES_` 管理 table metadata。
- 方案 B：关系查询请求显式传 `pkColumn`，但这会扩大 API 面，不建议 P0 做。

## 7. Projection 规则

`select` 表达用户希望返回的业务字段。

默认 projection：

- 返回 pk column。
- 返回已注册 `IS_RETURNABLE=Y` 且 `FIELD_STATUS=ACTIVE` 的 payload source columns。
- 不返回 vector bytes。
- 不返回 sidecar 系统字段。

禁止用户显式返回：

- vector column，例如 `EMBEDDING`。
- `ROW_VERSION`。
- `VECTOR_INDEX_VERSION`。
- 未来所有 `_sidecar_*` 虚拟字段。

如果后续确实需要返回 vector，应设计显式开关：

```json
{ "includeVector": true }
```

第一版不支持，避免大 payload 和意外泄露。

## 8. RELATIONAL_ONLY 链路

链路：

```text
SelectVectorDataService.select()
-> resolve VectorColumnMeta
-> validate projection / where / orderBy / limit / offset
-> VectorDataRelationalPort.queryRows()
-> map rows
-> return RELATIONAL_ONLY
```

新增 port：

```java
List<RelationalRow> queryRows(QueryRowsCommand command);

record QueryRowsCommand(
        String schemaName,
        String tableName,
        String pkColumn,
        List<String> selectColumns,
        List<Condition> conditions,
        List<OrderBy> orderBy,
        int limit,
        int offset
) {
}
```

SQL 生成要求：

- 不接收原始 SQL。
- 表名、列名继续使用现有 identifier 校验。
- 值全部使用 PreparedStatement 参数绑定。
- `limit` 必须有最大值保护。
- `orderBy` 字段必须是合法业务字段。

返回：

```text
executionPlan = RELATIONAL_ONLY
consistency = RELATIONAL_STRONG_READ
diagnostics.*HitCount = 0
```

## 9. VECTOR_FIRST 链路

链路：

```text
SelectVectorDataService.select()
-> resolve VectorColumnMeta
-> require column ACTIVE
-> find ACTIVE/READY collection
-> validate nearest vector
-> validate all where conditions can be pushed down to Qdrant
-> VectorEngineDataPort.searchPoints()
-> parse sidecar payload from hits
-> VectorDataRelationalPort.findRowsByPks()
-> stale delete / stale version filtering
-> preserve Qdrant score order
-> return VECTOR_FIRST
```

### 9.1 Readiness

`VECTOR_FIRST` 必须要求：

- `SYS_VECTOR_COLUMNS_.STATUS = ACTIVE`
- 存在 `SYS_VECTOR_COLLECTIONS_`：
  - `SERVING_STATE = ACTIVE`
  - `COLLECTION_STATUS = READY`

如果后续引入 index readiness，也应纳入：

- default vector index `ONLINE/READY`
- payload index `CREATED`

### 9.2 Qdrant search port

扩展 `VectorEngineDataPort`：

```java
SearchPointsResult searchPoints(SearchPointsCommand command);

record SearchPointsCommand(
        String collectionName,
        String vectorName,
        List<Float> vector,
        int limit,
        Double scoreThreshold,
        List<VectorFilterCondition> filters,
        boolean withPayload
) {
}

record SearchPointsResult(
        List<SearchPointHit> hits
) {
}

record SearchPointHit(
        Object pointId,
        double score,
        Map<String, Object> payload
) {
}
```

Qdrant 具体 REST 端点由 adapter 内部封装。测试需要锁定：

- unnamed vector 查询 JSON。
- named vector 查询 JSON。
- filter JSON。
- hit `id / score / payload` 解析。

### 9.3 候选数

因为 stale hit 会被丢弃，Qdrant 查询 limit 不应简单等于 `topK`。

建议：

```text
candidateLimit = min(topK * candidateMultiplier, maxCandidateLimit)
```

默认：

- `candidateMultiplier = 3`
- `maxCandidateLimit = 200`

最终返回前 `topK` 个通过一致性检查的结果。如果不足 `topK`，返回较少结果，并通过 diagnostics 暴露丢弃数量。

## 10. 一致性语义

`VECTOR_FIRST` 是最终一致读，但第一版采用严格返回策略：

- Qdrant hit 对应关系行不存在：丢弃。
- Qdrant hit 版本与关系行向量索引版本不一致：丢弃。
- Qdrant hit 缺少必要 sidecar payload：查询失败或丢弃并计数，P0 建议失败。

### 10.1 Delete stale hit

场景：

```text
Qdrant hit.sourcePk = 100
Altibase row not found
```

说明：

- 关系行已删除。
- Qdrant delete outbox 可能尚未完成。

处理：

```text
drop hit
staleDeletedHitCount + 1
```

### 10.2 Update stale hit

场景：

```text
Qdrant hit.vectorIndexVersion = 4
Altibase row.vectorIndexVersion = 5
```

说明：

- 关系行仍存在。
- Qdrant 参与相似度计算的是旧向量或旧 filter payload。
- 如果直接返回 Altibase 最新行，`score` 对应旧向量，结果语义会变脏。

处理：

```text
drop hit
staleVersionHitCount + 1
```

### 10.3 不能直接使用 ROW_VERSION

不要直接比较：

```text
hit.sourceVersion != relationalRow.ROW_VERSION
```

原因：

- 当前 update 会给每次业务更新分配新的 `SOURCE_VERSION`，并写入业务表 `ROW_VERSION`。
- 关系-only 更新也可能推进 `ROW_VERSION`。
- 但关系-only 更新没有改变 Qdrant 中用于相似度和过滤的向量索引内容。
- 如果用 `ROW_VERSION` 比较，会误杀仅更新非同步字段的行。

因此 P0 需要新增“向量索引版本”。

## 11. 向量索引版本

建议新增业务表系统列：

```sql
VECTOR_INDEX_VERSION BIGINT
```

语义：

- 表示当前关系行中“已被 Qdrant search 语义消费的内容”的版本。
- 只在会影响 Qdrant search 结果的写操作中更新。

影响 Qdrant search 结果的内容：

- vector column。
- `SYNC_ENABLED=Y` 的 payload field。
- delete / reinsert。

不影响 Qdrant search 结果的内容：

- 非同步标量字段。
- `SYNC_ENABLED=N` 的 payload field。
- 只用于关系查询返回、不参与 Qdrant filter 的字段。

### 11.1 Insert

有 vector：

```text
ROW_VERSION = sourceVersion
VECTOR_INDEX_VERSION = sourceVersion
enqueue UPSERT
```

无 vector：

```text
ROW_VERSION = sourceVersion
VECTOR_INDEX_VERSION = null
不写 outbox
```

### 11.2 Update

更新 vector，或更新 `SYNC_ENABLED=Y` payload 且当前行已有 vector：

```text
ROW_VERSION = sourceVersion
VECTOR_INDEX_VERSION = sourceVersion
enqueue UPSERT
```

只更新非同步字段：

```text
ROW_VERSION = sourceVersion
VECTOR_INDEX_VERSION 不变
不写 outbox
```

更新 `SYNC_ENABLED=Y` payload 但当前行没有 vector：

```text
ROW_VERSION = sourceVersion
VECTOR_INDEX_VERSION 保持 null
不写 outbox
```

### 11.3 Delete

删除关系行前仍分配新的 `SOURCE_VERSION` 并写 tombstone outbox。

关系行删除后没有 `VECTOR_INDEX_VERSION` 可读。search 回查时找不到关系行，按 delete stale hit 丢弃。

### 11.4 Worker upsert

worker 重新读取关系行时，需要读取：

- vector bytes
- return/sync payload source columns
- `VECTOR_INDEX_VERSION`

写入 Qdrant payload：

```text
_sidecar_vector_index_version = row.VECTOR_INDEX_VERSION
```

search 返回后，Select 链路使用该 payload 与 Altibase 回查行的 `VECTOR_INDEX_VERSION` 比较。

## 12. Qdrant sidecar payload

当前 worker 已写 `_sidecar_source_version`。Select P0 需要补充稳定回查和一致性判断字段。

建议保留 payload keys：

```text
_sidecar_tenant_id
_sidecar_column_id
_sidecar_source_pk
_sidecar_pk_value_type
_sidecar_source_version
_sidecar_vector_index_version
```

用途：

- `_sidecar_source_pk`：稳定回查关系库。
- `_sidecar_pk_value_type`：把 source pk 恢复成 JDBC 参数类型。
- `_sidecar_column_id`：防御性校验，避免 collection/alias 配置错误时误读。
- `_sidecar_vector_index_version`：防止 update stale hit。
- `_sidecar_source_version`：保留 worker/outbox 诊断价值。

约束：

- 用户 payload key 不允许以 `_sidecar_` 开头。
- Register payload field 时应拒绝 `_sidecar_*`。
- Worker 写 payload 前也要做 reserved key collision 检查。

如果 search hit 缺少这些必需字段，说明 Qdrant 中存在旧格式或外部写入的 point。

P0 建议：

```text
fail fast: INCONSISTENT_VECTOR_PAYLOAD
```

比静默跳过更利于发现迁移和数据污染问题。

## 13. Filter 下推规则

`nearest + where` 时，所有 `where` 条件必须能下推到 Qdrant。

不允许：

```text
先查全局 topK
再回查 Altibase
再用关系库 where 过滤
```

原因：

```text
过滤后的最相似 topK
!=
全局 topK 中碰巧满足过滤条件的若干条
```

后置过滤会漏掉真正应该返回的结果，因为符合过滤条件的高相似点可能没有进入全局 topK。

### 13.1 下推条件

每个 `where.field` 必须映射到 `SYS_VECTOR_PAYLOAD_FIELDS_` 中的一条 metadata：

```text
FIELD_STATUS = ACTIVE
AND SYNC_ENABLED = Y
AND IS_FILTERABLE = Y
AND IS_INDEXED = Y
AND PAYLOAD_INDEX_STATUS = CREATED
```

其中 `PAYLOAD_INDEX_STATUS` 是 P0 需要新增的运行态字段。

如果任一条件不满足：

```text
reject request
```

错误建议：

```text
FILTER_NOT_PUSHABLE: field DOC_TYPE is not backed by a ready Qdrant payload index
```

### 13.2 字段与操作类型

`RELATIONAL_ONLY` 可以按关系库能力继续支持较完整的 `where` 操作。

但 `VECTOR_FIRST` 中的 `nearest + where` 必须更保守。原因是这些条件会被翻译成 Qdrant filter，
一旦字段类型、操作符和值类型映射不严格，就可能出现：

- Qdrant filter 语义和 Altibase where 语义不一致。
- 字符串字段被错误下推 range。
- decimal / date / json 等复杂类型被隐式转成 double 或 string，导致边界精度丢失。
- payload 中实际写入的类型和查询 filter 类型不一致，造成漏查。

P0 对 `VECTOR_FIRST` 只支持以下白名单：

| payload field type | allowed ops |
| --- | --- |
| `VARCHAR` / `CHAR` / `STRING` / `KEYWORD` | `EQ`, `IN` |
| `INTEGER` / `INT` / `BIGINT` / `LONG` | `EQ`, `IN`, `GT`, `GTE`, `LT`, `LTE` |
| `DOUBLE` / `FLOAT` | `EQ`, `IN`, `GT`, `GTE`, `LT`, `LTE` |
| `BOOLEAN` | `EQ` |

P0 中 `VECTOR_FIRST` 暂不支持 `IS_NULL` / `IS_NOT_NULL`。如果业务确实需要空值过滤，
需要先明确 Altibase null、Qdrant missing payload key、Qdrant null payload value 三者的等价关系，
再单独扩展。

暂不支持：

- `LIKE`
- full-text
- nested payload
- array contains
- geo filter
- OR / nested boolean
- Date / Timestamp
- Decimal / BigDecimal / Number with precision-scale
- CLOB / JSON / object / array payload

这些需要单独设计 Qdrant filter 映射和关系库兼容语义。

## 14. Payload index 状态

现有 `SYS_VECTOR_PAYLOAD_FIELDS_` 有：

```text
IS_INDEXED
INDEX_PARAMS_JSON
```

但 `IS_INDEXED=Y` 只能表达“期望建立索引”，不能表达 Qdrant 物理索引已经创建成功。

建议新增字段：

```sql
PAYLOAD_INDEX_STATUS VARCHAR(16) NOT NULL DEFAULT 'MISSING',
PAYLOAD_INDEX_VERIFIED_AT TIMESTAMP,
PAYLOAD_INDEX_ERROR_CODE VARCHAR(64),
PAYLOAD_INDEX_ERROR_MESSAGE VARCHAR(1000)
```

状态：

```text
MISSING   -- 未创建或未验证
CREATING  -- 正在创建
CREATED   -- 已创建并验证通过
FAILED    -- 创建或验证失败
```

DDL check：

```sql
CHECK (PAYLOAD_INDEX_STATUS IN ('MISSING', 'CREATING', 'CREATED', 'FAILED'))
```

### 14.1 Lifecycle

新增：

- `VectorPayloadFieldLifecycle`
- `VectorPayloadFieldLifecycleService`

允许的状态流转：

```text
MISSING -> CREATING -> CREATED
MISSING -> CREATING -> FAILED
FAILED -> CREATING -> CREATED
CREATED -> MISSING
```

`CREATED -> MISSING` 用于 drift detection 发现 Qdrant 侧索引丢失。

### 14.2 Admin port

扩展 `VectorEngineAdminPort`：

```java
EnsureResult ensurePayloadIndex(EnsurePayloadIndexCommand command);

EnsureResult verifyPayloadIndex(VerifyPayloadIndexCommand command);

record EnsurePayloadIndexCommand(
        String collectionName,
        String payloadKey,
        String fieldType,
        String indexParamsJson
) {
}
```

Create table 自动注册 payload field 时：

1. 创建 collection / alias。
2. 对 `IS_INDEXED=Y` 的 payload field 调用 `ensurePayloadIndex`。
3. 成功后标记 `PAYLOAD_INDEX_STATUS=CREATED`。
4. 失败后标记 `FAILED`，并把错误写入 error fields。

手工注册 payload field 时：

- 如果 collection 已 ready 且 `IS_INDEXED=Y`，可以同步 ensure。
- 如果 collection 未 ready，先保存 `MISSING`，后续通过 verify/repair action 创建。

### 14.3 为什么必须纳入查询前置条件

Qdrant 支持没有 payload index 的过滤，但性能和资源消耗不可控。Qdrant 官方文档建议：

- 对要过滤的 payload 字段创建 payload index。
- 最好在数据写入前创建 payload index。
- 如果后建 payload index，可能需要 rebuild HNSW 才能充分利用 filter-aware graph。
- strict mode 可以阻止未索引字段过滤，避免生产延迟尖刺。

参考：

- https://qdrant.tech/documentation/search/filtering/
- https://qdrant.tech/documentation/manage-data/indexing/
- https://qdrant.tech/documentation/faq/qdrant-fundamentals/

因此 Select P0 要求：

```text
filtered vector search 必须基于 CREATED payload index
```

## 15. 关系库批量回查

新增 port：

```java
List<RelationalRow> findRowsByPks(FindRowsByPksCommand command);

record FindRowsByPksCommand(
        String schemaName,
        String tableName,
        String pkColumn,
        List<PkValue> pkValues,
        List<String> selectColumns,
        String vectorIndexVersionColumn
) {
}

record RelationalRow(
        Object pk,
        Long vectorIndexVersion,
        Map<String, Object> values
) {
}
```

实现要求：

- 使用 `WHERE pk IN (?, ?, ...)`。
- 大批量 pk 分 chunk 查询。
- 返回后 application 层按 Qdrant hit 顺序重排。
- 不依赖数据库返回顺序。
- 缺失 pk 表示 delete stale hit。

`VECTOR_FIRST` 回查时必须额外读取：

- pk column
- projection columns
- `VECTOR_INDEX_VERSION`

但响应默认不暴露 `VECTOR_INDEX_VERSION`，只放在 `vectorIndexVersion` 调试字段中。

## 16. Qdrant adapter 实现要点

`QdrantVectorEngineAdminAdapter` 当前同时实现 admin 和 data port。第一版可以继续扩展它，也可以拆出 `QdrantVectorEngineDataAdapter`，但不要改变 application 对 port 的依赖方向。

search 请求需要支持：

- collection alias 作为 search target。
- unnamed vector。
- named vector。
- score threshold。
- filter must 条件。
- `with_payload=true`，至少返回 `_sidecar_*` 字段。

search hit 解析：

- `id` -> `pointId`
- `score` -> `score`
- `payload` -> `Map<String,Object>`

必要校验：

- id 必须是 number 或 string。
- score 必须是 finite number。
- payload 必须包含 required sidecar keys。

## 17. 数据迁移与兼容

新增字段：

业务表：

```text
VECTOR_INDEX_VERSION
```

系统表：

```text
SYS_VECTOR_PAYLOAD_FIELDS_.PAYLOAD_INDEX_STATUS
SYS_VECTOR_PAYLOAD_FIELDS_.PAYLOAD_INDEX_VERIFIED_AT
SYS_VECTOR_PAYLOAD_FIELDS_.PAYLOAD_INDEX_ERROR_CODE
SYS_VECTOR_PAYLOAD_FIELDS_.PAYLOAD_INDEX_ERROR_MESSAGE
```

已有数据迁移：

1. 给已由 sidecar 创建的业务表增加 `VECTOR_INDEX_VERSION`。
2. 对存在 vector 的行，理论上可先置为当前 `ROW_VERSION`，但这只在 Qdrant 已同步到同版本时才严格正确。
3. 更稳妥的迁移方式是触发 full resync：
   - 重新读取关系表当前行。
   - 写 Qdrant 新 sidecar payload。
   - 写入 `VECTOR_INDEX_VERSION`。
4. payload index 状态默认 `MISSING`。
5. 对 `IS_INDEXED=Y` 的字段执行 ensure/verify，成功后标记 `CREATED`。

没有完成 full resync 前：

- `VECTOR_FIRST` 可以拒绝查询。
- 或返回 `VECTOR_INDEX_NOT_READY`。

不要在旧格式 Qdrant point 上悄悄降级为非严格查询。

## 18. 错误策略

建议错误码：

```text
SELECT_REQUEST_INVALID
VECTOR_COLUMN_NOT_FOUND
VECTOR_COLUMN_AMBIGUOUS
VECTOR_COLUMN_NOT_ACTIVE
VECTOR_COLLECTION_NOT_READY
VECTOR_DIMENSION_MISMATCH
FILTER_NOT_PUSHABLE
PAYLOAD_INDEX_NOT_READY
INCONSISTENT_VECTOR_PAYLOAD
VECTOR_INDEX_VERSION_MISSING
RELATIONAL_QUERY_FAILED
VECTOR_SEARCH_FAILED
```

`FILTER_NOT_PUSHABLE` 是产品语义错误，不是系统异常。

`INCONSISTENT_VECTOR_PAYLOAD` 和 `VECTOR_INDEX_VERSION_MISSING` 多数是迁移、外部写入或 drift 问题，应进入运维排查。

## 19. 测试计划

### 19.1 Application / Controller IT

1. `RELATIONAL_ONLY` 按 where 查询成功。
2. `RELATIONAL_ONLY` 默认隐藏 vector column / `ROW_VERSION` / `VECTOR_INDEX_VERSION`。
3. 多 vector column 且未传 `vectorColumn`，返回明确错误。
4. `VECTOR_FIRST` 正常返回 Qdrant score + Altibase row。
5. Qdrant hit 对应关系行已删除，丢弃并增加 `staleDeletedHitCount`。
6. Qdrant hit version 小于 Altibase `VECTOR_INDEX_VERSION`，丢弃并增加 `staleVersionHitCount`。
7. `nearest + where` 使用未注册 payload field，拒绝。
8. `nearest + where` 使用 `SYNC_ENABLED=N` field，拒绝。
9. `nearest + where` 使用 `PAYLOAD_INDEX_STATUS != CREATED` field，拒绝。
10. vector dimension 不匹配，拒绝。
11. search hit 缺少 `_sidecar_source_pk`，返回 `INCONSISTENT_VECTOR_PAYLOAD`。

### 19.2 Qdrant adapter test

1. unnamed vector search JSON 正确。
2. named vector search JSON 正确。
3. `EQ / IN / range` filter JSON 正确。
4. score threshold 正确传递。
5. hit payload 解析正确。
6. Qdrant HTTP error 转为 `BizException`。

### 19.3 DDL / Migration test

1. 新建 vector table 自动包含 `VECTOR_INDEX_VERSION`。
2. payload field DDL 包含 payload index 状态字段。
3. check constraint 覆盖 payload index status。
4. H2 与 Altibase 初始化脚本保持一致。

### 19.4 Worker test

1. vector insert 写 `VECTOR_INDEX_VERSION` 并 enqueue。
2. worker upsert 写 `_sidecar_vector_index_version`。
3. relation-only update 不改变 `VECTOR_INDEX_VERSION`。
4. sync-enabled payload update 改变 `VECTOR_INDEX_VERSION` 并 enqueue。
5. reserved payload key 冲突时拒绝注册或进入 non-retryable failure。

## 20. 实现顺序建议

### Step 1：补版本与 sidecar payload

- DDL 增加 `VECTOR_INDEX_VERSION`。
- create table workflow 自动创建该列。
- insert/update service 按规则维护该列。
- worker upsert 写 `_sidecar_*` payload。
- 禁止用户 payload key 使用 `_sidecar_` 前缀。

这是 search 一致性前提。

### Step 2：补 payload index lifecycle

- 系统表增加 payload index 状态字段。
- registry repository/domain/response 补字段。
- admin port 增加 ensure/verify payload index。
- create workflow 对 `IS_INDEXED=Y` 字段创建 Qdrant payload index。
- 查询前置条件使用 `PAYLOAD_INDEX_STATUS=CREATED`。

这是 filtered vector search 性能前提。

### Step 3：补查询端口

- `VectorDataRelationalPort.queryRows`
- `VectorDataRelationalPort.findRowsByPks`
- `VectorEngineDataPort.searchPoints`
- Qdrant adapter search 实现。

### Step 4：实现 Select use case

- `SelectVectorDataUseCase`
- `SelectVectorDataService`
- plan selection
- projection / where 校验
- stale hit 严格过滤
- diagnostics。

### Step 5：接 REST API

- `SelectVectorDataRequest`
- `SelectVectorDataResponse`
- `VectorDataController.select`
- Controller IT。

## 21. P0 明确不做

P0 不做：

- 原始 SQL passthrough。
- topK 后置过滤。
- OR / nested boolean condition。
- 纯标量表通用查询。
- 返回 vector bytes。
- 跨多个 vector column 的融合查询。
- hybrid text search。
- rerank。
- join。
- group by / aggregation。
- offset pagination for vector search。

这些能力都可能有价值，但会显著扩大语义面和测试面。

## 22. 当前实现仍存在的风险

截至 2026-05-28 当前代码已经覆盖：

- Qdrant hit 与 Altibase row 的 `VECTOR_INDEX_VERSION` 严格一致性判断。
- Qdrant hit 通过 `_sidecar_source_pk` / `_sidecar_pk_value_type` 回查，不依赖 point id 反推关系主键。
- 批量回查后按 Qdrant hit 顺序重排返回。
- `nearest` 查询遇到 Qdrant disabled 或 collection not ready 时失败，不降级为关系查询。
- `nearest + where` 拒绝未注册、未同步、未索引或 `PAYLOAD_INDEX_STATUS != CREATED` 的 payload field。

但仍有以下风险需要继续补齐。

### 22.1 风险 2：where filter 类型映射仍偏宽松

当前代码已经把 `fieldType` 传到 `SearchFilterCondition`，但还没有在 select use case 中执行
`fieldType + op + value type` 白名单校验。

当前 Qdrant adapter 的 `putScalar` / `addScalar` 会接受 `String`、`Number`、`Boolean` 等标量，
但它只负责 JSON 序列化，不应该承担查询语义判断。

需要补齐：

- 字段类型归一化。
- 操作符白名单。
- `value` / `values` 的类型校验与标准化。
- 拒绝 Date / Decimal / CLOB / JSON / object / array。
- 拒绝 string range、boolean range、mixed-type IN、empty IN、null range value。

否则 filtered vector search 可能出现 Qdrant filter 和 Altibase where 语义不一致。

### 22.2 风险 3：绕过 sidecar API 的关系库更新无法自动同步 payload

当前 sidecar API 内部链路已经处理：

- insert 写关系行 payload 字段。
- update 普通 payload 字段时，如果 point 已存在，会 enqueue upsert。
- update vector 字段时会 upsert vector + 最新 payload。
- delete 会 enqueue delete point。
- worker upsert 时重新从 Altibase 读取所有 `ACTIVE + SYNC_ENABLED=Y` 字段，而不是只使用本次请求 payload。

但如果业务系统绕过 sidecar API 直接修改 Altibase 业务列，目前没有 trigger / CDC / database outbox 捕获这些更新。

影响：

```text
Altibase business column changed
Qdrant payload unchanged
nearest + where filter sees stale payload
```

P0 需要明确系统边界：

- 生产写入必须经 sidecar API；或
- 引入 Altibase trigger / CDC / source table outbox，把直接写关系库的变更也转成 vector outbox event。

在没有 trigger / CDC 前，文档和 API 契约要明确：`nearest + where` 的一致性只覆盖 sidecar 管理的写入路径。

### 22.3 Payload index lifecycle 尚未闭环

当前已经新增 `PAYLOAD_INDEX_STATUS` 并用于查询门禁，但自动创建、验证、漂移检测还需要继续实现。

缺口：

- 注册 payload field 后没有自动调用 Qdrant create payload index。
- 没有 verify job 定期确认 Qdrant 侧 payload index 仍存在。
- `FAILED` / `MISSING` 到 `CREATED` 的恢复流程还没有管理 API。

影响：

- 功能语义不会错，因为 `nearest + where` 会拒绝非 `CREATED` 字段。
- 但运维上需要手工把字段标记成 `CREATED`，否则 filtered vector search 不可用。

## 23. 风险 2 详细解决方案：严格 filter 类型系统

### 23.1 设计目标

风险 2 的核心不是 Qdrant filter JSON 怎么拼，而是必须先定义一个 sidecar 自己的 filter 类型系统。

目标：

- `SelectVectorDataService` 负责查询语义校验。
- `QdrantVectorEngineAdminAdapter` 只负责把已经校验过的 filter 转成 Qdrant JSON。
- 所有不在 P0 白名单内的组合都 fail fast。
- 错误要在调用 Qdrant 前暴露，避免线上出现“功能可用但结果不可信”。

建议错误码：

```text
FILTER_TYPE_NOT_SUPPORTED
FILTER_OP_NOT_SUPPORTED
FILTER_VALUE_TYPE_MISMATCH
FILTER_VALUE_REQUIRED
FILTER_VALUE_AMBIGUOUS
```

### 23.2 类型归一化

新增内部枚举：

```java
enum PayloadFilterType {
    STRING,
    INTEGER,
    LONG,
    DOUBLE,
    BOOLEAN
}
```

metadata `fieldType` 到内部类型的映射：

| metadata fieldType | internal type |
| --- | --- |
| `VARCHAR`, `CHAR`, `STRING`, `KEYWORD` | `STRING` |
| `INTEGER`, `INT` | `INTEGER` |
| `BIGINT`, `LONG` | `LONG` |
| `DOUBLE`, `FLOAT` | `DOUBLE` |
| `BOOLEAN`, `BOOL` | `BOOLEAN` |

P0 直接拒绝：

- `DATE`
- `TIMESTAMP`
- `DECIMAL`
- `BIGDECIMAL`
- `NUMBER`
- `CLOB`
- `JSON`
- `OBJECT`
- `ARRAY`
- 空 `fieldType`
- 未知 `fieldType`

`NUMBER` 不建议 P0 支持，因为没有 precision / scale 信息时无法判断应该按 long、double 还是 decimal 处理。

### 23.3 操作符矩阵

`VECTOR_FIRST` 使用以下矩阵：

| internal type | allowed ops |
| --- | --- |
| `STRING` | `EQ`, `IN` |
| `INTEGER` | `EQ`, `IN`, `GT`, `GTE`, `LT`, `LTE` |
| `LONG` | `EQ`, `IN`, `GT`, `GTE`, `LT`, `LTE` |
| `DOUBLE` | `EQ`, `IN`, `GT`, `GTE`, `LT`, `LTE` |
| `BOOLEAN` | `EQ` |

`IS_NULL` / `IS_NOT_NULL` 暂不进入 P0 vector filter。

原因：

- Altibase `NULL`、Qdrant payload key missing、Qdrant payload value null 不是天然等价。
- worker 当前会把所有 sync payload key 写入 payload，但历史 point、外部写入 point、旧格式 point 可能缺 key。
- 空值语义一旦混用，filtered topK 会很难解释。

### 23.4 value / values 校验规则

通用规则：

- `EQ` / range op 使用 `value`。
- `IN` 使用 `values`。
- `IN.values` 不能为空。
- `IN.values` 建议 P0 限制最大长度，例如 `100`，避免生成过大的 Qdrant filter。
- `value` 和 `values` 不能同时出现。
- 不允许 object / array / map。
- 不做 string-number 隐式转换。

如果请求同时传入：

```json
{
  "field": "AGE",
  "op": "EQ",
  "value": 18,
  "values": [18, 19]
}
```

或：

```json
{
  "field": "AGE",
  "op": "IN",
  "value": 18,
  "values": [18, 19]
}
```

都必须拒绝：

```text
FILTER_VALUE_AMBIGUOUS: value and values cannot both be provided for field AGE
```

实现注意：

- 如果只看 Java 反序列化后的 `Object value`，无法区分 JSON 字段缺失和显式 `"value": null`。
- 如果要严格按“字段是否出现”判断，REST 层需要保留字段 presence。
- 推荐在 controller 解析 `where` 条件时基于 raw `JsonNode` 检查 `has("value")` / `has("values")`，
  再构造 use case command。
- 如果 P0 暂不改 request 解析模型，至少要拒绝 `value != null && values != null`，同时所有需要 `value`
  的 op 都拒绝 `value == null`。
- 更严谨的 P0 做法是给 use case condition 增加 `valueProvided` / `valuesProvided` 两个布尔字段。

`STRING`：

- `EQ.value` 必须是非 null string。
- `IN.values` 每个元素必须是非 null string。
- 不做数字到字符串的隐式转换。
- 不支持 range。

`INTEGER`：

- 值必须是整数型 number。
- 允许 JSON integer 映射成 `Integer` / `Long` / `BigInteger` / `scale <= 0` 的 `BigDecimal`。
- 拒绝 `Double` / `Float`，即使值看起来是 `1.0`，也不当作整数。
- 拒绝小数，例如 `1.2`。
- 拒绝字符串数字，例如 `"1"`。
- 拒绝超出 32-bit integer 范围的值。

`LONG`：

- 值必须是整数型 number。
- 允许 JSON integer 映射成 `Integer` / `Long` / `BigInteger` / `scale <= 0` 的 `BigDecimal`。
- 拒绝 `Double` / `Float`，即使值看起来是 `1.0`，也不当作 long。
- 拒绝小数，例如 `1.2`。
- 拒绝字符串数字，例如 `"1"`。
- 拒绝超出 64-bit long 范围的值。

`DOUBLE`：

- 值必须是 number。
- 必须是 finite number。
- 拒绝 `NaN` / `Infinity`。
- 拒绝 string number，例如 `"1.23"`。

`BOOLEAN`：

- `EQ.value` 必须是 boolean。
- 不支持 `IN`，避免 `IN [true, false]` 退化成无意义过滤。
- 不支持 range。

数字类型必须基于 `Number` 做判断，不要只匹配具体 Java 类型。

JSON 反序列化后，数字可能是：

- `Integer`
- `Long`
- `Double`
- `BigDecimal`
- `BigInteger`

建议规则：

```text
1       -> 可以作为 INTEGER / LONG / DOUBLE
1L      -> 如果范围合法，可以作为 INTEGER / LONG / DOUBLE
1.0     -> 可以作为 DOUBLE；拒绝作为 INTEGER / LONG
1.2     -> 可以作为 DOUBLE；拒绝作为 INTEGER / LONG
"1"     -> 全部数字类型都拒绝
```

对 `BigDecimal`：

- `BigDecimal("1")` 可作为 INTEGER / LONG，前提是范围合法。
- `BigDecimal("1.0")` P0 建议拒绝作为 INTEGER / LONG，避免 scale 语义不透明。
- `BigDecimal("1.2")` 拒绝作为 INTEGER / LONG。
- 所有 `BigDecimal` 转 DOUBLE 前必须确认 finite，并接受可能的 double 精度语义。

### 23.5 Qdrant filter 映射

校验后再映射：

```text
STRING EQ      -> match.value: string
STRING IN      -> match.any: [string]

INTEGER EQ     -> match.value: integer
INTEGER IN     -> match.any: [integer]
INTEGER RANGE  -> range.gt/gte/lt/lte: integer

LONG EQ        -> match.value: long
LONG IN        -> match.any: [long]
LONG RANGE     -> range.gt/gte/lt/lte: long

DOUBLE EQ      -> match.value: double
DOUBLE IN      -> match.any: [double]
DOUBLE RANGE   -> range.gt/gte/lt/lte: double

BOOLEAN EQ     -> match.value: boolean
```

适配器层不再根据 Java runtime type 推断业务语义，只序列化 service 已经标准化后的值。

Adapter 仍保留最后防线：

- 拒绝 object / map / array。
- 拒绝 `NaN` / `Infinity`。
- 拒绝 null 被错误传入 `match.value` 或 range。
- 拒绝 `IN` 的 `match.any` 中出现 null。

但 adapter 不做 `fieldType + op` 业务矩阵判断，也不根据原始 metadata 类型推断语义。

### 23.6 代码改造点

建议改造 `SelectVectorDataService.vectorFilters`：

```java
private List<SearchFilterCondition> vectorFilters(...) {
    ...
    PayloadFilterType type = normalizePayloadFilterType(field.fieldType());
    String op = normalizeOp(condition.op());
    validateVectorFilterOp(type, op, field);
    NormalizedFilterValue normalizedValue = normalizeFilterValue(type, op, condition);
    return new SearchFilterCondition(
            field.payloadKey(),
            op,
            normalizedValue.value(),
            normalizedValue.values(),
            type.name()
    );
}
```

为了精确实现 `value` / `values` 不能同时出现，建议轻微调整 use case command：

```java
record SelectCondition(
        String field,
        String op,
        Object value,
        List<Object> values,
        boolean valueProvided,
        boolean valuesProvided
) {
}
```

REST controller 解析 `where` 时不要只依赖 typed DTO 的 null 值，而是检查原始 JSON：

```java
boolean valueProvided = conditionNode.has("value");
boolean valuesProvided = conditionNode.has("values");
```

然后再把 JSON value 转成 Java scalar / list。

如果希望保持现有 DTO 不变，则只能实现弱版本：

```java
if (condition.value() != null && condition.values() != null) {
    throw new BizException("FILTER_VALUE_AMBIGUOUS: ...");
}
```

这个弱版本无法发现 `"value": null` 与 `"values": [...]` 同时出现，因此不推荐作为最终 P0。

新增方法：

- `normalizePayloadFilterType(String fieldType)`
- `validateVectorFilterOp(PayloadFilterType type, String op, VectorPayloadFieldMeta field)`
- `normalizeFilterValue(PayloadFilterType type, String op, SelectCondition condition)`
- `rejectAmbiguousValueCarrier(SelectCondition condition)`
- `requireSingleValue(...)`
- `requireValues(...)`
- `normalizeInteger(...)`
- `normalizeLong(...)`
- `normalizeDouble(...)`
- `normalizeBoolean(...)`
- `normalizeString(...)`
- `isIntegralNumber(Number number)`
- `rejectFloatLikeInteger(Number number, String fieldName)`
- `rejectUnsupportedScalar(Object value, String fieldName)`

`SearchFilterCondition.fieldType` 应传标准化后的 `type.name()`：

```text
STRING
INTEGER
LONG
DOUBLE
BOOLEAN
```

不要继续传 metadata 原始值：

```text
VARCHAR / CHAR / KEYWORD / INT / BIGINT / FLOAT / BOOL
```

P0 中 `PayloadFilterType` 可以先作为 `SelectVectorDataService` 的 private enum。
如果 P1 发现其他 use case 或 adapter 也需要共享，再抽到 application port 或独立 data model。

`QdrantVectorEngineAdminAdapter` 可保留 `putScalar` / `addScalar`，但它们的异常只作为防御式保护；
正常情况下不应该再由 adapter 发现类型错误。

### 23.7 测试补充

Select controller / service 测试：

1. `STRING EQ` 成功。
2. `STRING IN` 成功。
3. `STRING GT` 拒绝，错误包含 `FILTER_OP_NOT_SUPPORTED`。
4. `INTEGER GT` 成功。
5. `INTEGER EQ 1.2` 拒绝，错误包含 `FILTER_VALUE_TYPE_MISMATCH`。
6. `INTEGER EQ "1"` 拒绝，错误包含 `FILTER_VALUE_TYPE_MISMATCH`。
7. `INTEGER EQ 1.0` 拒绝，避免 Double 伪整数。
8. `LONG IN [1, 2]` 成功。
9. `DOUBLE LTE 1.2` 成功。
10. `DOUBLE value "1.2"` 拒绝。
11. `BOOLEAN EQ true` 成功。
12. `BOOLEAN IN [true]` 拒绝。
13. `IN []` 拒绝。
14. `IN values` 超过 100 个，拒绝。
15. `IN ["a", 1]` 拒绝。
16. `EQ` 同时传 `value` 和 `values`，拒绝，错误包含 `FILTER_VALUE_AMBIGUOUS`。
17. `IN` 同时传 `value` 和 `values`，拒绝，错误包含 `FILTER_VALUE_AMBIGUOUS`。
18. `DATE EQ "2026-05-28"` 拒绝，错误包含 `FILTER_TYPE_NOT_SUPPORTED`。
19. `DECIMAL GT 1.23` 拒绝。
20. `IS_NULL` / `IS_NOT_NULL` 在 `VECTOR_FIRST` 下拒绝。
21. `nearest` 不存在时，`DATE` / `IS_NULL` 不触发 vector filter 校验，由 `RELATIONAL_ONLY` 按关系库逻辑处理。

Qdrant adapter 测试：

1. 已标准化 string match JSON 正确。
2. 已标准化 integer / long / double range JSON 正确。
3. boolean match JSON 正确。
4. adapter 防御式拒绝 object / map / array。
5. adapter 防御式拒绝 `NaN` / `Infinity`。

不需要在 adapter 测试复杂业务类型矩阵，那是 use case 层职责。

### 23.8 实施顺序

建议按以下顺序改，避免把 adapter 和 use case 职责混在一起：

1. 调整 request / use case condition，保留 `valueProvided` / `valuesProvided`。
2. 在 REST controller 中基于 raw JSON where condition 设置 presence flags。
3. 在 `SelectVectorDataService.vectorFilters` 中加入类型归一化、op 白名单和 value 标准化。
4. 将 `SearchFilterCondition.fieldType` 改为传 `PayloadFilterType.name()`。
5. 保持 `QdrantVectorEngineAdminAdapter` 的 filter JSON 映射基本不变，只补最后防线。
6. 增加 controller / service 测试覆盖所有 P0 类型矩阵。
7. 增加 adapter 测试确认标准化后的 JSON 输出。

主要修改文件：

- `SelectVectorDataService.java`
- `SelectVectorDataUseCase.java`
- `SelectVectorDataRequest.java`
- `VectorDataController.java`
- `VectorSelectControllerIT.java`
- `QdrantVectorEngineAdminAdapterTest.java`

原则上不需要大改 `QdrantVectorEngineAdminAdapter.java`。如需修改，也只补防御式 scalar 检查。

建议验证：

```bash
./mvnw -q -DskipTests compile
./mvnw -q -Dtest=VectorSelectControllerIT test
./mvnw -q -Dtest=QdrantVectorEngineAdminAdapterTest test
SIDECAR_QDRANT_ENABLED=false ./mvnw -q test
```

### 23.9 后续 Date 支持条件

Date / Timestamp 不进入 P0。

后续若要支持，需要先完成：

1. payload field metadata 明确 `DATE_EPOCH_MS` 或 `TIMESTAMP_EPOCH_MS`。
2. worker 写 payload 时统一转换成 epoch millis long。
3. select request 约定输入格式，例如 ISO-8601 string。
4. use case 在调用 Qdrant 前把输入转换成 epoch millis。
5. Altibase relational-only 查询也使用同一套时间边界语义。

没有这些前置条件时，不允许把 Date 当 string 或 double 下推。

## 24. 最终设计结论

第一版 Select API 应该保持一个对外入口：

```text
POST /api/v1/vector-data/select
```

内部执行计划：

```text
RELATIONAL_ONLY:
    Altibase query

VECTOR_FIRST:
    Qdrant search with fully pushable filters
    -> Altibase batch lookup
    -> delete stale filtering
    -> vector-index-version stale filtering
```

必须坚持的边界：

- `select` 不写 outbox。
- Qdrant hit 不能直接返回，必须回查 Altibase。
- Qdrant hit 必须通过 `VECTOR_INDEX_VERSION` 一致性检查。
- `nearest + where` 只接受可下推到 Qdrant 且 payload index ready 的字段。
- 不做 topK 后置过滤。

这样可以让用户获得一个统一的查询 API，同时保留关系库事实源和最终一致派生索引的系统边界。
