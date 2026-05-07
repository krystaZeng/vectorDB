package com.krystal.vectorsidecarservice.interfaces.rest.controller;

import com.krystal.vectorsidecarservice.application.port.in.CreateVectorSyncJobUseCase;
import com.krystal.vectorsidecarservice.interfaces.rest.request.CreateVectorSyncJobRequest;
import com.krystal.vectorsidecarservice.interfaces.rest.response.ApiResponse;
import com.krystal.vectorsidecarservice.interfaces.rest.response.VectorSyncJobResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/vector-sync-jobs")
@RequiredArgsConstructor
public class VectorSyncJobController {

    private final CreateVectorSyncJobUseCase createVectorSyncJobUseCase;

    @PostMapping
    public ApiResponse<VectorSyncJobResponse> create(@Valid @RequestBody CreateVectorSyncJobRequest request) {
        var result = createVectorSyncJobUseCase.create(
                new CreateVectorSyncJobUseCase.CreateVectorSyncJobCommand(
                        request.columnId(),
                        request.collectionId(),
                        request.indexId(),
                        request.jobType(),
                        request.triggerType(),
                        request.idempotencyKey(),
                        request.snapshotId(),
                        request.sourceCursor(),
                        request.startPk(),
                        request.endPk(),
                        request.workerId()
                )
        );
        return ApiResponse.ok(VectorSyncJobResponse.from(result));
    }

    @GetMapping
    public ApiResponse<List<VectorSyncJobResponse>> list(@RequestParam long columnId) {
        List<VectorSyncJobResponse> data = createVectorSyncJobUseCase.listByColumnId(columnId)
                .stream()
                .map(VectorSyncJobResponse::from)
                .toList();
        return ApiResponse.ok(data);
    }
}
