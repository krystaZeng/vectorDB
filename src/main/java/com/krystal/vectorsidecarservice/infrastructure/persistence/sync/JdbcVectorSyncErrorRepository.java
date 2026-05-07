package com.krystal.vectorsidecarservice.infrastructure.persistence.sync;

import com.krystal.vectorsidecarservice.application.port.out.VectorSyncErrorPort;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.sync.VectorSyncErrorMeta;
import com.krystal.vectorsidecarservice.infrastructure.persistence.support.JdbcTimeSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class JdbcVectorSyncErrorRepository extends JdbcTimeSupport implements VectorSyncErrorPort {

    private static final String INSERT_SQL = """
            INSERT INTO SYS_VECTOR_SYNC_ERRORS_ (
                ERROR_ID, JOB_ID, COLUMN_ID, PARTITION_ID, SOURCE_PK, OP_TYPE, ERROR_STAGE, ERROR_CODE,
                ERROR_MESSAGE, PAYLOAD_SNAPSHOT, DEDUPE_KEY, FIRST_SEEN_AT, LAST_SEEN_AT, LAST_SEEN_AT_EPOCH_MS,
                RETRY_COUNT, NEXT_RETRY_AT, NEXT_RETRY_AT_EPOCH_MS, ERROR_STATUS, CREATED_AT, UPDATED_AT
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_SQL = """
            SELECT
                ERROR_ID, JOB_ID, COLUMN_ID, PARTITION_ID, SOURCE_PK, OP_TYPE, ERROR_STAGE, ERROR_CODE,
                ERROR_MESSAGE, PAYLOAD_SNAPSHOT, DEDUPE_KEY, FIRST_SEEN_AT, LAST_SEEN_AT, LAST_SEEN_AT_EPOCH_MS,
                RETRY_COUNT, NEXT_RETRY_AT, NEXT_RETRY_AT_EPOCH_MS, ERROR_STATUS, CREATED_AT, UPDATED_AT
            FROM SYS_VECTOR_SYNC_ERRORS_
            WHERE JOB_ID = ?
            ORDER BY CREATED_AT DESC
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public VectorSyncErrorMeta save(VectorSyncErrorMeta syncErrorMeta) {
        try {
            jdbcTemplate.update(
                    INSERT_SQL,
                    syncErrorMeta.errorId(),
                    syncErrorMeta.jobId(),
                    syncErrorMeta.columnId(),
                    syncErrorMeta.partitionId(),
                    syncErrorMeta.sourcePk(),
                    syncErrorMeta.opType(),
                    syncErrorMeta.errorStage(),
                    syncErrorMeta.errorCode(),
                    syncErrorMeta.errorMessage(),
                    syncErrorMeta.payloadSnapshot(),
                    syncErrorMeta.dedupeKey(),
                    timestamp(syncErrorMeta.firstSeenAt()),
                    timestamp(syncErrorMeta.lastSeenAt()),
                    epochMillis(syncErrorMeta.lastSeenAt()),
                    syncErrorMeta.retryCount(),
                    timestamp(syncErrorMeta.nextRetryAt()),
                    epochMillis(syncErrorMeta.nextRetryAt()),
                    syncErrorMeta.errorStatus(),
                    timestamp(syncErrorMeta.createdAt()),
                    timestamp(syncErrorMeta.updatedAt())
            );
            return syncErrorMeta;
        } catch (DataIntegrityViolationException ex) {
            throw new BizException("vector sync error already exists", ex);
        }
    }

    @Override
    public List<VectorSyncErrorMeta> findByJobId(long jobId) {
        return jdbcTemplate.query(FIND_SQL, (rs, rowNum) -> new VectorSyncErrorMeta(
                rs.getLong("ERROR_ID"),
                rs.getLong("JOB_ID"),
                rs.getLong("COLUMN_ID"),
                rs.getString("PARTITION_ID"),
                rs.getString("SOURCE_PK"),
                rs.getString("OP_TYPE"),
                rs.getString("ERROR_STAGE"),
                rs.getString("ERROR_CODE"),
                rs.getString("ERROR_MESSAGE"),
                rs.getString("PAYLOAD_SNAPSHOT"),
                rs.getString("DEDUPE_KEY"),
                instant(rs.getTimestamp("FIRST_SEEN_AT")),
                instant((Long) rs.getObject("LAST_SEEN_AT_EPOCH_MS"), rs.getTimestamp("LAST_SEEN_AT")),
                rs.getInt("RETRY_COUNT"),
                instant((Long) rs.getObject("NEXT_RETRY_AT_EPOCH_MS"), rs.getTimestamp("NEXT_RETRY_AT")),
                rs.getString("ERROR_STATUS"),
                instant(rs.getTimestamp("CREATED_AT")),
                instant(rs.getTimestamp("UPDATED_AT"))
        ), jobId);
    }
}
