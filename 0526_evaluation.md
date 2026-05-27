# 0526 Evaluation

## 总体结论

当前方案是“技术方向正确、架构合理、已经达到可验证 MVP 的写入同步框架”，但还不是完整产品化方案。

客观打分：**7.8 / 10**。

它不是“只是能跑”的方案。核心一致性模型、分层、状态机、outbox、Qdrant readiness 校验都已经比较扎实；但离产品化还差查询链路、真实环境压测、可观测性、迁移治理、漂移检测和运维闭环。

## 1. 技术正确性

评分：**8 / 10**

### 正确的点

1. 最终一致性模型合理。

   关系库是事实源，Qdrant 是派生索引，insert / update / delete 通过 outbox 异步同步。

   核心链路：

   - `InsertVectorDataService`
   - `UpdateVectorDataService`
   - `DeleteVectorDataService`

2. outbox 设计方向正确。

   当前已经具备：

   - `ACTIVE_KEY` 合并同一个 logical point。
   - `SOURCE_VERSION` 防止旧 worker 覆盖新状态。
   - `NEEDS_RESYNC` 处理 `PROCESSING` 期间的新变更。
   - worker 至少一次投递，Qdrant upsert / delete 通过稳定 point id 保持幂等。

3. update 链路已经补强。

   现在先 `FOR UPDATE` 读取行状态，再判断是否需要 Qdrant sync。

   payload-only update 且当前行没有 vector 时，不再 enqueue 无意义 outbox，也不会污染失败率指标。

4. Qdrant collection readiness 校验已经补强。

   现在不是只看 collection 名字是否存在，而是校验：

   - vector mode：unnamed / named
   - vector name
   - dimension
   - distance
   - alias 是否指向目标 collection

5. 手工注册 vector column 的状态流转更合理。

   手工注册不再直接进入 `ACTIVE`，而是：

   ```text
   BUILDING -> verify-and-activate -> ACTIVE
   ```

   激活前会校验真实表结构、ready collection、Qdrant 实际配置。

### 需要补的技术点

1. `insert` 对显式空 vector `[]` 当前会被当成未传 vector。

   建议改为：

   ```text
   INVALID_VECTOR_VALUE
   ```

   否则用户显式传错参数时，系统会静默降级为 scalar-only insert。

2. `delete` 当前总是要求 `ACTIVE + READY collection`。

   如果删除的是 scalar-only / no-vector 行，更稳妥的语义是：

   - 先读行 vector 状态。
   - 无 vector：只删除关系行，不 enqueue Qdrant delete。
   - 有 vector：要求 ready collection，并 enqueue delete。

3. `FLOAT32 / FLOAT16` 编码需要补范围校验。

   当前校验了 `Double.isFinite`，但超大 `Double` 转 `float` 后可能变成 `Infinity`。

   建议补：

   - FLOAT32：转 float 后仍必须 finite。
   - FLOAT16：超出 half-float 可表示范围时拒绝或明确截断策略。

4. `UINT64` 语义目前偏窄。

   代码里实际只支持 Java signed long 正数范围，也就是接近 `UINT63`。

   建议二选一：

   - 改名为 `INT64_POSITIVE` / `LONG_POSITIVE`
   - 或支持完整 unsigned 64 的字符串表达

## 2. 现有架构正确性

评分：**8.5 / 10**

当前架构方向是正确的。

整体依赖方向清楚：

```text
interfaces -> application -> port -> infrastructure
domain -> JDK only
```

### 合理点

1. Registry / Data / Sync / System 分层清楚。

2. 一张系统表一条垂直链路，避免大而全 service。

3. 状态流转通过 lifecycle service 收敛，不完全散落在业务代码里。

4. Qdrant 适配器被包在 port 后面，后续替换向量引擎或 mock 测试成本较低。

5. DDL 有基础约束：

   - 主键
   - 唯一键
   - 外键
   - 状态 check
   - 关键索引

### 架构不足

1. 状态仍有不少裸字符串。

   例如：

   - `ACTIVE`
   - `READY`
   - `PENDING`
   - `DEAD`

   后续应继续收敛为 enum / lifecycle。

2. provisioning 仍是同步编排。

   Qdrant 慢或不稳定时，create table 请求会被拖长，半成功恢复也更复杂。

   长期建议改成 provisioning operation / outbox。

3. readiness 错误结构化不足。

   当前主要落到 `remark`，缺少：

   - `verified_at`
   - `readiness_error_code`
   - `readiness_error_message`

4. 查询链路还没形成独立模块。

   当前写入同步框架较完整，但产品闭环还缺 select / vector search。

## 3. 工作量评估

以下按一个熟悉当前代码的工程师估算。

### P0 剩余小问题

预计：**2 - 4 人日**

包括：

- insert 空 vector 显式拒绝。
- delete no-vector skip。
- float range 校验。
- readiness 错误字段基础结构化。
- 少量补充测试。

### 达到可验证 MVP

预计：**1 - 2 周**

包括：

