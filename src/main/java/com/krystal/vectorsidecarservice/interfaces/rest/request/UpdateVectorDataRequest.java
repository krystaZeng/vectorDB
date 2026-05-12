package com.krystal.vectorsidecarservice.interfaces.rest.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public record UpdateVectorDataRequest(
        String tenantId,
        String schemaName,
        @NotBlank String tableName,
        String vectorColumn,
        @NotNull Object pk,
        List<@NotNull Double> vector,
        Map<String, Object> payload
) {
}
