package com.krystal.vectorsidecarservice.interfaces.rest.controller;

import com.krystal.vectorsidecarservice.application.port.in.RecordVectorSyncErrorUseCase;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.interfaces.rest.request.RecordVectorSyncErrorRequest;
import com.krystal.vectorsidecarservice.interfaces.rest.response.ApiResponse;
import com.krystal.vectorsidecarservice.interfaces.rest.response.VectorSyncErrorResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/vector-sync-errors")
@RequiredArgsConstructor
public class VectorSyncErrorController {

    private final RecordVectorSyncErrorUseCase recordVectorSyncErrorUseCase;

    @PostMapping
    public ApiResponse<VectorSyncErrorResponse> record(@Valid @RequestBody RecordVectorSyncErrorRequest request) {
        var result = recordVectorSyncErrorUseCase.record(
                new RecordVectorSyncErrorUseCase.RecordVectorSyncErrorCommand(
                        request.jobId(),
                        request.columnId(),
                        request.partitionId(),
                        request.sourcePk(),
                        request.opType(),
                        request.errorStage(),
                        request.errorCode(),
                        request.errorMessage(),
                        request.payloadSnapshot(),
                        request.dedupeKey(),
                        request.retryCount(),
                        parseInstant(request.nextRetryAt()),
                        request.errorStatus()
                )
        );
        return ApiResponse.ok(VectorSyncErrorResponse.from(result));
    }

    @GetMapping
    public ApiResponse<List<VectorSyncErrorResponse>> list(@RequestParam long jobId) {
        List<VectorSyncErrorResponse> data = recordVectorSyncErrorUseCase.listByJobId(jobId)
                .stream()
                .map(VectorSyncErrorResponse::from)
                .toList();
        return ApiResponse.ok(data);
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ex) {
            throw new BizException("nextRetryAt must be ISO-8601 instant");
        }
    }
}
