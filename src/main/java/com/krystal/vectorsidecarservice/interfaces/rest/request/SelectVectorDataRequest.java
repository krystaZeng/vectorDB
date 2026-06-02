package com.krystal.vectorsidecarservice.interfaces.rest.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record SelectVectorDataRequest(
        String tenantId,
        String schemaName,
        @NotBlank String tableName,
        String vectorColumn,
        List<String> select,
        @Valid List<@Valid SelectConditionRequest> where,
        @Valid List<@Valid OrderByRequest> orderBy,
        Integer limit,
        Integer offset,
        @Valid NearestRequest nearest
) {

    public record SelectConditionRequest(
            @NotBlank String field,
            @NotBlank String op,
            Object value,
            List<Object> values
    ) {
    }

    public record OrderByRequest(
            @NotBlank String field,
            String direction
    ) {
    }

    public record NearestRequest(
            @NotNull List<@NotNull Double> vector,
            Integer topK,
            Double scoreThreshold
    ) {
    }
}
