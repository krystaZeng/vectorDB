# Vector DB 待解决问题

## 背景

本次检查范围覆盖 create table / insert / update / delete / outbox / Qdrant adapter / schema。

验证结果：`./mvnw verify` 通过，16 个单测 + 56 个集成测试。

## 总体评价

当前方案是“可用且方向正确”，但还不是“优秀/产品级”。

客观评分：7/10。

原因：

- outbox + source version + stale claim/resync 的基本框架是对的。
- create / insert / update / delete 链路完整。
- 最终一致性方向合理，不会显著拖慢关系库写入查询。
- 但边界状态、provisioning 验证、类型限制、指标隔离和真实环境测试还不够硬。

离产品化大概还有 30%-35% 的工程化工作。

MVP hardening 预计 1-2 周；如果要达到比较稳的生产级，预计 3-6 周，取决于真实 Altibase + Qdrant 验证范围。

## 主要问题

### 1. 高风险：payload-only update 可能制造 DEAD outbox

位置：

- `UpdateVectorDataService.java`
- `VectorOutboxWorker.java`

现象：

- 更新同步到 Qdrant 的 payload 时，只要 payload 命中同步字段，就会 enqueue UPSERT。
- 如果该行之前是 scalar-only insert，没有 vector，worker 重新读取行后会发现 vector 为空，并把事件标成 `DEAD`。

影响：

- 普通字段更新可能导致向量同步失败。
- 会污染失败率指标。
- 会让用户误以为 update 逻辑本身不稳定。

建议：

- payload-only 同步前检查当前行是否已有 vector。
- 如果没有 vector，要么拒绝该更新，要么只更新关系库且不写 outbox。
- `vectorSyncRequired` 时必须要求 vector column 处于 `ACTIVE`。

需要补测试：

- scalar-only insert 后更新 sync-enabled payload，不应产生 `DEAD`。
- vector column 非 `ACTIVE` 时更新 sync-enabled payload，应明确失败或跳过同步。

### 2. 高风险：Qdrant 已存在 collection 时没有校验配置

位置：

- `QdrantVectorEngineAdminAdapter.java`

现象：

- `ensureCollection` 只判断 collection 是否存在。
- 没有校验 dimension、distance、vector name。

影响：

- 如果已有同名但配置不一致的 collection，会被误判为 READY。
- 后续写入或查询才失败，问题更晚暴露。

建议：

- collection 已存在时读取配置并严格比对。
- dimension、distance、vector name 任一不一致，都应失败并记录 provisioning error。

需要补测试：

- 已存在同名但 dimension 不一致的 collection。
- 已存在同名但 distance 不一致的 collection。
- 已存在同名但 vector name 不一致的 collection。

### 3. 中高风险：手工注册 vector column 可写入脏元数据

位置：

- `RegisterVectorColumnService.java`

现象：

- 手工注册入口只做 trim/uppercase。
- 没有复用 create table 的 SQL identifier 校验。
- 可直接注册为 `ACTIVE`。

影响：

- metadata 可能显示可用，但数据 API 后续才拒绝。
- 控制面状态可能和真实表、真实 Qdrant 状态不一致。

建议：

- 注册入口统一使用标识符校验。
- `ACTIVE` 状态只能由 provisioning/verification 流程置位。
- 手工注册默认应进入 `BUILDING` 或 `DISABLED`，再由校验任务激活。

需要补测试：

- 非法 tableName / pkColumn / vectorColumn。
- 手工注册不存在的关系表。
- 手工注册不存在的 Qdrant collection。

### 4. 中风险：create table 成功不等于向量能力可用

位置：

- `CreateVectorTableService.java`

现象：

- 当 Qdrant provisioning disabled 时，column 会保留在 `BUILDING`，collection/index 会保留在 `CREATING`。
- API 仍然返回创建成功。

影响：

- 控制面语义是合理的，但产品侧容易误判“表已可用”。
- 实验或验收流程可能把 DDL 成功误认为向量链路成功。

建议：

