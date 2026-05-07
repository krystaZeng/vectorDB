package com.krystal.vectorsidecarservice.infrastructure.persistence.sync;

import com.krystal.vectorsidecarservice.application.port.out.VectorSyncProgressPort;
import com.krystal.vectorsidecarservice.domain.sync.VectorSyncProgressMeta;
import com.krystal.vectorsidecarservice.infrastructure.persistence.support.JdbcTimeSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class JdbcVectorSyncProgressRepository extends JdbcTimeSupport implements VectorSyncProgressPort {

    private static final String INSERT_SQL = """
            INSERT INTO SYS_VECTOR_SYNC_PROGRESS_ (
                PROGRESS_ID, JOB_ID, COLUMN_ID, PARTITION_ID, LAST_PK, LAST_EVENT_TIME, LAST_EVENT_TIME_EPOCH_MS,
                LAST_EVENT_ID, PROCESSED_ROWS, SUCCESS_ROWS, FAILED_ROWS, LAST_BATCH_TIME, LAST_BATCH_TIME_EPOCH_MS,
                PROGRESS_STATUS, CHECKPOINT_DATA, UPDATED_AT
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String UPDATE_SQL = """
            UPDATE SYS_VECTOR_SYNC_PROGRESS_
            SET
                COLUMN_ID = ?,
                LAST_PK = ?,
                LAST_EVENT_TIME = ?,
                LAST_EVENT_TIME_EPOCH_MS = ?,
                LAST_EVENT_ID = ?,
                PROCESSED_ROWS = ?,
                SUCCESS_ROWS = ?,
                FAILED_ROWS = ?,
                LAST_BATCH_TIME = ?,
                LAST_BATCH_TIME_EPOCH_MS = ?,
                PROGRESS_STATUS = ?,
                CHECKPOINT_DATA = ?,
                UPDATED_AT = ?
            WHERE JOB_ID = ? AND PARTITION_ID = ?
            """;

    private static final String FIND_SQL = """
            SELECT
                PROGRESS_ID, JOB_ID, COLUMN_ID, PARTITION_ID, LAST_PK, LAST_EVENT_TIME, LAST_EVENT_TIME_EPOCH_MS,
                LAST_EVENT_ID, PROCESSED_ROWS, SUCCESS_ROWS, FAILED_ROWS, LAST_BATCH_TIME, LAST_BATCH_TIME_EPOCH_MS,
                PROGRESS_STATUS, CHECKPOINT_DATA, UPDATED_AT
            FROM SYS_VECTOR_SYNC_PROGRESS_
            WHERE JOB_ID = ?
            ORDER BY UPDATED_AT DESC
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public VectorSyncProgressMeta upsert(VectorSyncProgressMeta syncProgressMeta) {
        int updated = jdbcTemplate.update(
                UPDATE_SQL,
                syncProgressMeta.columnId(),
                syncProgressMeta.lastPk(),
                timestamp(syncProgressMeta.lastEventTime()),
                epochMillis(syncProgressMeta.lastEventTime()),
                syncProgressMeta.lastEventId(),
                syncProgressMeta.processedRows(),
                syncProgressMeta.successRows(),
                syncProgressMeta.failedRows(),
                timestamp(syncProgressMeta.lastBatchTime()),
                epochMillis(syncProgressMeta.lastBatchTime()),
                syncProgressMeta.progressStatus(),
                syncProgressMeta.checkpointData(),
                timestamp(syncProgressMeta.updatedAt()),
                syncProgressMeta.jobId(),
                syncProgressMeta.partitionId()
        );
        if (updated > 0) {
            return syncProgressMeta;
        }
        try {
            jdbcTemplate.update(
                    INSERT_SQL,
                    syncProgressMeta.progressId(),
                    syncProgressMeta.jobId(),
                    syncProgressMeta.columnId(),
                    syncProgressMeta.partitionId(),
                    syncProgressMeta.lastPk(),
                    timestamp(syncProgressMeta.lastEventTime()),
                    epochMillis(syncProgressMeta.lastEventTime()),
                    syncProgressMeta.lastEventId(),
                    syncProgressMeta.processedRows(),
                    syncProgressMeta.successRows(),
                    syncProgressMeta.failedRows(),
                    timestamp(syncProgressMeta.lastBatchTime()),
                    epochMillis(syncProgressMeta.lastBatchTime()),
                    syncProgressMeta.progressStatus(),
                    syncProgressMeta.checkpointData(),
                    timestamp(syncProgressMeta.updatedAt())
            );
            return syncProgressMeta;
        } catch (DataIntegrityViolationException ex) {
            jdbcTemplate.update(
                    UPDATE_SQL,
                    syncProgressMeta.columnId(),
                    syncProgressMeta.lastPk(),
                    timestamp(syncProgressMeta.lastEventTime()),
                    epochMillis(syncProgressMeta.lastEventTime()),
                    syncProgressMeta.lastEventId(),
                    syncProgressMeta.processedRows(),
                    syncProgressMeta.successRows(),
                    syncProgressMeta.failedRows(),
                    timestamp(syncProgressMeta.lastBatchTime()),
                    epochMillis(syncProgressMeta.lastBatchTime()),
                    syncProgressMeta.progressStatus(),
                    syncProgressMeta.checkpointData(),
                    timestamp(syncProgressMeta.updatedAt()),
                    syncProgressMeta.jobId(),
                    syncProgressMeta.partitionId()
            );
            return syncProgressMeta;
        }
    }

    @Override
    public List<VectorSyncProgressMeta> findByJobId(long jobId) {
        return jdbcTemplate.query(FIND_SQL, (rs, rowNum) -> new VectorSyncProgressMeta(
                rs.getLong("PROGRESS_ID"),
                rs.getLong("JOB_ID"),
                rs.getLong("COLUMN_ID"),
                rs.getString("PARTITION_ID"),
                rs.getString("LAST_PK"),
                instant((Long) rs.getObject("LAST_EVENT_TIME_EPOCH_MS"), rs.getTimestamp("LAST_EVENT_TIME")),
                rs.getString("LAST_EVENT_ID"),
                rs.getLong("PROCESSED_ROWS"),
                rs.getLong("SUCCESS_ROWS"),
                rs.getLong("FAILED_ROWS"),
                instant((Long) rs.getObject("LAST_BATCH_TIME_EPOCH_MS"), rs.getTimestamp("LAST_BATCH_TIME")),
                rs.getString("PROGRESS_STATUS"),
                rs.getString("CHECKPOINT_DATA"),
                instant(rs.getTimestamp("UPDATED_AT"))
        ), jobId);
    }
}
