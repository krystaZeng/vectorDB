package com.krystal.vectorsidecarservice.application.port.in;

public interface VerifyAndActivateVectorColumnUseCase {

    VerifyAndActivateVectorColumnResult verifyAndActivate(VerifyAndActivateVectorColumnCommand command);

    record VerifyAndActivateVectorColumnCommand(long columnId) {
    }

    record VerifyAndActivateVectorColumnResult(
            long columnId,
            String previousStatus,
            String status,
            boolean activated,
            String message
    ) {
    }
}
