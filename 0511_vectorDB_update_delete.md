# 2026-05-11 VectorDB Update / Delete 实现总结

本文梳理 2026-05-11 对 vector sidecar 数据写入链路的主要设计和实现。核心目标是把系统从 insert-only vector upsert，推进到业务 `insert / update / delete`，同时把 outbox 提升到更接近生产可用的状态机。

## 1. 业务语义

对外业务接口保持 SQL 风格：

| 业务操作 | 关系库语义 | Outbox 同步动作 | Qdrant 动作 |
| --- | --- | --- | --- |
| INSERT | 新增关系表行，重复 PK 失败 | `EVENT_TYPE=UPSERT`, `SOURCE_OP=INSERT` | upsert point |
| UPDATE | 更新已有关系表行，不存在失败 | `EVENT_TYPE=UPSERT`, `SOURCE_OP=UPDATE` | upsert point |
| DELETE | 删除已有关系表行，不存在失败 | `EVENT_TYPE=DELETE`, `SOURCE_OP=DELETE` | delete point |
| SELECT | 读关系库或向量检索结果 | 不写 outbox | read/search |

这里的 `UPSERT` 不是对外业务 API，而是 vector engine 同步动作。因为 Qdrant 的写入模型是 point upsert，关系库的 INSERT 和 UPDATE 最终都映射为 Qdrant upsert。

## 2. Outbox V2 状态机

本次修改将 outbox 从永久 `DEDUPE_KEY` 去重，调整为 active-level coalescing 模型：

- `EVENT_KEY = tenantId + ":" + columnId + ":" + sourcePk`
- `ACTIVE_KEY = EVENT_KEY`
- `PENDING / RETRYING / PROCESSING / DEAD` 占用 `ACTIVE_KEY`
- `DONE` 清空 `ACTIVE_KEY`
- `DEAD` 第一版保留 `ACTIVE_KEY`，不允许业务更新或删除自动绕过，需要人工 retry

同一 logical point 同一时间最多存在一个未解决事件。后续业务变更会合并到当前 active event，而不是创建并发 worker。

## 3. SOURCE_VERSION

`SOURCE_VERSION` 不再依赖业务表行自身递增，而由 sidecar 版本表维护：

- 新增 `SYS_VECTOR_SOURCE_VERSIONS_`
- 以 `EVENT_KEY` 为主键
- 每次 INSERT / UPDATE / DELETE 分配下一版 `CURRENT_VERSION + 1`
- 业务表存在 `ROW_VERSION` 时，将本次版本镜像写入业务表

这样可以解决 delete 后同 pk reinsert 的版本断裂问题。示例：

```text
INSERT pk=501 -> SOURCE_VERSION=1
UPDATE pk=501 -> SOURCE_VERSION=2
DELETE pk=501 -> SOURCE_VERSION=3
REINSERT pk=501 -> SOURCE_VERSION=4
```

## 4. Worker Ownership

outbox worker 的完成态更新已经改为基于 claim token 和版本 fencing：

```sql
WHERE EVENT_ID = ?
  AND EVENT_STATUS = 'PROCESSING'
  AND CLAIM_TOKEN = ?
  AND SOURCE_VERSION = ?
  AND NEEDS_RESYNC = 'N'
```

`markDone / markRetry / markDead` 都走同一类 ownership 保护：

- claim 过期或被新 worker 接管时，旧 worker 不能覆盖 outbox 状态
- 如果 `NEEDS_RESYNC=Y` 或版本变化，事件回到 `PENDING`
- 如果旧 worker 已经写过 Qdrant 但 finalize 发现 `STALE_CLAIM`，会触发 resync

这解决了旧 worker 在锁超时后覆盖新 worker 状态的问题。

## 5. Update 链路

新增 `POST /api/v1/vector-data/update`：

1. 校验请求、租户、schema/table/vector column。
2. 确认关系表行存在；不存在返回错误。
3. 从 `SYS_VECTOR_SOURCE_VERSIONS_` 分配下一版。
4. 更新关系表向量列、payload 标量列和 `ROW_VERSION`。
5. 如果更新影响 vector 或同步到 Qdrant 的 payload，则 enqueue/merge `EVENT_TYPE=UPSERT`。
6. worker 后续重新读取关系表当前行，再 upsert Qdrant。

