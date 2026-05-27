package com.krystal.vectorsidecarservice.interfaces.rest.response;

import com.krystal.vectorsidecarservice.application.port.in.VerifyAndActivateVectorColumnUseCase;

public record VerifyAndActivateVectorColumnResponse(
        long columnId,
        String previousStatus,
        String status,
        boolean activated,
        String message
) {
    public static VerifyAndActivateVectorColumnResponse from(
            VerifyAndActivateVectorColumnUseCase.VerifyAndActivateVectorColumnResult result
    ) {
        return new VerifyAndActivateVectorColumnResponse(
                result.columnId(),
                result.previousStatus(),
                result.status(),
                result.activated(),
                result.message()
        );
    }
}
