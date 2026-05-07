package com.krystal.vectorsidecarservice.interfaces.rest.request;

import jakarta.validation.constraints.Min;

public record CreateVectorSyncJobRequest(
        @Min(1) long columnId,
        @Min(1) Long collectionId,
        @Min(1) Long indexId,
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
