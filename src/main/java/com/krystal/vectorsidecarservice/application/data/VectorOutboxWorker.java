package com.krystal.vectorsidecarservice.application.data;

import com.krystal.vectorsidecarservice.application.port.out.VectorCollectionPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorDataRelationalPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorEngineDataPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorMetadataPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorOutboxEventPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorPayloadFieldPort;
import com.krystal.vectorsidecarservice.application.registry.lifecycle.VectorColumnLifecycle;
import com.krystal.vectorsidecarservice.application.system.VectorEngineDataRouter;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.data.VectorOutboxEventMeta;
import com.krystal.vectorsidecarservice.domain.registry.VectorCollectionMeta;
import com.krystal.vectorsidecarservice.domain.registry.VectorColumnMeta;
import com.krystal.vectorsidecarservice.domain.registry.VectorPayloadFieldMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class VectorOutboxWorker {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    private final VectorOutboxEventPort vectorOutboxEventPort;
    private final VectorMetadataPort vectorMetadataPort;
    private final VectorCollectionPort vectorCollectionPort;
    private final VectorPayloadFieldPort vectorPayloadFieldPort;
    private final VectorDataRelationalPort vectorDataRelationalPort;
    private final VectorEngineDataRouter vectorEngineDataRouter;
    private final VectorValueEncoder vectorValueEncoder;
    private final VectorPointIdNormalizer pointIdNormalizer;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName() + "-" + UUID.randomUUID();

    @Value("${vector.outbox.worker.enabled:false}")
    private boolean enabled;

    @Value("${vector.outbox.worker.batch-size:50}")
    private int configuredBatchSize;

    @Value("${vector.outbox.worker.max-retries:5}")
    private int maxRetries;

    @Value("${vector.outbox.worker.initial-retry-delay-ms:1000}")
    private long initialRetryDelayMs;

    @Value("${vector.outbox.worker.max-retry-delay-ms:60000}")
    private long maxRetryDelayMs;

    @Value("${vector.outbox.worker.lock-timeout-ms:60000}")
    private long lockTimeoutMs;

    @Scheduled(fixedDelayString = "${vector.outbox.worker.fixed-delay-ms:1000}")
    public void scheduledDrain() {
        if (enabled) {
            drainOnce(configuredBatchSize);
        }
    }

    public int drainOnce(int batchSize) {
        if (batchSize <= 0) {
            return 0;
        }
        Instant now = Instant.now();
        vectorOutboxEventPort.releaseExpiredProcessing(now.minusMillis(lockTimeoutMs), now, now);
        List<VectorOutboxEventMeta> dueEvents = vectorOutboxEventPort.findDue(now, batchSize);
        int processed = 0;
        for (VectorOutboxEventMeta event : dueEvents) {
            var claimed = vectorOutboxEventPort.claim(event.eventId(), workerId, Instant.now());
            if (claimed.isPresent()) {
                processClaimed(claimed.get());
                processed++;
            }
        }
        return processed;
    }

    private void processClaimed(VectorOutboxEventMeta event) {
        try {
            processUpsert(event);
            vectorOutboxEventPort.markDone(event.eventId(), Instant.now());
        } catch (NonRetryableOutboxException ex) {
            vectorOutboxEventPort.markDead(
                    event.eventId(),
                    event.retryCount(),
                    errorCode(ex),
                    ex.getMessage(),
                    Instant.now()
            );
        } catch (Exception ex) {
            markRecoverableFailure(event, ex);
        }
    }

    private void processUpsert(VectorOutboxEventMeta event) {
        if (!"UPSERT".equals(event.eventType())) {
            throw new NonRetryableOutboxException("unsupported outbox eventType: " + event.eventType());
        }
        VectorColumnMeta column = vectorMetadataPort.findById(event.columnId())
                .orElseThrow(() -> new NonRetryableOutboxException("vector column metadata not found: " + event.columnId()));
        if (!VectorColumnLifecycle.ACTIVE.status().equals(column.status())) {
            throw new BizException("vector column is not ACTIVE: " + column.columnId());
        }
        VectorCollectionMeta collection = readyCollection(column.columnId());
        List<VectorPayloadFieldMeta> qdrantPayloadFields = qdrantPayloadFields(column.columnId());
        List<String> sourceColumns = qdrantPayloadFields.stream()
                .map(field -> normalizeIdentifier(field.sourceColumnName(), "payload sourceColumnName"))
                .distinct()
                .toList();

        VectorDataRelationalPort.VectorRow row = vectorDataRelationalPort.findByPk(
                        new VectorDataRelationalPort.FindRowCommand(
                                column.schemaName(),
                                column.tableName(),
                                column.pkColumn(),
                                pointIdNormalizer.relationalPkValue(event.sourcePk(), event.pkValueType()),
                                column.vectorColumn(),
                                sourceColumns
                        )
                )
                .orElseThrow(() -> new NonRetryableOutboxException("source row not found: " + event.sourcePk()));
        if (row.vectorBytes() == null || row.vectorBytes().length == 0) {
            throw new NonRetryableOutboxException("source row vector is empty: " + event.sourcePk());
        }

        Map<String, Object> qdrantPayload = qdrantPayload(qdrantPayloadFields, row.scalarValues());
        VectorPointIdNormalizer.NormalizedPointId pointId = pointIdNormalizer.normalize(event.pointId(), collection.qdrantIdType());
        String writeTargetName = writeTargetName(collection);
        VectorEngineDataPort.UpsertPointResult result = vectorEngineDataRouter.get(collection.engineType())
                .upsertPoint(new VectorEngineDataPort.UpsertPointCommand(
                        writeTargetName,
                        collection.qdrantVectorName(),
                        pointId.value(),
                        vectorValueEncoder.decodeToFloatVector(row.vectorBytes(), column.dimension(), column.vectorEncoding()),
                        qdrantPayload
                ));
        if (result.status() != VectorEngineDataPort.UpsertPointStatus.UPSERTED) {
            throw new BizException(result.message());
        }
    }

    private VectorCollectionMeta readyCollection(long columnId) {
        return vectorCollectionPort.findByColumnId(columnId)
                .stream()
                .filter(collection -> "ACTIVE".equals(collection.servingState())
                        && "READY".equals(collection.collectionStatus()))
                .findFirst()
                .orElseThrow(() -> new BizException("ready vector collection not found for column: " + columnId));
    }

    private List<VectorPayloadFieldMeta> qdrantPayloadFields(long columnId) {
        return vectorPayloadFieldPort.findByColumnId(columnId)
                .stream()
                .filter(field -> "ACTIVE".equals(field.fieldStatus()))
                .filter(field -> "Y".equals(field.syncEnabled()))
                .toList();
    }

    private Map<String, Object> qdrantPayload(
            List<VectorPayloadFieldMeta> qdrantPayloadFields,
            Map<String, Object> scalarValues
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (VectorPayloadFieldMeta field : qdrantPayloadFields) {
            String sourceColumn = normalizeIdentifier(field.sourceColumnName(), "payload sourceColumnName");
            if (payload.containsKey(field.payloadKey())) {
                throw new NonRetryableOutboxException("duplicated payloadKey in metadata: " + field.payloadKey());
            }
            payload.put(field.payloadKey(), scalarValues.get(sourceColumn));
        }
        return payload;
    }

    private void markRecoverableFailure(VectorOutboxEventMeta event, Exception ex) {
        int nextRetryCount = event.retryCount() + 1;
        Instant now = Instant.now();
        if (nextRetryCount >= maxRetries) {
            vectorOutboxEventPort.markDead(
                    event.eventId(),
                    nextRetryCount,
                    errorCode(ex),
                    ex.getMessage(),
                    now
            );
            return;
        }
        vectorOutboxEventPort.markRetry(
                event.eventId(),
                nextRetryCount,
                now.plusMillis(retryDelayMillis(nextRetryCount)),
                errorCode(ex),
                ex.getMessage(),
                now
        );
    }

    private long retryDelayMillis(int retryCount) {
        long multiplier = 1L << Math.min(Math.max(retryCount - 1, 0), 10);
        long delay = initialRetryDelayMs * multiplier;
        if (delay < 0) {
            return maxRetryDelayMs;
        }
        return Math.min(delay, maxRetryDelayMs);
    }

    private String writeTargetName(VectorCollectionMeta collection) {
        if (collection.aliasName() != null && !collection.aliasName().isBlank()) {
            return collection.aliasName();
        }
        return collection.collectionName();
    }

    private String normalizeIdentifier(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new NonRetryableOutboxException(fieldName + " must not be blank");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!IDENTIFIER_PATTERN.matcher(normalized).matches()) {
            throw new NonRetryableOutboxException(fieldName + " must match [A-Za-z][A-Za-z0-9_]*");
        }
        return normalized;
    }

    private String errorCode(Exception ex) {
        String code = ex.getClass().getSimpleName();
        if (code.length() <= 64) {
            return code;
        }
        return code.substring(0, 64);
    }

    private static class NonRetryableOutboxException extends RuntimeException {

        NonRetryableOutboxException(String message) {
            super(message);
        }
    }
}
