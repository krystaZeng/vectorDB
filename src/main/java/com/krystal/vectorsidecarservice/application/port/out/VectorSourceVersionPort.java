package com.krystal.vectorsidecarservice.application.port.out;

import java.time.Instant;

public interface VectorSourceVersionPort {

    long nextVersion(NextVersionCommand command);

    record NextVersionCommand(
            String tenantId,
            long columnId,
            String sourcePk,
            String eventKey,
            Instant now
    ) {
    }
}
