package com.krystal.vectorsidecarservice.application.port.out;

import java.util.List;
import java.util.Map;

public interface VectorEngineDataPort {

    String engineType();

    UpsertPointResult upsertPoint(UpsertPointCommand command);

    DeletePointResult deletePoint(DeletePointCommand command);

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

    enum DeletePointStatus {
        DELETED,
        SKIPPED_DISABLED
    }

    record DeletePointResult(DeletePointStatus status, String message) {

        public static DeletePointResult deleted(String message) {
            return new DeletePointResult(DeletePointStatus.DELETED, message);
        }

        public static DeletePointResult skippedDisabled(String message) {
            return new DeletePointResult(DeletePointStatus.SKIPPED_DISABLED, message);
        }
    }

    record DeletePointCommand(
            String collectionName,
            Object pointId
    ) {
    }
}
