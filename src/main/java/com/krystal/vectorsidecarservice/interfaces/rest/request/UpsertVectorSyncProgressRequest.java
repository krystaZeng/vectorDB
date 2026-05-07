package com.krystal.vectorsidecarservice.interfaces.rest.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record UpsertVectorSyncProgressRequest(
        @Min(1) Long progressId,
        @Min(1) long jobId,
        @Min(1) long columnId,
        @NotBlank String partitionId,
        String lastPk,
        String lastEventId,
        @Min(0) Long processedRows,
        @Min(0) Long successRows,
        @Min(0) Long failedRows,
        String progressStatus,
        String checkpointData
) {
}
