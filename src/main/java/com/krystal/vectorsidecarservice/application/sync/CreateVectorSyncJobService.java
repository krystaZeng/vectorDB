package com.krystal.vectorsidecarservice.application.sync;

import com.krystal.vectorsidecarservice.application.port.in.CreateVectorSyncJobUseCase;
import com.krystal.vectorsidecarservice.application.port.out.VectorSyncJobPort;
import com.krystal.vectorsidecarservice.application.support.FieldValidator;
import com.krystal.vectorsidecarservice.common.id.IdGenerator;
import com.krystal.vectorsidecarservice.domain.sync.VectorSyncJobMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CreateVectorSyncJobService implements CreateVectorSyncJobUseCase {

    private final VectorSyncJobPort vectorSyncJobPort;
    private final IdGenerator idGenerator;

    @Override
    public VectorSyncJobMeta create(CreateVectorSyncJobCommand command) {
        FieldValidator.requirePositive(command.columnId(), "columnId");
        Instant now = Instant.now();
        VectorSyncJobMeta meta = new VectorSyncJobMeta(
                idGenerator.nextId(),
                command.columnId(),
                command.collectionId(),
                command.indexId(),
                FieldValidator.normalizeEnum(command.jobType(), Set.of("BACKFILL", "CDC", "REBUILD", "REPAIR"), "BACKFILL", "jobType"),
                "PENDING",
                FieldValidator.normalizeEnum(command.triggerType(), Set.of("MANUAL", "SCHEDULED", "SYSTEM"), "MANUAL", "triggerType"),
                FieldValidator.optionalText(command.idempotencyKey()),
                FieldValidator.optionalText(command.snapshotId()),
                FieldValidator.optionalText(command.sourceCursor()),
                FieldValidator.optionalText(command.startPk()),
                FieldValidator.optionalText(command.endPk()),
                FieldValidator.optionalText(command.workerId()),
                0,
                0,
                null,
                null,
                null,
                null,
                null,
                now,
                now
        );
        return vectorSyncJobPort.save(meta);
    }

    @Override
    public List<VectorSyncJobMeta> listByColumnId(long columnId) {
        FieldValidator.requirePositive(columnId, "columnId");
        return vectorSyncJobPort.findByColumnId(columnId);
    }
}