- 响应中显式返回 `provisioningStatus`。
- 响应中增加类似 `usableForVectorWrites=false` 的明确状态。
- 提供 repair/retry operation。

需要补测试：

- Qdrant disabled 时 create table 的响应状态。
- 后续 insert vector 应明确失败，而不是隐式进入异常链路。

### 5. 中风险：outbox 异常分类过宽

位置：

- `JdbcVectorOutboxEventRepository.java`

现象：

- 所有 `DataIntegrityViolationException` 都被转成 “already exists”。
- enqueue merge 逻辑也可能把非唯一键错误当作 active key 冲突处理。

影响：

- FK、CHECK、长度超限等真实问题会被掩盖。
- 排查线上问题时会误导。

建议：

- 只对唯一键冲突走 merge。
- 其它完整性错误原样暴露。
- 最好按具体 constraint name 分类。

需要补测试：

- active key 唯一键冲突。
- FK 失败。
- CHECK 失败。
- source_pk / event_key 长度超限。

### 6. 中风险：类型、内存、溢出边界不足

位置：

- `CreateVectorTableService.java`
- `VectorValueEncoder.java`
- `QdrantVectorEngineAdminAdapter.java`
- `VectorPointIdNormalizer.java`
- `schema.sql`

现象：

- vector dimension 只限制到 `Integer.MAX_VALUE`，缺少产品级上限。
- encode vector 时会按 dimension 分配 heap，大维度请求可能打爆内存。
- 未知 `Number` 会转 `double`，BigDecimal/BigInteger 可能丢精度。
- string pk 空字符串没有明确拒绝。
- `SOURCE_PK VARCHAR(512)`、`EVENT_KEY VARCHAR(768)` 没有应用层长度校验。

影响：

- 大请求可能造成内存压力。
- payload 类型和数值精度可能污染实验数据。
- DB 层异常可能在较晚阶段才暴露。

建议：

- 增加最大 dimension、最大 payload、最大请求体配置。
- 对 payload 数值类型做白名单。
- BigDecimal/BigInteger 要么明确拒绝，要么按确定规则转换。
- 应用层校验 pk 和 event key 长度。
- 拒绝 blank string pk。

需要补测试：

- 超大 dimension。
- 超大 vector payload。
- BigDecimal / BigInteger payload。
- blank string pk。
- 超长 string pk。
- float16 边界值。

## 实验指标污染风险

### 1. Qdrant payload 中写入系统字段

当前 `_sidecar_source_version` 会写入每个 Qdrant point 的 payload。

风险：

- 如果实验统计 payload key 数量、字段分布、过滤条件、返回字段，会把 sidecar 字段算进去。

建议：

- 放入保留 namespace。
- 查询响应默认隐藏系统字段。
- 指标统计默认过滤 `_sidecar_*`。

### 2. retry/resync 会放大写入指标

风险：

- 重试、锁超时、resync 都可能导致重复 upsert/delete。
- Qdrant write QPS、写入延迟、失败率会被放大。

建议：

- 指标按 `sourceOp`、`retryCount`、`resync`、`eventStatus` 打标签。
- 实验报表区分 user write 和 sidecar retry write。

### 3. payload-only update bug 会污染失败率

风险：

- 无 vector 的行被 payload-only update 触发 UPSERT 后，会形成 DEAD 事件。

建议：

- 优先修复该问题。
- 在修复前，实验失败率统计需要过滤该类已知问题。

## 最终一致性延迟与查询性能

### 关系库写入/查询

当前设计不会显著拖慢关系库查询。

主链路增加的是：

- source version 更新。
- outbox 写入。
- 少量 metadata 查询。

这些开销主要影响写入延迟，不会直接拖慢普通关系库查询。

### Qdrant 可见性延迟

真正的延迟体现在 Qdrant 可见性：

- worker poll interval。
- batch size。
- Qdrant `wait=true`。
- retry/backoff。
- Qdrant 网络和服务端写入耗时。

当前 worker 偏串行，吞吐上来后同步延迟会明显增加。

建议：

