# 0528 Select 查询链路问题清单

本文记录当前 `select` / search 链路 review 后仍需收口的问题。

总体判断：

- 当前方案主方向正确。
- 核心语义已经基本守住：统一 Select API、Qdrant hit 回查 Altibase、delete/update stale hit 严格过滤、拒绝 topK 后置过滤、payload index ready gate、filter 类型白名单。
- 问题不是架构性推倒重来，而是若干工程细节会影响 filtered vector search 的语义稳定性。

## 1. 优先级总览

| 优先级 | 问题 | 风险类型 | 修复难度 |
| --- | --- | --- | --- |
| P0 | Qdrant payload 写入类型与 filter 查询类型可能不一致 | 查询语义错误 / 漏查 | 中等偏高 |
| P0 | `_sidecar_*` 数字 payload 解析不够严格 | 脏数据被截断 / 误匹配 | 中等 |
| P1 | `value` / `values` 歧义只在 `VECTOR_FIRST` 拒绝 | API 语义不一致 | 中等偏低 |
| P1 | raw `JsonNode` select 入口绕过部分 Bean Validation | 边界输入可能 NPE 或错误不一致 | 中等偏低 |
| P2 | `candidateLimit` 固定 over-fetch 可能导致返回不足 topK | 可解释性 / 召回不足 | 中等 |
| P2 | `malformedHitCount` 当前永远为 0 | 响应字段语义不清晰 | 低 |

## 2. P0：Qdrant payload 写入类型与 filter 查询类型可能不一致

### 现象

`SelectVectorDataService` 已经对 `VECTOR_FIRST` filter 做了严格类型标准化：

```text
STRING
INTEGER
LONG
DOUBLE
BOOLEAN
```

例如：

- `INTEGER` filter 会标准化成 `Integer`
- `LONG` filter 会标准化成 `Long`
- `DOUBLE` filter 会标准化成 `Double`

但 worker 写 Qdrant payload 时，目前是：

```text
Altibase row scalar value
-> qdrantPayload
-> Qdrant adapter putScalar
```

如果 JDBC 返回的数字类型是：

- `BigDecimal`
- `BigInteger`
- dialect-specific Number

adapter 对非 `Integer` / `Long` / `Float` / `Double` 的 `Number` 会走 `doubleValue()`。

### 风险

可能出现：

```text
Altibase BIGINT = 9007199254740993
Qdrant payload 写入 double 后精度丢失
select filter 使用 Long 精确值
Qdrant filter 匹配不到
```

或者：

```text
Altibase INTEGER 返回 BigDecimal("18")
Qdrant payload 写入 18.0
filter 写入 18
Qdrant match 语义依赖引擎类型处理，存在不确定性
```

这会让 `nearest + where` 出现漏查，属于查询语义风险。

### 建议修复

抽一个共享标准化组件，例如：

```java
PayloadScalarNormalizer
```

职责：

1. 根据 `VectorPayloadFieldMeta.fieldType()` 归一化成内部类型。
2. 对写入 Qdrant payload 的值做同样的类型标准化。
3. 对查询 filter 的值做同样的类型标准化。
4. 拒绝 Date / Decimal / CLOB / JSON / object / array 等 P0 不支持类型。

使用位置：

- `SelectVectorDataService.vectorFilters`
- `VectorOutboxWorker.qdrantPayload`

P0 支持矩阵保持：

| fieldType | payload 写入类型 | filter 类型 |
| --- | --- | --- |
| `VARCHAR` / `CHAR` / `STRING` / `KEYWORD` | `String` | `String` |
| `INTEGER` / `INT` | `Integer` | `Integer` |
| `BIGINT` / `LONG` | `Long` | `Long` |
| `DOUBLE` / `FLOAT` | `Double` | `Double` |
| `BOOLEAN` / `BOOL` | `Boolean` | `Boolean` |

### 建议测试

