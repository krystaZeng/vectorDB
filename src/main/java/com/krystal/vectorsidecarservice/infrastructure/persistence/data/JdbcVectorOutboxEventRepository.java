package com.krystal.vectorsidecarservice.infrastructure.persistence.data;

import com.krystal.vectorsidecarservice.application.port.out.VectorOutboxEventPort;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.data.VectorOutboxEventMeta;
import com.krystal.vectorsidecarservice.infrastructure.persistence.support.JdbcTimeSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JdbcVectorOutboxEventRepository extends JdbcTimeSupport implements VectorOutboxEventPort {

    private static final String COLUMNS = """
            EVENT_ID, TENANT_ID, COLUMN_ID, EVENT_KEY, ACTIVE_KEY, EVENT_TYPE, SOURCE_OP, EVENT_STATUS,
            SOURCE_PK, POINT_ID, PK_VALUE_TYPE, DEDUPE_KEY, SOURCE_VERSION, NEEDS_RESYNC,
            RETRY_COUNT, NEXT_RETRY_AT, NEXT_RETRY_AT_EPOCH_MS, LOCKED_BY, LOCKED_AT, LOCKED_AT_EPOCH_MS,
            CLAIM_TOKEN, FINISHED_AT, FINISHED_AT_EPOCH_MS, ERROR_CODE, ERROR_MESSAGE, CREATED_AT, UPDATED_AT
            """;

    private static final String INSERT_SQL = """
            INSERT INTO SYS_VECTOR_OUTBOX_EVENTS_ (
                EVENT_ID, TENANT_ID, COLUMN_ID, EVENT_KEY, ACTIVE_KEY, EVENT_TYPE, SOURCE_OP, EVENT_STATUS,
                SOURCE_PK, POINT_ID, PK_VALUE_TYPE, DEDUPE_KEY, SOURCE_VERSION, NEEDS_RESYNC,
                RETRY_COUNT, NEXT_RETRY_AT, NEXT_RETRY_AT_EPOCH_MS, LOCKED_BY, LOCKED_AT, LOCKED_AT_EPOCH_MS,
                CLAIM_TOKEN, FINISHED_AT, FINISHED_AT_EPOCH_MS, ERROR_CODE, ERROR_MESSAGE, CREATED_AT, UPDATED_AT
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_BY_ID_SQL = "SELECT " + COLUMNS + " FROM SYS_VECTOR_OUTBOX_EVENTS_ WHERE EVENT_ID = ?";

    private static final String FIND_BY_DEDUPE_KEY_SQL = "SELECT " + COLUMNS + " FROM SYS_VECTOR_OUTBOX_EVENTS_ WHERE DEDUPE_KEY = ?";

    private static final String FIND_BY_ACTIVE_KEY_SQL = "SELECT " + COLUMNS + " FROM SYS_VECTOR_OUTBOX_EVENTS_ WHERE ACTIVE_KEY = ?";

    private static final String MERGE_PENDING_OR_RETRYING_SQL = """
            UPDATE SYS_VECTOR_OUTBOX_EVENTS_
            SET EVENT_TYPE = ?,
                SOURCE_OP = ?,
                SOURCE_VERSION = ?,
                NEEDS_RESYNC = 'N',
                NEXT_RETRY_AT = ?,
                NEXT_RETRY_AT_EPOCH_MS = ?,
                ERROR_CODE = NULL,
                ERROR_MESSAGE = NULL,
                UPDATED_AT = ?
            WHERE EVENT_ID = ?
              AND ACTIVE_KEY = ?
              AND EVENT_STATUS IN ('PENDING', 'RETRYING')
            """;

    private static final String MARK_PROCESSING_NEEDS_RESYNC_SQL = """
            UPDATE SYS_VECTOR_OUTBOX_EVENTS_
            SET EVENT_TYPE = ?,
                SOURCE_OP = ?,
                SOURCE_VERSION = ?,
                NEEDS_RESYNC = 'Y',
                UPDATED_AT = ?
            WHERE EVENT_ID = ?
              AND ACTIVE_KEY = ?
              AND EVENT_STATUS = 'PROCESSING'
            """;

    private static final String FIND_DUE_SQL = """
            SELECT
            """ + COLUMNS + """
            FROM SYS_VECTOR_OUTBOX_EVENTS_
            WHERE EVENT_STATUS IN ('PENDING', 'RETRYING')
              AND NEXT_RETRY_AT_EPOCH_MS <= ?
            ORDER BY NEXT_RETRY_AT_EPOCH_MS, EVENT_ID
            """;

    private static final String CLAIM_SQL = """
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
            """;

    private static final String RELEASE_EXPIRED_SQL = """
            UPDATE SYS_VECTOR_OUTBOX_EVENTS_
            SET EVENT_STATUS = 'RETRYING',
                LOCKED_BY = NULL,
                LOCKED_AT = NULL,
                LOCKED_AT_EPOCH_MS = NULL,
                CLAIM_TOKEN = NULL,
                NEXT_RETRY_AT = ?,
                NEXT_RETRY_AT_EPOCH_MS = ?,
                ERROR_CODE = 'LOCK_EXPIRED',
                ERROR_MESSAGE = 'processing lock expired',
                UPDATED_AT = ?
            WHERE EVENT_STATUS = 'PROCESSING'
              AND LOCKED_AT_EPOCH_MS <= ?
            """;

    private static final String MARK_DONE_SQL = """
            UPDATE SYS_VECTOR_OUTBOX_EVENTS_
            SET EVENT_STATUS = 'DONE',
                ACTIVE_KEY = NULL,
                LOCKED_BY = NULL,
                LOCKED_AT = NULL,
                LOCKED_AT_EPOCH_MS = NULL,
                CLAIM_TOKEN = NULL,
                NEXT_RETRY_AT = NULL,
                NEXT_RETRY_AT_EPOCH_MS = NULL,
                FINISHED_AT = ?,
                FINISHED_AT_EPOCH_MS = ?,
                ERROR_CODE = NULL,
                ERROR_MESSAGE = NULL,
                UPDATED_AT = ?
            WHERE EVENT_ID = ?
              AND EVENT_STATUS = 'PROCESSING'
              AND CLAIM_TOKEN = ?
              AND SOURCE_VERSION = ?
              AND NEEDS_RESYNC = 'N'
            """;

    private static final String MARK_DONE_RESYNC_SQL = """
            UPDATE SYS_VECTOR_OUTBOX_EVENTS_
            SET EVENT_STATUS = 'PENDING',
                NEEDS_RESYNC = 'N',
                LOCKED_BY = NULL,
                LOCKED_AT = NULL,
                LOCKED_AT_EPOCH_MS = NULL,
                CLAIM_TOKEN = NULL,
                NEXT_RETRY_AT = ?,
                NEXT_RETRY_AT_EPOCH_MS = ?,
                ERROR_CODE = NULL,
                ERROR_MESSAGE = NULL,
                UPDATED_AT = ?
            WHERE EVENT_ID = ?
              AND EVENT_STATUS = 'PROCESSING'
              AND CLAIM_TOKEN = ?
              AND (NEEDS_RESYNC = 'Y' OR SOURCE_VERSION <> ?)
            """;

    private static final String MARK_RETRY_SQL = """
            UPDATE SYS_VECTOR_OUTBOX_EVENTS_
            SET EVENT_STATUS = 'RETRYING',
                RETRY_COUNT = ?,
                LOCKED_BY = NULL,
                LOCKED_AT = NULL,
                LOCKED_AT_EPOCH_MS = NULL,
                CLAIM_TOKEN = NULL,
                NEXT_RETRY_AT = ?,
                NEXT_RETRY_AT_EPOCH_MS = ?,
                ERROR_CODE = ?,
                ERROR_MESSAGE = ?,
                UPDATED_AT = ?
            WHERE EVENT_ID = ?
              AND EVENT_STATUS = 'PROCESSING'
              AND CLAIM_TOKEN = ?
              AND SOURCE_VERSION = ?
              AND NEEDS_RESYNC = 'N'
            """;

    private static final String MARK_DEAD_SQL = """
            UPDATE SYS_VECTOR_OUTBOX_EVENTS_
            SET EVENT_STATUS = 'DEAD',
                RETRY_COUNT = ?,
                LOCKED_BY = NULL,
                LOCKED_AT = NULL,
                LOCKED_AT_EPOCH_MS = NULL,
                CLAIM_TOKEN = NULL,
                NEXT_RETRY_AT = NULL,
                NEXT_RETRY_AT_EPOCH_MS = NULL,
                FINISHED_AT = ?,
                FINISHED_AT_EPOCH_MS = ?,
                ERROR_CODE = ?,
                ERROR_MESSAGE = ?,
                UPDATED_AT = ?
            WHERE EVENT_ID = ?
              AND EVENT_STATUS = 'PROCESSING'
              AND CLAIM_TOKEN = ?
              AND SOURCE_VERSION = ?
              AND NEEDS_RESYNC = 'N'
            """;

    private static final String RETRY_DEAD_SQL = """
            UPDATE SYS_VECTOR_OUTBOX_EVENTS_
            SET EVENT_STATUS = 'PENDING',
                NEEDS_RESYNC = 'N',
                RETRY_COUNT = 0,
                LOCKED_BY = NULL,
                LOCKED_AT = NULL,
                LOCKED_AT_EPOCH_MS = NULL,
                CLAIM_TOKEN = NULL,
                NEXT_RETRY_AT = ?,
                NEXT_RETRY_AT_EPOCH_MS = ?,
                FINISHED_AT = NULL,
                FINISHED_AT_EPOCH_MS = NULL,
                ERROR_CODE = NULL,
                ERROR_MESSAGE = NULL,
                UPDATED_AT = ?
            WHERE EVENT_ID = ?
              AND EVENT_STATUS = 'DEAD'
            """;

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<VectorOutboxEventMeta> mapper = (rs, rowNum) -> new VectorOutboxEventMeta(
            rs.getLong("EVENT_ID"),
            rs.getString("TENANT_ID"),
            rs.getLong("COLUMN_ID"),
            rs.getString("EVENT_KEY"),
            rs.getString("ACTIVE_KEY"),
            rs.getString("EVENT_TYPE"),
            rs.getString("SOURCE_OP"),
            rs.getString("EVENT_STATUS"),
            rs.getString("SOURCE_PK"),
            rs.getString("POINT_ID"),
            rs.getString("PK_VALUE_TYPE"),
            rs.getString("DEDUPE_KEY"),
            rs.getLong("SOURCE_VERSION"),
            rs.getString("NEEDS_RESYNC"),
            rs.getInt("RETRY_COUNT"),
            instant((Long) rs.getObject("NEXT_RETRY_AT_EPOCH_MS"), rs.getTimestamp("NEXT_RETRY_AT")),
            rs.getString("LOCKED_BY"),
            instant((Long) rs.getObject("LOCKED_AT_EPOCH_MS"), rs.getTimestamp("LOCKED_AT")),
            rs.getString("CLAIM_TOKEN"),
            instant((Long) rs.getObject("FINISHED_AT_EPOCH_MS"), rs.getTimestamp("FINISHED_AT")),
            rs.getString("ERROR_CODE"),
            rs.getString("ERROR_MESSAGE"),
            instant(rs.getTimestamp("CREATED_AT")),
            instant(rs.getTimestamp("UPDATED_AT"))
    );

    @Override
    public VectorOutboxEventMeta save(VectorOutboxEventMeta event) {
        try {
            jdbcTemplate.update(
                    INSERT_SQL,
                    event.eventId(),
                    event.tenantId(),
                    event.columnId(),
                    event.eventKey(),
                    event.activeKey(),
                    event.eventType(),
                    event.sourceOp(),
                    event.eventStatus(),
                    event.sourcePk(),
                    event.pointId(),
                    event.pkValueType(),
                    event.dedupeKey(),
                    event.sourceVersion(),
                    event.needsResync(),
                    event.retryCount(),
                    timestamp(event.nextRetryAt()),
                    epochMillis(event.nextRetryAt()),
                    event.lockedBy(),
                    timestamp(event.lockedAt()),
                    epochMillis(event.lockedAt()),
                    event.claimToken(),
                    timestamp(event.finishedAt()),
                    epochMillis(event.finishedAt()),
                    event.errorCode(),
                    truncate(event.errorMessage()),
                    timestamp(event.createdAt()),
                    timestamp(event.updatedAt())
            );
            return event;
        } catch (DataIntegrityViolationException ex) {
            throw new BizException("vector outbox event already exists", ex);
        }
    }

    @Override
    public SaveResult saveOrFindByDedupeKey(VectorOutboxEventMeta event) {
        try {
            return new SaveResult(save(event), true);
        } catch (BizException ex) {
            return findByDedupeKey(event.dedupeKey())
                    .map(existing -> new SaveResult(existing, false))
                    .orElseThrow(() -> ex);
        }
    }

    @Override
    public SaveResult enqueueOrMergeActive(VectorOutboxEventMeta event) {
        try {
            return new SaveResult(save(event), true);
        } catch (BizException ex) {
            if (event.activeKey() == null || event.activeKey().isBlank()) {
                throw ex;
            }
            VectorOutboxEventMeta existing = findByActiveKey(event.activeKey())
                    .orElseThrow(() -> ex);
            return switch (existing.eventStatus()) {
                case "PENDING", "RETRYING" -> mergePendingOrRetrying(existing, event);
                case "PROCESSING" -> markProcessingNeedsResync(existing, event);
                default -> new SaveResult(existing, false);
            };
        }
    }

    @Override
    public Optional<VectorOutboxEventMeta> findById(long eventId) {
        return jdbcTemplate.query(FIND_BY_ID_SQL, mapper, eventId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<VectorOutboxEventMeta> findByDedupeKey(String dedupeKey) {
        return jdbcTemplate.query(FIND_BY_DEDUPE_KEY_SQL, mapper, dedupeKey)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<VectorOutboxEventMeta> findByActiveKey(String activeKey) {
        return jdbcTemplate.query(FIND_BY_ACTIVE_KEY_SQL, mapper, activeKey)
                .stream()
                .findFirst();
    }

    private SaveResult mergePendingOrRetrying(VectorOutboxEventMeta existing, VectorOutboxEventMeta event) {
        if (event.sourceVersion() < existing.sourceVersion()) {
            return new SaveResult(existing, false);
        }
        int updated = jdbcTemplate.update(
                MERGE_PENDING_OR_RETRYING_SQL,
                event.eventType(),
                event.sourceOp(),
                event.sourceVersion(),
                timestamp(event.nextRetryAt()),
                epochMillis(event.nextRetryAt()),
                timestamp(event.updatedAt()),
                existing.eventId(),
                event.activeKey()
        );
        if (updated != 1) {
            return findByActiveKey(event.activeKey())
                    .map(current -> new SaveResult(current, false))
                    .orElse(new SaveResult(existing, false));
        }
        return findById(existing.eventId())
                .map(current -> new SaveResult(current, false))
                .orElse(new SaveResult(existing, false));
    }

    private SaveResult markProcessingNeedsResync(VectorOutboxEventMeta existing, VectorOutboxEventMeta event) {
        if (event.sourceVersion() < existing.sourceVersion()) {
            return new SaveResult(existing, false);
        }
        int updated = jdbcTemplate.update(
                MARK_PROCESSING_NEEDS_RESYNC_SQL,
                event.eventType(),
                event.sourceOp(),
                event.sourceVersion(),
                timestamp(event.updatedAt()),
                existing.eventId(),
                event.activeKey()
        );
        if (updated != 1) {
            return findByActiveKey(event.activeKey())
                    .map(current -> new SaveResult(current, false))
                    .orElse(new SaveResult(existing, false));
        }
        return findById(existing.eventId())
                .map(current -> new SaveResult(current, false))
                .orElse(new SaveResult(existing, false));
    }

    @Override
    public List<VectorOutboxEventMeta> findByStatus(String status, Long columnId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(COLUMNS)
                .append(" FROM SYS_VECTOR_OUTBOX_EVENTS_ WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND EVENT_STATUS = ?");
            args.add(status);
        }
        if (columnId != null) {
            sql.append(" AND COLUMN_ID = ?");
            args.add(columnId);
        }
        sql.append(" ORDER BY CREATED_AT DESC, EVENT_ID DESC");
        return jdbcTemplate.query(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql.toString());
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            ps.setMaxRows(limit);
            return ps;
        }, mapper);
    }

    @Override
    public List<VectorOutboxEventMeta> findDue(Instant now, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return jdbcTemplate.query(connection -> {
            PreparedStatement ps = connection.prepareStatement(FIND_DUE_SQL);
            ps.setLong(1, now.toEpochMilli());
            ps.setMaxRows(limit);
            return ps;
        }, mapper);
    }

    @Override
    public Optional<VectorOutboxEventMeta> claim(long eventId, String workerId, String claimToken, Instant now) {
        int updated = jdbcTemplate.update(
                CLAIM_SQL,
                workerId,
                timestamp(now),
                epochMillis(now),
                claimToken,
                timestamp(now),
                eventId,
                epochMillis(now)
        );
        if (updated != 1) {
            return Optional.empty();
        }
        return findById(eventId);
    }

    @Override
    public int releaseExpiredProcessing(Instant lockedBefore, Instant retryAt, Instant now) {
        return jdbcTemplate.update(
                RELEASE_EXPIRED_SQL,
                timestamp(retryAt),
                epochMillis(retryAt),
                timestamp(now),
                epochMillis(lockedBefore)
        );
    }

    @Override
    public OwnershipUpdateStatus markDone(long eventId, String claimToken, long processedSourceVersion, Instant now) {
        int updated = jdbcTemplate.update(
                MARK_DONE_SQL,
                timestamp(now),
                epochMillis(now),
                timestamp(now),
                eventId,
                claimToken,
                processedSourceVersion
        );
        if (updated == 1) {
            return OwnershipUpdateStatus.UPDATED;
        }
        int resyncUpdated = jdbcTemplate.update(
                MARK_DONE_RESYNC_SQL,
                timestamp(now),
                epochMillis(now),
                timestamp(now),
                eventId,
                claimToken,
                processedSourceVersion
        );
        return resyncUpdated == 1 ? OwnershipUpdateStatus.RESYNC_REQUIRED : OwnershipUpdateStatus.STALE_CLAIM;
    }

    @Override
    public OwnershipUpdateStatus markRetry(
            long eventId,
            String claimToken,
            long processedSourceVersion,
            int retryCount,
            Instant nextRetryAt,
            String errorCode,
            String errorMessage,
            Instant now
    ) {
        int updated = jdbcTemplate.update(
                MARK_RETRY_SQL,
                retryCount,
                timestamp(nextRetryAt),
                epochMillis(nextRetryAt),
                errorCode,
                truncate(errorMessage),
                timestamp(now),
                eventId,
                claimToken,
                processedSourceVersion
        );
        return ownershipOrResyncStatus(updated, eventId, claimToken, processedSourceVersion, now);
    }

    @Override
    public OwnershipUpdateStatus markDead(
            long eventId,
            String claimToken,
            long processedSourceVersion,
            int retryCount,
            String errorCode,
            String errorMessage,
            Instant now
    ) {
        int updated = jdbcTemplate.update(
                MARK_DEAD_SQL,
                retryCount,
                timestamp(now),
                epochMillis(now),
                errorCode,
                truncate(errorMessage),
                timestamp(now),
                eventId,
                claimToken,
                processedSourceVersion
        );
        return ownershipOrResyncStatus(updated, eventId, claimToken, processedSourceVersion, now);
    }

    @Override
    public Optional<VectorOutboxEventMeta> retryDead(long eventId, Instant now) {
        int updated = jdbcTemplate.update(
                RETRY_DEAD_SQL,
                timestamp(now),
                epochMillis(now),
                timestamp(now),
                eventId
        );
        if (updated != 1) {
            return Optional.empty();
        }
        return findById(eventId);
    }

    private OwnershipUpdateStatus ownershipOrResyncStatus(
            int updated,
            long eventId,
            String claimToken,
            long processedSourceVersion,
            Instant now
    ) {
        if (updated == 1) {
            return OwnershipUpdateStatus.UPDATED;
        }
        int resyncUpdated = jdbcTemplate.update(
                MARK_DONE_RESYNC_SQL,
                timestamp(now),
                epochMillis(now),
                timestamp(now),
                eventId,
                claimToken,
                processedSourceVersion
        );
        return resyncUpdated == 1 ? OwnershipUpdateStatus.RESYNC_REQUIRED : OwnershipUpdateStatus.STALE_CLAIM;
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 2048) {
            return value;
        }
        return value.substring(0, 2048);
    }
}
