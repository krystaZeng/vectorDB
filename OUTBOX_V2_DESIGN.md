# Outbox V2 Design

本文定义 `SYS_VECTOR_OUTBOX_EVENTS_` 从 insert-only vector upsert outbox 演进到业务 `insert / update / delete / select` 可用的状态机。对外业务语义保持 SQL 风格：`INSERT`、`UPDATE`、`DELETE`、`SELECT`；内部同步到 Qdrant 时，`INSERT / UPDATE` 映射为 vector upsert，`DELETE` 映射为 vector delete，`SELECT` 不写 outbox。

## 1. 设计目标

- 关系库业务表仍是事实源，Qdrant 是派生索引。
- 同一个 vector point 同一时间最多只有一个未解决 outbox 事件。
- worker 可以重复执行，但不能让旧 worker 覆盖新 worker 的 outbox 状态。
- 如果旧 worker 已经写入 Qdrant，但状态更新时发现 claim 过期，必须触发 resync，保证最终由关系库当前行修正 Qdrant。
- `DONE` 不阻塞未来同一行再次 update/insert 后的同步。
- `DEAD` 第一版不自动绕过，必须人工 retry。

## 2. 核心概念

### EVENT_KEY

`EVENT_KEY` 表示同一个需要同步到 vector engine 的逻辑资源。

定义：

```text
EVENT_KEY = tenantId + ":" + columnId + ":" + sourcePk
```

它不包含 vector sync 操作类型。`UPSERT` 和后续 `DELETE` 操作的是同一个逻辑 point。

### ACTIVE_KEY

`ACTIVE_KEY` 用于约束同一 `EVENT_KEY` 在未解决状态下只能有一个事件。

第一版定义：

```text
ACTIVE_KEY = EVENT_KEY
```

`ACTIVE_KEY` 可空，并有唯一约束：

```sql
UNIQUE (ACTIVE_KEY)
```

状态含义：

- `PENDING / RETRYING / PROCESSING / DEAD`：占用 `ACTIVE_KEY`
- `DONE`：清空 `ACTIVE_KEY`

这使 `DONE` 不阻塞未来 update/insert 后的同步，而 `DEAD` 会阻止自动绕过故障。

### SOURCE_VERSION

`SOURCE_VERSION` 是同一 `EVENT_KEY` 下的单调版本号。它不使用 `UPDATED_AT_EPOCH_MS`，避免时间精度、时钟和同毫秒多次更新问题。

第一版由 sidecar 版本表 `SYS_VECTOR_SOURCE_VERSIONS_` 分配版本：

- `EVENT_KEY` 是主键
- 每次 insert / update / future delete 都将 `CURRENT_VERSION + 1`
- 业务表存在 `ROW_VERSION BIGINT` 时，写入本次分配出的版本，作为关系行上的镜像版本

这样即使业务行被 delete 后又用同一个 pk reinsert，同一 logical point 的版本仍然继续递增。

outbox event 记录本次希望同步到 vector engine 的最新版本。

### NEEDS_RESYNC

`NEEDS_RESYNC` 表示某个事件正在 `PROCESSING` 时，又发生了同一 `EVENT_KEY` 的新变更。

它防止同一个 point 并发同步，也防止旧 worker 完成后把 outbox 标成 `DONE`，导致新版本没有再次同步。

## 3. 建议字段

`SYS_VECTOR_OUTBOX_EVENTS_` v2 需要在当前字段基础上增加：

```text
TENANT_ID
EVENT_KEY
ACTIVE_KEY
SOURCE_VERSION
NEEDS_RESYNC
SOURCE_OP
```

保留并继续使用：

```text
EVENT_ID
COLUMN_ID
EVENT_TYPE  -- vector sync op: UPSERT / DELETE
EVENT_STATUS
SOURCE_PK
POINT_ID
PK_VALUE_TYPE
RETRY_COUNT
NEXT_RETRY_AT / NEXT_RETRY_AT_EPOCH_MS
LOCKED_BY
LOCKED_AT / LOCKED_AT_EPOCH_MS
CLAIM_TOKEN
FINISHED_AT / FINISHED_AT_EPOCH_MS
ERROR_CODE
ERROR_MESSAGE
CREATED_AT
UPDATED_AT
```

`SOURCE_OP` 记录业务来源操作：`INSERT / UPDATE / DELETE`。`EVENT_TYPE` 表示对 vector engine 的同步动作：`INSERT / UPDATE` 写 `UPSERT`，`DELETE` 写 `DELETE`。

`DEDUPE_KEY` 在 v2 中不再承担“一个 pk 终身只能有一个 UPSERT 事件”的语义。可以逐步废弃，或过渡期保留为兼容字段，但新的合并逻辑必须基于 `ACTIVE_KEY`。

## 4. 事件状态

第一版状态仍使用：

```text
PENDING
PROCESSING
RETRYING
DONE
DEAD
```

含义：