1. worker upsert `INTEGER` payload 时，Qdrant payload value 是 `Integer`。
2. worker upsert `BIGINT` payload 时，Qdrant payload value 是 `Long`。
3. worker upsert `DOUBLE` payload 时，Qdrant payload value 是 finite `Double`。
4. JDBC-like `BigDecimal("18")` 可标准化为 `Integer`。
5. `BigDecimal("18.0")` 对 INTEGER / LONG 拒绝。
6. 超出 int / long 范围拒绝。
7. unsupported field type 写入 payload 时进入 non-retryable failure。

## 3. P0：`_sidecar_*` 数字 payload 解析不够严格

### 现象

select 解析 Qdrant hit payload 时会读取：

```text
_sidecar_column_id
_sidecar_vector_index_version
```

当前逻辑对 `Number` 使用 `longValue()`。

### 风险

如果 Qdrant payload 被污染成：

```json
{
  "_sidecar_vector_index_version": 4.9
}
```

`longValue()` 会截断为 `4`，而不是报错。

如果 payload 是超大数字，也可能被截断或溢出。

### 建议修复

把 sidecar payload 数字解析改成 exact integer：

- 允许 `Integer` / `Long` / `BigInteger`
- 允许 `BigDecimal` 但必须 `scale <= 0`
- 拒绝 `Float` / `Double`
- 拒绝小数
- 拒绝非有限数字
- 拒绝超出 long 范围

建议方法：

```java
private long requiredExactLong(Map<String, Object> payload, String key)
```

### 建议测试

1. `_sidecar_vector_index_version = 1.2` 拒绝。
2. `_sidecar_column_id = 1.0` 拒绝。
3. `_sidecar_vector_index_version` 超出 long 范围拒绝。
4. `_sidecar_source_pk` 为非整数 number 时拒绝。
5. 正常 string number `"123"` 仍可接受。

## 4. P1：`value` / `values` 歧义只在 VECTOR_FIRST 拒绝

### 现象

`VECTOR_FIRST` 已经拒绝：

```json
{
  "field": "AGE",
  "op": "EQ",
  "value": 18,
  "values": [18, 19]
}
```

但 `RELATIONAL_ONLY` 目前直接把 `value` 和 `values` 传给关系查询。

结果：

- `EQ` 只用 `value`
- `IN` 只用 `values`

另一边会被静默忽略。

### 风险

同一个 Select API 在不同 plan 下语义不一致。

用户可能认为请求被严格校验，但关系查询路径悄悄接受了歧义输入。

### 建议修复

在 plan selection 前做通用 where carrier 校验：

```text
valueProvided && valuesProvided
=> FILTER_VALUE_AMBIGUOUS
```

这个校验应同时适用于：

- `RELATIONAL_ONLY`
- `VECTOR_FIRST`

### 建议测试

1. `RELATIONAL_ONLY EQ` 同时传 `value` / `values`，拒绝。
2. `RELATIONAL_ONLY IN` 同时传 `value` / `values`，拒绝。
3. `VECTOR_FIRST` 现有歧义测试继续通过。

## 5. P1：select raw JsonNode 入口绕过部分 Bean Validation

### 现象

为了保留 `value` / `values` 字段是否出现，controller 的 select 入口改成：

```java
select(@RequestBody JsonNode requestBody)
```

然后手动：

```java
objectMapper.treeToValue(...)
```

当前只补了 `tableName` 手动校验。

### 风险

原来 `@Valid` / `@NotBlank` / `@NotNull` 的部分校验不再自动执行。

例如需要关注：

- `where: [null]`
- `orderBy: [null]`
- `nearest.vector: null`
- `nearest.vector: [null]`
- `where.field: ""`
- `where.op: ""`

有些最终会在 service 层报错，有些可能 NPE 或错误信息不一致。

### 建议修复

方案 A：

保留 raw `JsonNode`，但注入 `jakarta.validation.Validator`，在 `treeToValue` 后手动 validate DTO。

