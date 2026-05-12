package com.krystal.vectorsidecarservice.interfaces.rest.controller;

import com.krystal.vectorsidecarservice.application.port.in.ListVectorOutboxEventUseCase;
import com.krystal.vectorsidecarservice.application.port.in.RetryVectorOutboxEventUseCase;
import com.krystal.vectorsidecarservice.interfaces.rest.response.ApiResponse;
import com.krystal.vectorsidecarservice.interfaces.rest.response.VectorOutboxEventResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/vector-outbox-events")
@RequiredArgsConstructor
public class VectorOutboxEventController {

    private final ListVectorOutboxEventUseCase listVectorOutboxEventUseCase;
    private final RetryVectorOutboxEventUseCase retryVectorOutboxEventUseCase;

    @GetMapping
    public ApiResponse<List<VectorOutboxEventResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long columnId,
            @RequestParam(required = false) Integer limit
    ) {
        List<VectorOutboxEventResponse> data = listVectorOutboxEventUseCase.list(
                        new ListVectorOutboxEventUseCase.ListVectorOutboxEventQuery(status, columnId, limit)
                )
                .stream()
                .map(VectorOutboxEventResponse::from)
                .toList();
        return ApiResponse.ok(data);
    }

    @PostMapping("/{eventId}/retry")
    public ApiResponse<VectorOutboxEventResponse> retry(@PathVariable long eventId) {
        return ApiResponse.ok(VectorOutboxEventResponse.from(retryVectorOutboxEventUseCase.retryDead(eventId)));
    }
}
