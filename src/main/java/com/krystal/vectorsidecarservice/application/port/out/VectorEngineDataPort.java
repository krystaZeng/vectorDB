package com.krystal.vectorsidecarservice.application.port.out;

import java.util.List;
import java.util.Map;

public interface VectorEngineDataPort {

    String engineType();

    UpsertPointResult upsertPoint(UpsertPointCommand command);

    enum UpsertPointStatus {
        UPSERTED,
        SKIPPED_DISABLED
    }

    record UpsertPointResult(UpsertPointStatus status, String message) {

        public static UpsertPointResult upserted(String message) {
            return new UpsertPointResult(UpsertPointStatus.UPSERTED, message);
        }

        public static UpsertPointResult skippedDisabled(String message) {
            return new UpsertPointResult(UpsertPointStatus.SKIPPED_DISABLED, message);
        }
    }

    record UpsertPointCommand(
            String collectionName,
            String vectorName,
            Object pointId,
            List<Float> vector,
            Map<String, Object> payload
    ) {
    }
}
