package com.krystal.vectorsidecarservice.interfaces.rest.controller;

import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorCollectionUseCase;
import com.krystal.vectorsidecarservice.interfaces.rest.request.RegisterVectorCollectionRequest;
import com.krystal.vectorsidecarservice.interfaces.rest.response.ApiResponse;
import com.krystal.vectorsidecarservice.interfaces.rest.response.VectorCollectionResponse;
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
@RequestMapping("/api/v1/vector-collections")
@RequiredArgsConstructor
public class VectorCollectionController {

    private final RegisterVectorCollectionUseCase registerVectorCollectionUseCase;

    @PostMapping
    public ApiResponse<VectorCollectionResponse> register(@Valid @RequestBody RegisterVectorCollectionRequest request) {
        var result = registerVectorCollectionUseCase.register(
                new RegisterVectorCollectionUseCase.RegisterVectorCollectionCommand(
                        request.columnId(),
                        request.engineType(),
                        request.namespaceName(),
                        request.collectionName(),
                        request.aliasName(),
                        request.collectionVersion(),
                        request.qdrantVectorName(),
                        request.vectorDim(),
                        request.distanceMetric(),
                        request.qdrantIdType(),
                        request.servingState(),
                        request.collectionStatus(),
                        request.shardNumber(),
                        request.replicationFactor(),
                        request.writeConsistencyFactor(),
                        request.onDiskPayload(),
                        request.hnswConfigJson(),
                        request.quantizationConfigJson(),
                        request.collectionConfigJson()
                )
        );
        return ApiResponse.ok(VectorCollectionResponse.from(result));
    }

    @GetMapping
    public ApiResponse<List<VectorCollectionResponse>> list(@RequestParam long columnId) {
        List<VectorCollectionResponse> data = registerVectorCollectionUseCase.listByColumnId(columnId)
                .stream()
                .map(VectorCollectionResponse::from)
                .toList();
        return ApiResponse.ok(data);
    }
}
