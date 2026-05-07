package com.krystal.vectorsidecarservice.application.sync;

import com.krystal.vectorsidecarservice.application.port.in.UpsertVectorSyncProgressUseCase;
import com.krystal.vectorsidecarservice.application.port.out.IdGeneratorPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorSyncProgressPort;
import com.krystal.vectorsidecarservice.application.support.FieldValidator;
import com.krystal.vectorsidecarservice.application.support.VectorMetadataReferenceGuard;
import com.krystal.vectorsidecarservice.domain.sync.VectorSyncProgressMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UpsertVectorSyncProgressService implements UpsertVectorSyncProgressUseCase {

    private static final int MAX_CHECKPOINT_DATA_LEN = 2000;

    private final VectorSyncProgressPort vectorSyncProgressPort;
    private final IdGeneratorPort idGenerator;
    private final VectorMetadataReferenceGuard referenceGuard;

    @Override
    public VectorSyncProgressMeta upsert(UpsertVectorSyncProgressCommand command) {
        if (command.progressId() != null) {
            FieldValidator.requirePositive(command.progressId(), "progressId");
        }
        FieldValidator.requirePositive(command.jobId(), "jobId");
        FieldValidator.requirePositive(command.columnId(), "columnId");
        long processedRows = FieldValidator.nonNegativeOrDefault(command.processedRows(), 0L, "processedRows");
        long successRows = FieldValidator.nonNegativeOrDefault(command.successRows(), 0L, "successRows");
        long failedRows = FieldValidator.nonNegativeOrDefault(command.failedRows(), 0L, "failedRows");
        referenceGuard.requireSyncJobForColumn(command.jobId(), command.columnId());
        Instant now = Instant.now();
        VectorSyncProgressMeta meta = new VectorSyncProgressMeta(
                command.progressId() == null ? idGenerator.nextId() : command.progressId(),
                command.jobId(),
                command.columnId(),
                FieldValidator.requireText(command.partitionId(), "partitionId"),
                FieldValidator.optionalText(command.lastPk()),
                now,
                FieldValidator.optionalText(command.lastEventId()),
                processedRows,
                successRows,
                failedRows,
                now,
                FieldValidator.normalizeEnum(command.progressStatus(), Set.of("RUNNING", "STALLED", "DONE", "FAILED"), "RUNNING", "progressStatus"),
                FieldValidator.optionalTextWithMaxLength(command.checkpointData(), "checkpointData", MAX_CHECKPOINT_DATA_LEN),
                now
        );
        return vectorSyncProgressPort.upsert(meta);
    }

    @Override
    public List<VectorSyncProgressMeta> listByJobId(long jobId) {
        FieldValidator.requirePositive(jobId, "jobId");
        return vectorSyncProgressPort.findByJobId(jobId);
    }
}
