package com.krystal.vectorsidecarservice.interfaces.rest.controller;

import com.krystal.vectorsidecarservice.application.port.in.UpsertVectorSyncProgressUseCase;
import com.krystal.vectorsidecarservice.interfaces.rest.request.UpsertVectorSyncProgressRequest;
import com.krystal.vectorsidecarservice.interfaces.rest.response.ApiResponse;
import com.krystal.vectorsidecarservice.interfaces.rest.response.VectorSyncProgressResponse;
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
@RequestMapping("/api/v1/vector-sync-progress")
@RequiredArgsConstructor
public class VectorSyncProgressController {

    private final UpsertVectorSyncProgressUseCase upsertVectorSyncProgressUseCase;

    @PostMapping
    public ApiResponse<VectorSyncProgressResponse> upsert(@Valid @RequestBody UpsertVectorSyncProgressRequest request) {
        var result = upsertVectorSyncProgressUseCase.upsert(
                new UpsertVectorSyncProgressUseCase.UpsertVectorSyncProgressCommand(
                        request.progressId(),
                        request.jobId(),
                        request.columnId(),
                        request.partitionId(),
                        request.lastPk(),
                        request.lastEventId(),
                        request.processedRows(),
                        request.successRows(),
                        request.failedRows(),
                        request.progressStatus(),
                        request.checkpointData()
                )
        );
        return ApiResponse.ok(VectorSyncProgressResponse.from(result));
    }

    @GetMapping
    public ApiResponse<List<VectorSyncProgressResponse>> list(@RequestParam long jobId) {
        List<VectorSyncProgressResponse> data = upsertVectorSyncProgressUseCase.listByJobId(jobId)
                .stream()
                .map(VectorSyncProgressResponse::from)
                .toList();
        return ApiResponse.ok(data);
    }
}
