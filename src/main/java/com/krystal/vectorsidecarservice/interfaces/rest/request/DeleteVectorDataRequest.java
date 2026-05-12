package com.krystal.vectorsidecarservice.interfaces.rest.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DeleteVectorDataRequest(
        String tenantId,
        String schemaName,
        @NotBlank String tableName,
        String vectorColumn,
        @NotNull Object pk
) {
}
