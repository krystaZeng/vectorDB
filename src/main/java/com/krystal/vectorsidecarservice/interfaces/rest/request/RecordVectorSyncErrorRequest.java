package com.krystal.vectorsidecarservice.interfaces.rest.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record RecordVectorSyncErrorRequest(
        @Min(1) long jobId,
        @Min(1) long columnId,
        String partitionId,
        @NotBlank String sourcePk,
        String opType,
        String errorStage,
        @NotBlank String errorCode,
        @NotBlank String errorMessage,
        String payloadSnapshot,
        @NotBlank String dedupeKey,
        Integer retryCount,
        String nextRetryAt,
        String errorStatus
) {
}
