package com.krystal.vectorsidecarservice.interfaces.rest.response;

import com.krystal.vectorsidecarservice.application.port.in.SelectVectorDataUseCase;

import java.util.List;
import java.util.Map;

public record SelectVectorDataResponse(
        String executionPlan,
        String consistency,
        List<SelectRowResponse> rows,
        SelectDiagnosticsResponse diagnostics
) {
    public static SelectVectorDataResponse from(SelectVectorDataUseCase.SelectVectorDataResult result) {
        return new SelectVectorDataResponse(
                result.executionPlan(),
                result.consistency(),
                result.rows().stream()
                        .map(SelectRowResponse::from)
                        .toList(),
                SelectDiagnosticsResponse.from(result.diagnostics())
        );
    }

    public record SelectRowResponse(
            Object pk,
            Double score,
            Long vectorIndexVersion,
            Map<String, Object> values
    ) {
        static SelectRowResponse from(SelectVectorDataUseCase.SelectRowResult result) {
            return new SelectRowResponse(
                    result.pk(),
                    result.score(),
                    result.vectorIndexVersion(),
                    result.values()
            );
        }
    }

    public record SelectDiagnosticsResponse(
            int qdrantHitCount,
            int relationalRowCount,
            int staleDeletedHitCount,
            int staleVersionHitCount,
            int malformedHitCount,
            int returnedRowCount
    ) {
        static SelectDiagnosticsResponse from(SelectVectorDataUseCase.SelectDiagnostics diagnostics) {
            return new SelectDiagnosticsResponse(
                    diagnostics.qdrantHitCount(),
                    diagnostics.relationalRowCount(),
                    diagnostics.staleDeletedHitCount(),
                    diagnostics.staleVersionHitCount(),
                    diagnostics.malformedHitCount(),
                    diagnostics.returnedRowCount()
            );
        }
    }
}