- `PENDING`：可立即被 worker claim。
- `PROCESSING`：已被一个 worker claim，并有 `CLAIM_TOKEN`。
- `RETRYING`：失败后等待下次重试。
- `DONE`：同步成功，`ACTIVE_KEY = NULL`。
- `DEAD`：超过重试上限或不可恢复错误，保留 `ACTIVE_KEY`，只能人工 retry。

## 5. Enqueue / Merge 规则

### 没有 active event

创建新事件：

```text
EVENT_STATUS = PENDING
EVENT_TYPE = UPSERT / DELETE
SOURCE_OP = INSERT / UPDATE / DELETE
EVENT_KEY = tenantId:columnId:sourcePk
ACTIVE_KEY = EVENT_KEY
SOURCE_VERSION = allocated source version
NEEDS_RESYNC = N
NEXT_RETRY_AT = now
```

### 已有 PENDING / RETRYING

合并到已有事件：

```text
SOURCE_VERSION = latest allocated source version
EVENT_TYPE = latest vector sync op
SOURCE_OP = latest source op
NEEDS_RESYNC = N
NEXT_RETRY_AT = now
```

不新增事件。worker 还没处理，后续读取关系表当前行即可。

### 已有 PROCESSING

不新增并发事件。更新已有事件：

```text
SOURCE_VERSION = latest allocated source version
EVENT_TYPE = latest vector sync op
SOURCE_OP = latest source op
NEEDS_RESYNC = Y
```

当前 worker 完成外部写入后，`markDone` 会看到 `NEEDS_RESYNC = Y` 或版本变化，事件重新回到 `PENDING`。

### 已有 DONE

`DONE` 已清空 `ACTIVE_KEY`，不会阻塞后续 update/insert 触发的新同步。新的业务变更会创建新事件。

### 已有 DEAD

第一版不自动复活、不自动绕过。

行为：

- `DEAD` 保留 `ACTIVE_KEY`
- 同一 `EVENT_KEY` 新业务变更命中 active key 冲突时返回已有 `DEAD` 或 409
- 只能通过人工 retry 恢复

后续版本可以允许“更高 `SOURCE_VERSION` 创建新事件并保留旧 DEAD 历史”，但需要 attempt/history 表支持。

## 6. Claim 与 Ownership

claim 仍是原子更新：

```sql
UPDATE SYS_VECTOR_OUTBOX_EVENTS_
SET EVENT_STATUS = 'PROCESSING',
    LOCKED_BY = ?,
    LOCKED_AT = ?,
    LOCKED_AT_EPOCH_MS = ?,
    CLAIM_TOKEN = ?,
    NEXT_RETRY_AT = NULL,
    NEXT_RETRY_AT_EPOCH_MS = NULL,
    ERROR_CODE = NULL,
    ERROR_MESSAGE = NULL,
    UPDATED_AT = ?
WHERE EVENT_ID = ?
  AND EVENT_STATUS IN ('PENDING', 'RETRYING')
  AND NEXT_RETRY_AT_EPOCH_MS <= ?
```

完成态更新必须带 ownership 条件：

```sql
WHERE EVENT_ID = ?
  AND EVENT_STATUS = 'PROCESSING'
  AND CLAIM_TOKEN = ?
```

v2 的 `markDone` 还必须校验版本和 resync：

```sql
WHERE EVENT_ID = ?
  AND EVENT_STATUS = 'PROCESSING'
  AND CLAIM_TOKEN = ?
  AND SOURCE_VERSION = ?
  AND NEEDS_RESYNC = 'N'
```

如果该更新影响 1 行：

```text
EVENT_STATUS = DONE
ACTIVE_KEY = NULL
```

如果影响 0 行，worker 必须判断原因：

- stale claim：触发 resync
- `NEEDS_RESYNC = Y` 或 `SOURCE_VERSION` 变化：转回 `PENDING`
- event 已 DEAD 或被人工操作：不覆盖

## 7. Worker 流程

UPSERT worker 流程：

1. `findDue`
2. `claim(eventId, workerId, claimToken)`
3. 根据 `columnId + sourcePk` 重新读取关系表当前行
4. 读取当前 `ROW_VERSION`
5. 构造 Qdrant point，payload 中写入 `sourceVersion`
6. upsert Qdrant
7. `markDone(eventId, claimToken, processedVersion)`

`markDone` 结果：

- 成功：事件转 `DONE`，清空 `ACTIVE_KEY`
- 发现 `NEEDS_RESYNC` 或版本变化：事件转 `PENDING`，清空锁，`NEEDS_RESYNC = N`
- stale claim：触发 resync，避免旧 worker 外部写入造成最终脏数据

DELETE worker 流程：

1. `findDue`
2. `claim(eventId, workerId, claimToken)`
3. 不重新读取关系行，直接使用 tombstone 中的 `POINT_ID / PK_VALUE_TYPE`
4. 调用 vector engine delete point
5. `markDone(eventId, claimToken, event.sourceVersion)`

## 8. Stale Claim After External Write

`CLAIM_TOKEN` 只能保护 outbox 状态，不能阻止旧 worker 已经写入 Qdrant。

风险场景：