Update 语义是 update-only，不会在关系行不存在时偷偷创建新行。

## 6. Delete 链路

新增 `POST /api/v1/vector-data/delete`：

1. 确认关系表行存在；不存在返回错误。
2. 从 `SYS_VECTOR_SOURCE_VERSIONS_` 分配下一版。
3. 生成 tombstone outbox event：
   - `EVENT_TYPE=DELETE`
   - `SOURCE_OP=DELETE`
   - `EVENT_KEY`
   - `SOURCE_VERSION`
   - `SOURCE_PK`
   - `POINT_ID`
   - `PK_VALUE_TYPE`
4. 同一事务内先 enqueue/merge outbox，再删除关系表行。
5. 如果 active event 是 `DEAD`，返回错误并回滚，不删除关系表行。
6. worker 处理 DELETE 时不重新读取关系表，直接根据 tombstone 删除 Qdrant point。

DELETE 和 UPSERT 使用同一套 `EVENT_KEY / ACTIVE_KEY / SOURCE_VERSION / NEEDS_RESYNC` 状态机。

## 7. Merge 规则

同一 `ACTIVE_KEY` 已存在时：

- `PENDING / RETRYING`：合并成最新 `EVENT_TYPE / SOURCE_OP / SOURCE_VERSION`。
- `PROCESSING`：更新为最新事件，设置 `NEEDS_RESYNC=Y`，不清当前 `CLAIM_TOKEN`。
- `DONE`：不占用 `ACTIVE_KEY`，新业务变更可创建新事件。
- `DEAD`：不自动复活，不自动绕过，返回错误或已有 DEAD event。

另外，低版本 stale resync 不会覆盖高版本 active event，避免旧 worker 兜底事件把新状态倒退。

## 8. Stale Claim 后 Resync

`CLAIM_TOKEN` 只能保护 outbox 状态，不能阻止旧 worker 已经对 Qdrant 产生外部副作用。

本次实现的兜底策略：

```text
worker 已经写过 Qdrant，但 finalize 返回 STALE_CLAIM:
    读取关系库当前行
    如果当前行存在：enqueue/merge UPSERT resync
    如果当前行不存在：enqueue/merge DELETE resync
```

这保证最终结果会回到关系库事实源对应的状态。

## 9. Qdrant Adapter

`VectorEngineDataPort` 增加 `deletePoint`：

- upsert: `PUT /collections/{collection}/points?wait=true`
- delete: `POST /collections/{collection}/points/delete?wait=true`

业务层不直接感知 Qdrant 的接口差异，只通过 outbox worker 的 `EVENT_TYPE` 选择 upsert 或 delete。

## 10. 测试覆盖

本次补充的关键测试包括：

- update existing row 后关系表、`ROW_VERSION`、outbox 均更新。
- update missing row 返回错误。
- delete existing row 后关系行删除，outbox 合并为 `DELETE`。
- delete missing row 返回错误。
- delete 后同 pk reinsert，`SOURCE_VERSION` 继续递增，并把 active DELETE 合并回 UPSERT。
- worker 可消费 DELETE event 并调用 Qdrant delete。
- PROCESSING UPSERT 期间 DELETE，只设置 `NEEDS_RESYNC=Y`，不清 claim。
- `markRetry / markDead` 遇到 resync required 时回到 `PENDING`。
- Qdrant adapter 覆盖 point delete 请求。

已验证：

```bash
./mvnw -q -Dtest=VectorDataControllerIT,VectorOutboxWorkerIT,QdrantVectorEngineAdminAdapterTest test
./mvnw -q test
./mvnw -q verify
git diff --check
```

## 11. 当前边界

已经完成：

- 业务 `insert / update / delete`
- 内部 vector `UPSERT / DELETE`
- outbox v2 active coalescing
- worker ownership / claim token
- sidecar source version
- dead event 查询与人工 retry

尚未完成或需要后续产品化补强：

- `select` 查询链路
- 请求级 idempotency key
- attempt history 表
- worker heartbeat
- 真实 Altibase DDL smoke test，尤其是 `UNIQUE(ACTIVE_KEY)` 多 `NULL` 行为
- Flyway/Liquibase 管理系统表 DDL 版本
- outbox 指标、告警和运维操作面