- 支持批量 upsert/delete。
- 配置 worker 并发度。
- 增加同步 lag 指标。
- 明确最终一致性 SLA，例如 p95/p99 同步延迟。

### 向量查询

向量查询可能读到旧数据，这是最终一致性的预期结果。

产品侧需要明确：

- 写入成功只代表关系库提交成功。
- 向量检索可见需要等待 outbox 同步完成。
- 需要提供按 pk 查询同步状态或等待同步完成的接口。

## 需要补的测试清单

- scalar-only insert 后更新 sync-enabled payload。
- vector column 非 `ACTIVE` 时更新 sync-enabled payload。
- 已存在不兼容 Qdrant collection。
- Qdrant provisioning disabled 的 create table 响应。
- 手工注册非法 identifier。
- 手工注册不存在的关系表或 collection。
- 超大 dimension。
- 超大 vector payload。
- BigDecimal / BigInteger payload。
- blank string pk。
- 超长 string pk。
- `SOURCE_PK` / `EVENT_KEY` 长度超限。
- active key 唯一键冲突。
- outbox FK/CHECK 失败。
- worker lock timeout 后重复 claim。
- 慢 Qdrant 调用下的重试和 stale claim。
- 真实 Altibase 上的 VARBYTE、sequence、事务并发、timestamp 排序。
- H2 和 Altibase 行为差异测试。

## 更稳妥的实现方式

### 1. 明确 row vector state

update 时先判断当前行是否存在 vector。

推荐规则：

- vector provided：允许 UPSERT。
- vector not provided，但 sync payload changed 且 current row has vector：允许 UPSERT。
- vector not provided，sync payload changed 且 current row has no vector：拒绝或跳过 outbox。

### 2. 把 ACTIVE/READY 校验统一绑定到 vectorSyncRequired

只要本次操作需要同步 Qdrant，就必须要求：

- vector column `ACTIVE`。
- collection `READY`。
- Qdrant 配置与 metadata 一致。

### 3. 强化 provisioning idempotency

collection 已存在时不能只看名字。

必须校验：

- vector dimension。
- distance。
- vector name。
- collection alias 或 collection id。

### 4. 明确异常分类

建议拆分：

- duplicate active key。
- FK violation。
- CHECK violation。
- length overflow。
- Qdrant config mismatch。
- vector missing。
- payload type unsupported。

### 5. 限制资源上限

增加配置：

- max vector dimension。
- max vector bytes。
- max payload keys。
- max payload json size。
- max source pk length。
- max event key length。

### 6. 指标隔离

建议指标维度：

- user operation：insert/update/delete。
- sidecar operation：upsert/delete/resync/retry。
- event status：pending/processing/succeeded/dead。
- retry count。
- lag millis。
- stale claim count。

### 7. 增加真实环境验证

H2 测试不能完全代表 Altibase。

产品化前必须补：

- Altibase DDL 兼容性。
- Altibase VARBYTE 读写。
- Altibase transaction/rollback 行为。
- Altibase sequence 并发。
- Qdrant 真实 collection 配置读取。
- Qdrant 网络超时和慢请求。

## 下一步计划

### P0：先修正确性问题

1. 修复 payload-only update on null vector。
2. `vectorSyncRequired` 时统一要求 column `ACTIVE` 和 collection `READY`。
3. Qdrant collection 存在时校验配置一致性。
4. outbox 异常按 constraint 精确分类。

### P1：补测试

1. 补 update 边界测试。
2. 补 Qdrant collection mismatch 测试。
3. 补注册非法 metadata 测试。
4. 补类型、长度、超大 payload 测试。

### P2：产品化增强

1. 增加同步 lag 和 retry/resync 指标。
2. 查询响应隐藏 sidecar 系统字段。
3. create table 响应暴露 provisioning 状态。
4. 增加 repair/retry provisioning 接口。
5. 支持批量 Qdrant 写入和 worker 并发。

### P3：真实环境压测和验收

1. Altibase 集成测试。
2. Qdrant 慢请求和超时测试。
3. 最终一致性延迟压测。
4. 查询侧旧数据可见性验证。
5. 实验指标过滤规则验证。
