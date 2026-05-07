package com.krystal.vectorsidecarservice.application.port.in;

import com.krystal.vectorsidecarservice.domain.sync.VectorSyncJobMeta;

import java.util.List;

public interface CreateVectorSyncJobUseCase {

    VectorSyncJobMeta create(CreateVectorSyncJobCommand command);

    List<VectorSyncJobMeta> listByColumnId(long columnId);

    record CreateVectorSyncJobCommand(
            long columnId,
            Long collectionId,
            Long indexId,
            String jobType,
            String triggerType,
            String idempotencyKey,
            String snapshotId,
            String sourceCursor,
            String startPk,
            String endPk,
            String workerId
    ) {
    }
}