1. worker A claim，读取 `ROW_VERSION = 3`
2. A 卡住，锁超时
3. worker B claim，读取并同步 `ROW_VERSION = 4`，标记 `DONE`
4. A 恢复，把旧版本写入 Qdrant
5. A `markDone` 因 `CLAIM_TOKEN` 失效失败

因此第一版必须补偿：

```text
如果 worker 已经写入 Qdrant，但 finalize 返回 STALE_CLAIM：
    读取关系库当前行
    如果当前行存在：enqueue/merge 一个 PENDING UPSERT resync event
    如果当前行不存在：enqueue/merge 一个 PENDING DELETE resync event
```

这保证最终会再次读取关系库当前行，把 Qdrant 修正为最新状态。

后续增强：

- worker heartbeat，避免活 worker 锁过早过期
- Qdrant payload 持久化 `sourceVersion`
- 如果 vector engine 支持条件写，增加 version fencing

## 9. 人工 Retry

`DEAD` 事件保留 `ACTIVE_KEY`，人工 retry 直接复用该事件：

```text
EVENT_STATUS = PENDING
RETRY_COUNT = 0
NEEDS_RESYNC = N
CLAIM_TOKEN = NULL
LOCKED_BY = NULL
LOCKED_AT = NULL
LOCKED_AT_EPOCH_MS = NULL
NEXT_RETRY_AT = now
FINISHED_AT = NULL
ERROR_CODE = NULL
ERROR_MESSAGE = NULL
ACTIVE_KEY 保持不变
```

非 `DEAD` 事件调用 retry 返回冲突。

## 10. Delete 语义

DELETE 不能完全依赖 worker 重新读取关系表当前行，因为行可能已经不存在。

DELETE 事件至少需要 tombstone 信息：

```text
EVENT_TYPE = DELETE
SOURCE_OP = DELETE
EVENT_KEY
SOURCE_VERSION
SOURCE_PK
POINT_ID
PK_VALUE_TYPE
```

DELETE 和 UPSERT 使用同一个 `EVENT_KEY / ACTIVE_KEY / SOURCE_VERSION` 状态机。

业务 DELETE 流程：

1. 确认关系行存在。
2. 从 `SYS_VECTOR_SOURCE_VERSIONS_` 分配下一版 `SOURCE_VERSION`。
3. 在同一事务内先 enqueue/merge tombstone outbox event。
4. 如果命中 `DEAD` active event，返回错误并回滚，不删除关系行。
5. 删除关系行。
6. worker 后续按 tombstone 删除 Qdrant point。

`PENDING / RETRYING` 下 DELETE 会合并成 `EVENT_TYPE=DELETE`；`PROCESSING` 下 DELETE 会更新为 `EVENT_TYPE=DELETE` 且 `NEEDS_RESYNC=Y`，但不清当前 `CLAIM_TOKEN`。

## 11. 实现顺序

推荐顺序：

1. 业务表建表增加 `ROW_VERSION BIGINT NOT NULL`
2. 增加 `SYS_VECTOR_SOURCE_VERSIONS_`，由 sidecar 分配同一 `EVENT_KEY` 的单调版本
3. outbox 表增加 `TENANT_ID / EVENT_KEY / ACTIVE_KEY / SOURCE_VERSION / NEEDS_RESYNC`
4. repository 增加基于 `ACTIVE_KEY` 的 enqueue/merge 方法
5. worker `markDone / markRetry / markDead` 增加 `SOURCE_VERSION / NEEDS_RESYNC` 条件
6. stale claim after external write 触发 resync
7. 新增 `/api/v1/vector-data/update`，内部 enqueue vector UPSERT
8. 新增 `/api/v1/vector-data/delete`，内部 enqueue vector DELETE tombstone
9. 补完整并发与乱序测试

## 12. 必测场景

- `PENDING` 状态重复业务变更合并到同一事件。
- `RETRYING` 状态重复业务变更立即变为可处理。
- `PROCESSING` 状态重复业务变更设置 `NEEDS_RESYNC`。
- worker 处理完成时发现 `NEEDS_RESYNC = Y`，事件回到 `PENDING`。
- worker 处理完成时发现 `SOURCE_VERSION` 已变化，事件回到 `PENDING`。
- worker 写 Qdrant 后 `markDone` stale claim，触发 resync。
- `DONE` 清空 `ACTIVE_KEY`，未来业务变更可创建新事件。
- `DEAD` 保留 `ACTIVE_KEY`，未来业务变更不自动绕过。
- 人工 retry 将 `DEAD` 恢复为 `PENDING`，且 `ACTIVE_KEY` 保持不变。
- 同一 `EVENT_KEY` 多 worker 并发 claim 只有一个成功。
- DELETE 合并 PENDING UPSERT 后，worker 执行 Qdrant delete 并清空 `ACTIVE_KEY`。
- PROCESSING UPSERT 期间 DELETE，只设置 `NEEDS_RESYNC=Y`，不清当前 worker 锁。
- DELETE 后同 pk 重新 INSERT，`SOURCE_VERSION` 继续递增，并将 active DELETE 合并回 UPSERT。
