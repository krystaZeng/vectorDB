package com.krystal.vectorsidecarservice.infrastructure.persistence.sync;

import com.krystal.vectorsidecarservice.application.port.out.VectorSyncJobPort;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.sync.VectorSyncJobMeta;
import com.krystal.vectorsidecarservice.infrastructure.persistence.support.JdbcTimeSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JdbcVectorSyncJobRepository extends JdbcTimeSupport implements VectorSyncJobPort {

    private static final String INSERT_SQL = """
            INSERT INTO SYS_VECTOR_SYNC_JOBS_ (
                JOB_ID, COLUMN_ID, COLLECTION_ID, INDEX_ID, JOB_TYPE, JOB_STATUS, TRIGGER_TYPE,
                IDEMPOTENCY_KEY, SNAPSHOT_ID, SOURCE_CURSOR, START_PK, END_PK, WORKER_ID, ATTEMPT_NO,
                RETRY_COUNT, ERROR_CODE, ERROR_MESSAGE, HEARTBEAT_AT, HEARTBEAT_AT_EPOCH_MS,
                STARTED_AT, STARTED_AT_EPOCH_MS, FINISHED_AT, FINISHED_AT_EPOCH_MS, CREATED_AT, UPDATED_AT
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_SQL = """
            SELECT
                JOB_ID, COLUMN_ID, COLLECTION_ID, INDEX_ID, JOB_TYPE, JOB_STATUS, TRIGGER_TYPE,
                IDEMPOTENCY_KEY, SNAPSHOT_ID, SOURCE_CURSOR, START_PK, END_PK, WORKER_ID, ATTEMPT_NO,
                RETRY_COUNT, ERROR_CODE, ERROR_MESSAGE, HEARTBEAT_AT, HEARTBEAT_AT_EPOCH_MS,
                STARTED_AT, STARTED_AT_EPOCH_MS, FINISHED_AT, FINISHED_AT_EPOCH_MS, CREATED_AT, UPDATED_AT
            FROM SYS_VECTOR_SYNC_JOBS_
            WHERE COLUMN_ID = ?
            ORDER BY CREATED_AT DESC
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT
                JOB_ID, COLUMN_ID, COLLECTION_ID, INDEX_ID, JOB_TYPE, JOB_STATUS, TRIGGER_TYPE,
                IDEMPOTENCY_KEY, SNAPSHOT_ID, SOURCE_CURSOR, START_PK, END_PK, WORKER_ID, ATTEMPT_NO,
                RETRY_COUNT, ERROR_CODE, ERROR_MESSAGE, HEARTBEAT_AT, HEARTBEAT_AT_EPOCH_MS,
                STARTED_AT, STARTED_AT_EPOCH_MS, FINISHED_AT, FINISHED_AT_EPOCH_MS, CREATED_AT, UPDATED_AT
            FROM SYS_VECTOR_SYNC_JOBS_
            WHERE JOB_ID = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public VectorSyncJobMeta save(VectorSyncJobMeta syncJobMeta) {
        try {
            jdbcTemplate.update(
                    INSERT_SQL,
                    syncJobMeta.jobId(),
                    syncJobMeta.columnId(),
                    syncJobMeta.collectionId(),
                    syncJobMeta.indexId(),
                    syncJobMeta.jobType(),
                    syncJobMeta.jobStatus(),
                    syncJobMeta.triggerType(),
                    syncJobMeta.idempotencyKey(),
                    syncJobMeta.snapshotId(),
                    syncJobMeta.sourceCursor(),
                    syncJobMeta.startPk(),
                    syncJobMeta.endPk(),
                    syncJobMeta.workerId(),
                    syncJobMeta.attemptNo(),
                    syncJobMeta.retryCount(),
                    syncJobMeta.errorCode(),
                    syncJobMeta.errorMessage(),
                    timestamp(syncJobMeta.heartbeatAt()),
                    epochMillis(syncJobMeta.heartbeatAt()),
                    timestamp(syncJobMeta.startedAt()),
                    epochMillis(syncJobMeta.startedAt()),
                    timestamp(syncJobMeta.finishedAt()),
                    epochMillis(syncJobMeta.finishedAt()),
                    timestamp(syncJobMeta.createdAt()),
                    timestamp(syncJobMeta.updatedAt())
            );
            return syncJobMeta;
        } catch (DataIntegrityViolationException ex) {
            throw new BizException("vector sync job already exists", ex);
        }
    }

    @Override
    public Optional<VectorSyncJobMeta> findById(long jobId) {
        return jdbcTemplate.query(FIND_BY_ID_SQL, this::mapRow, jobId)
                .stream()
                .findFirst();
    }

    @Override
    public List<VectorSyncJobMeta> findByColumnId(long columnId) {
        return jdbcTemplate.query(FIND_SQL, this::mapRow, columnId);
    }

    private VectorSyncJobMeta mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new VectorSyncJobMeta(
                rs.getLong("JOB_ID"),
                rs.getLong("COLUMN_ID"),
                (Long) rs.getObject("COLLECTION_ID"),
                (Long) rs.getObject("INDEX_ID"),
                rs.getString("JOB_TYPE"),
                rs.getString("JOB_STATUS"),
                rs.getString("TRIGGER_TYPE"),
                rs.getString("IDEMPOTENCY_KEY"),
                rs.getString("SNAPSHOT_ID"),
                rs.getString("SOURCE_CURSOR"),
                rs.getString("START_PK"),
                rs.getString("END_PK"),
                rs.getString("WORKER_ID"),
                rs.getInt("ATTEMPT_NO"),
                rs.getInt("RETRY_COUNT"),
                rs.getString("ERROR_CODE"),
                rs.getString("ERROR_MESSAGE"),
                instant((Long) rs.getObject("HEARTBEAT_AT_EPOCH_MS"), rs.getTimestamp("HEARTBEAT_AT")),
                instant((Long) rs.getObject("STARTED_AT_EPOCH_MS"), rs.getTimestamp("STARTED_AT")),
                instant((Long) rs.getObject("FINISHED_AT_EPOCH_MS"), rs.getTimestamp("FINISHED_AT")),
                instant(rs.getTimestamp("CREATED_AT")),
                instant(rs.getTimestamp("UPDATED_AT"))
        );
    }
}
