package com.krystal.vectorsidecarservice.interfaces.rest.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record RegisterVectorColumnRequest(
        String tenantId,
        String schemaName,
        @NotBlank String tableName,
        @NotBlank String pkColumn,
        @NotBlank String vectorColumn,
        @Min(1) int dimension,
        String metricType,
        String vectorEncoding,
        String syncMode,
        String status
) {
}
