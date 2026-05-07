# Architecture Guide

本项目是 Altibase + Qdrant 旁路架构的元数据与同步控制面，当前不包含 embedding 生成流程。

## 1. 分层依赖规则

依赖方向必须单向：

- `interfaces` -> `application.port.in`
- `application` -> `application.port.out` + `domain` + `common`
- `infrastructure` -> `application.port.out` + `domain`
- `domain` -> 仅 JDK 类型

禁止：

- Controller 直接调用 JDBC Repository
- `application` 反向依赖 `infrastructure`
- 将多张系统表逻辑重新合并成一个“大而全” service/repository

## 2. 模块边界（Registry vs Sync）

### Registry（定义“是什么”）

- 表：`SYS_VECTOR_COLUMNS_`、`SYS_VECTOR_COLLECTIONS_`、`SYS_VECTOR_INDEXES_`、`SYS_VECTOR_PAYLOAD_FIELDS_`
- 目录：
  - `domain/registry`
  - `application/registry`
  - `infrastructure/persistence/registry`

### Sync（定义“跑到哪了/失败了什么”）

- 表：`SYS_VECTOR_SYNC_JOBS_`、`SYS_VECTOR_SYNC_PROGRESS_`、`SYS_VECTOR_SYNC_ERRORS_`
- 目录：
  - `domain/sync`
  - `application/sync`
  - `infrastructure/persistence/sync`

### Shared

- `infrastructure/persistence/support`：跨仓储复用能力（例如 `JdbcTimeSupport`）
- `application/support`：通用参数校验、格式校验等，不承载业务状态

## 3. 一表一链路（新增系统表时必须补齐）

新增任意系统表时，按下列最小闭环补齐：

1. `domain` 新增对应 `Meta` 记录模型（按 registry/sync 归属放置）
2. `application.port.out` 新增读写 Port
3. `application.port.in` + `application/{registry|sync}` 新增 UseCase/Service
4. `infrastructure/persistence/{registry|sync}` 新增 JDBC Repository 实现
5. `interfaces/rest` 新增 Controller + Request/Response DTO
6. `db/altibase/001_init_sidecar.sql` 补齐 DDL（主键、唯一约束、状态约束、必要索引）
7. 增加至少一条集成测试覆盖新增 API

## 4. 当前 7 张系统表归属

- Registry：
  - `SYS_VECTOR_COLUMNS_` -> `VectorColumnMeta` / `RegisterVectorColumnService` / `JdbcVectorMetadataRepository`
  - `SYS_VECTOR_COLLECTIONS_` -> `VectorCollectionMeta` / `RegisterVectorCollectionService` / `JdbcVectorCollectionRepository`
  - `SYS_VECTOR_INDEXES_` -> `VectorIndexMeta` / `RegisterVectorIndexService` / `JdbcVectorIndexRepository`
  - `SYS_VECTOR_PAYLOAD_FIELDS_` -> `VectorPayloadFieldMeta` / `RegisterVectorPayloadFieldService` / `JdbcVectorPayloadFieldRepository`
- Sync：
  - `SYS_VECTOR_SYNC_JOBS_` -> `VectorSyncJobMeta` / `CreateVectorSyncJobService` / `JdbcVectorSyncJobRepository`
  - `SYS_VECTOR_SYNC_PROGRESS_` -> `VectorSyncProgressMeta` / `UpsertVectorSyncProgressService` / `JdbcVectorSyncProgressRepository`
  - `SYS_VECTOR_SYNC_ERRORS_` -> `VectorSyncErrorMeta` / `RecordVectorSyncErrorService` / `JdbcVectorSyncErrorRepository`

## 5. 演进建议

- 需要跨表编排时，再引入 `application/system` 级别编排服务；单表 CRUD 不要放入 `system` 层。
- 面向产品化，优先保持“每张表一套职责清晰的垂直链路”，而不是“按功能杂糅”。
