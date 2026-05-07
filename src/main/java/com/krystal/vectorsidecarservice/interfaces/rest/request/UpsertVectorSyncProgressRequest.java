package com.krystal.vectorsidecarservice.interfaces.rest.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record UpsertVectorSyncProgressRequest(
        Long progressId,
        @Min(1) long jobId,
        @Min(1) long columnId,
        @NotBlank String partitionId,
        String lastPk,
        String lastEventId,
        Long processedRows,
        Long successRows,
        Long failedRows,
        String progressStatus,
        String checkpointData
) {
}