方案 B：

自定义 select request parser，构造应用层 command，并集中完成字段校验和 presence 标记。

P0/P1 建议用方案 A，改动较小。

### 建议测试

1. `tableName` blank 拒绝。
2. `where: [null]` 拒绝。
3. `orderBy: [null]` 拒绝。
4. `nearest.vector: null` 拒绝。
5. `nearest.vector: [null]` 拒绝。
6. `where.field` blank 拒绝。
7. `where.op` blank 拒绝。

## 6. P2：candidateLimit 固定 over-fetch 可能返回不足 topK

### 现象

当前 `VECTOR_FIRST` 搜索：

```text
candidateLimit = min(topK * 3, 200)
```

然后在关系库回查后丢弃：

- deleted stale hit
- version stale hit

### 风险

如果前 200 个 Qdrant hit 大量 stale，但后面仍有 fresh hit，当前结果会少于 `topK`。

这不一定是错误，但需要明确语义。

### 可选修复

P0 可接受当前策略，但建议文档和 diagnostics 中明确：

```text
returnedRowCount < topK can happen after strict stale filtering
```

P1 可考虑循环 search：

```text
search candidate page
-> hydrate
-> filter stale
-> if fresh rows < topK and has more candidates, continue
```

但 Qdrant search 分页 / offset / scroll 语义需要单独设计，不建议 P0 仓促做。

### 建议测试

1. 所有 candidates 都 stale，返回 0，diagnostics 反映 stale count。
2. stale count 超过 fresh count，返回少于 topK。

## 7. P2：`malformedHitCount` 当前永远为 0

### 现象

response diagnostics 有：

```text
malformedHitCount
```

但当前 hit payload 缺字段或格式不一致时是 fail fast：

```text
INCONSISTENT_VECTOR_PAYLOAD
```

不会跳过 malformed hit，也不会计数。

### 风险

字段容易误导调用方，以为系统会统计 malformed hit。

### 建议修复

二选一：

1. 保持 fail fast，删除或废弃 `malformedHitCount`。
2. 改成 skip malformed hit，并计入 `malformedHitCount`。

P0 建议保持 fail fast，移除或标记该字段暂不使用。

### 建议测试

1. hit 缺 `_sidecar_source_pk` 返回 `INCONSISTENT_VECTOR_PAYLOAD`。
2. hit column id mismatch 返回 `INCONSISTENT_VECTOR_PAYLOAD`。
3. response diagnostics 不承诺 malformed skip 语义。

## 8. 已经覆盖较好的点

当前实现已经覆盖：

- 统一 `POST /api/v1/vector-data/select`。
- `RELATIONAL_ONLY` 与 `VECTOR_FIRST` plan 分离。
- `VECTOR_FIRST` 禁止 offset / orderBy。
- Qdrant hit 必须回查 Altibase。
- delete stale hit 丢弃。
- update stale hit 通过 `VECTOR_INDEX_VERSION` 丢弃。
- 按 Qdrant hit 顺序重排结果。
- `nearest + where` 不做关系库后置过滤。
- payload field 必须 `ACTIVE + SYNC_ENABLED=Y + IS_FILTERABLE=Y + IS_INDEXED=Y + PAYLOAD_INDEX_STATUS=CREATED`。
- vector filter 类型白名单。
- Qdrant disabled / collection not ready 明确失败。

## 9. 建议处理顺序

建议按下面顺序修：

1. P0：抽共享 payload scalar normalizer，统一写入和查询 filter 类型。
2. P0：sidecar payload exact long parsing。
3. P1：`value` / `values` 歧义校验前置到所有 plan。
4. P1：select controller 手动 Bean Validation 或自定义 parser。
5. P2：candidateLimit 返回不足 topK 的语义说明或补 diagnostics。
6. P2：处理 `malformedHitCount` 字段语义。

