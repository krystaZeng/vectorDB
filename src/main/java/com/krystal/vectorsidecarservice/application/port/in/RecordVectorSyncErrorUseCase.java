package com.krystal.vectorsidecarservice.application.port.in;

import com.krystal.vectorsidecarservice.domain.sync.VectorSyncErrorMeta;

import java.time.Instant;
import java.util.List;

public interface RecordVectorSyncErrorUseCase {

    VectorSyncErrorMeta record(RecordVectorSyncErrorCommand command);

    List<VectorSyncErrorMeta> listByJobId(long jobId);

    record RecordVectorSyncErrorCommand(
            long jobId,
            long columnId,
            String partitionId,
            String sourcePk,
            String opType,
            String errorStage,
            String errorCode,
            String errorMessage,
            String payloadSnapshot,
            String dedupeKey,
            Integer retryCount,
            Instant nextRetryAt,
            String errorStatus
    ) {
    }
}