- 最小 select / search 链路。
- 真实 Altibase + Qdrant 环境 E2E。
- 基础指标：
  - outbox backlog
  - dead count
  - retry count
  - sync latency
  - worker throughput
- README 操作手册。
- 故障演示和恢复流程。

### 早期产品化

预计：**4 - 8 周**

包括：

- 数据库迁移治理。
- 鉴权与租户隔离。
- 指标和告警。
- 压测。
- 多实例 worker 验证。
- drift detection。
- `reverify` 接口。
- 异步 provisioning。
- 运维接口。

### 生产级稳定产品

预计：**8 - 12 周+**

取决于是否需要支持：

- 多租户强隔离。
- 在线 schema 变更。
- 灰度 collection / index。
- 批量 backfill。
- 查询 SLA。
- 大规模 outbox 清理和归档。

## 4. 风险点

### 高风险

1. 查询链路缺失。

   当前写入同步链路比较完整，但产品最终一定要验证：

   - 写入后如何查。
   - 查到什么。
   - 延迟多久可见。
   - Qdrant 返回 point 后如何回查关系库组装结果。

2. 真实 Altibase + Qdrant 兼容性不足。

   H2 集成测试覆盖了逻辑，但以下行为必须在 Altibase 上验证：

   - `FOR UPDATE`
   - DDL
   - `VARBYTE`
   - 唯一索引和 `NULL` 语义
   - 时间字段
   - JDBC metadata
   - 事务隔离

3. 缺少 drift detection。

   资源进入 `ACTIVE / READY` 后，如果 Qdrant collection 被外部修改，目前不会主动发现。

### 中风险

1. provisioning 同步执行。

   Qdrant 响应慢、网络抖动、collection 创建耗时变长时，API 请求会被拉长。

2. outbox 缺少清理和归档策略。

   长期运行后：

   - DONE 事件会越来越多。
   - 查询和索引维护成本会上升。
   - 失败分析需要保留窗口策略。

3. `DEAD / retry / backlog` 可观测性不足。

   目前有状态和查询入口，但还不是产品级运维面。

   需要补：

   - 指标
   - 告警
   - 按 column / collection / tenant 聚合
   - 一键 retry 或批量 retry 策略

4. scalar-only 写入语义还要进一步定清楚。

   例如：

   - `BUILDING` column 是否允许 scalar-only insert。
   - `FAILED` column 是否允许 relational-only update。
   - delete no-vector 是否必须依赖 ready collection。

### 低到中风险

1. payload 数字类型写 Qdrant 时，部分 `Number` 会转 double，可能丢精度。

2. `"default"` 表示 unnamed vector 是过渡方案。

   长期应改成显式：

   ```text
   vector_mode = UNNAMED / NAMED
   qdrant_vector_name = null / actual_name
   ```

3. API 错误码目前主要是 message 字符串。

   不利于前端、运维系统、自动化脚本稳定判断错误类型。

## 5. 可验证 MVP

当前已经接近可验证 MVP，写入链路可以验证：

- create table
- insert
- update
- delete
- outbox pending / retry / dead
- Qdrant collection readiness
- 手工注册 verify-and-activate
- payload-only update 不污染失败指标

最近一次完整验证命令：

```bash
./mvnw verify
```

验证结果：

- unit tests：25 个，通过。
- integration tests：65 个，通过。
- `BUILD SUCCESS`。

### 可验证 MVP 仍缺的关键闭环

1. 最小查询接口。

   需要支持：

   - 普通关系查询。
   - 向量检索读 Qdrant。
   - 根据 point id 回查关系库。
   - 组装 payload / scalar columns / vector metadata。

2. 真实环境 E2E。

   至少需要跑：

   - Altibase
   - Qdrant
   - worker enabled
   - create table
   - insert / update / delete
   - search / query
   - retry / dead / manual retry

3. 指标面板。

   最小指标：

   - outbox backlog
   - dead count
   - retry count
   - sync latency
   - worker throughput
   - Qdrant write failure count

## 6. 产品化判断

当前状态：**强 MVP / 弱产品化**。

如果目标是内部验证架构、验证最终一致性、验证 Altibase + Qdrant 旁路模型，现在已经够用。

如果目标是给业务方稳定接入，还差一轮产品化治理。

### 产品化优先级建议

1. 补最小查询链路。

2. 补真实 Altibase + Qdrant E2E 测试。

3. 修 P0 小问题：

   - insert 空 vector。
   - delete no-vector skip。
   - float range。

4. 增加结构化 readiness 字段：

   - `verified_at`
   - `readiness_error_code`
   - `readiness_error_message`

5. 增加 outbox 指标和 dead / retry 运维接口。

6. 增加：

   - drift detection
   - reverify
   - 异步 provisioning

## 最终评价

当前方案是正确且合理的，不是临时拼凑。

它的优秀程度主要体现在写入同步框架：

- 关系库作为事实源。
- Qdrant 作为最终一致派生索引。
- outbox 合并和版本控制。
- 状态生命周期。
- readiness verification。

但产品化能力还需要继续补齐，尤其是查询链路、真实环境验证、运维观测、漂移检测和迁移治理。

