package com.krystal.vectorsidecarservice.application.port.in;

import java.util.List;
import java.util.Map;

public interface SelectVectorDataUseCase {

    SelectVectorDataResult select(SelectVectorDataCommand command);

    record SelectVectorDataCommand(
            String tenantId,
            String schemaName,
            String tableName,
            String vectorColumn,
            List<String> select,
            List<SelectCondition> where,
            List<OrderBy> orderBy,
            Integer limit,
            Integer offset,
            Nearest nearest
    ) {
    }

    record SelectCondition(
            String field,
            String op,
            Object value,
            List<Object> values,
            boolean valueProvided,
            boolean valuesProvided
    ) {
    }

    record OrderBy(
            String field,
            String direction
    ) {
    }

    record Nearest(
            List<Double> vector,
            Integer topK,
            Double scoreThreshold
    ) {
    }

    record SelectVectorDataResult(
            String executionPlan,
            String consistency,
            List<SelectRowResult> rows,
            SelectDiagnostics diagnostics
    ) {
    }

    record SelectRowResult(
            Object pk,
            Double score,
            Long vectorIndexVersion,
            Map<String, Object> values
    ) {
    }

    record SelectDiagnostics(
            int qdrantHitCount,
            int relationalRowCount,
            int staleDeletedHitCount,
            int staleVersionHitCount,
            int malformedHitCount,
            int returnedRowCount
    ) {
    }
}
