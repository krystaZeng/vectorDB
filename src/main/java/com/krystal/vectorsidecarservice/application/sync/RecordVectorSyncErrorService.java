package com.krystal.vectorsidecarservice.application.sync;

import com.krystal.vectorsidecarservice.application.port.in.RecordVectorSyncErrorUseCase;
import com.krystal.vectorsidecarservice.application.port.out.IdGeneratorPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorSyncErrorPort;
import com.krystal.vectorsidecarservice.application.support.FieldValidator;
import com.krystal.vectorsidecarservice.application.support.VectorMetadataReferenceGuard;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.sync.VectorSyncErrorMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RecordVectorSyncErrorService implements RecordVectorSyncErrorUseCase {

    private static final int MAX_PAYLOAD_SNAPSHOT_LEN = 2000;

    private final VectorSyncErrorPort vectorSyncErrorPort;
    private final IdGeneratorPort idGenerator;
    private final VectorMetadataReferenceGuard referenceGuard;

    @Override
    public VectorSyncErrorMeta record(RecordVectorSyncErrorCommand command) {
        FieldValidator.requirePositive(command.jobId(), "jobId");
        FieldValidator.requirePositive(command.columnId(), "columnId");
        Instant now = Instant.now();
        String errorStatus = FieldValidator.normalizeEnum(
                command.errorStatus(),
                Set.of("OPEN", "RETRYING", "RESOLVED", "DEAD"),
                "OPEN",
                "errorStatus"
        );
        Instant nextRetryAt = command.nextRetryAt();
        if (!"RETRYING".equals(errorStatus) && nextRetryAt != null) {
            throw new BizException("nextRetryAt is only allowed when errorStatus=RETRYING");
        }
        int retryCount = FieldValidator.nonNegativeOrDefault(command.retryCount(), 0, "retryCount");
        referenceGuard.requireSyncJobForColumn(command.jobId(), command.columnId());
        VectorSyncErrorMeta meta = new VectorSyncErrorMeta(
                idGenerator.nextId(),
                command.jobId(),
                command.columnId(),
                FieldValidator.optionalText(command.partitionId()),
                FieldValidator.requireText(command.sourcePk(), "sourcePk"),
                FieldValidator.normalizeEnum(command.opType(), Set.of("UPSERT", "DELETE"), "UPSERT", "opType"),
                FieldValidator.optionalText(command.errorStage(), "UPSERT").toUpperCase(Locale.ROOT),
                FieldValidator.requireText(command.errorCode(), "errorCode"),
                FieldValidator.requireText(command.errorMessage(), "errorMessage"),
                FieldValidator.optionalTextTruncate(command.payloadSnapshot(), MAX_PAYLOAD_SNAPSHOT_LEN),
                FieldValidator.requireText(command.dedupeKey(), "dedupeKey"),
                now,
                now,
                retryCount,
                nextRetryAt,
                errorStatus,
                now,
                now
        );
        return vectorSyncErrorPort.save(meta);
    }

    @Override
    public List<VectorSyncErrorMeta> listByJobId(long jobId) {
        FieldValidator.requirePositive(jobId, "jobId");
        return vectorSyncErrorPort.findByJobId(jobId);
    }
}
