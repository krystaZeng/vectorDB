package com.krystal.vectorsidecarservice.application.port.out;

public interface VectorEngineAdminPort {

    String engineType();

    EnsureResult ensureCollection(EnsureCollectionCommand command);

    EnsureResult ensureAlias(EnsureAliasCommand command);

    EnsureResult ensureIndex(EnsureIndexCommand command);

    enum EnsureStatus {
        CREATED,
        ALREADY_EXISTS,
        UPDATED,
        SKIPPED_DISABLED,
        SKIPPED_NOOP
    }

    record EnsureResult(EnsureStatus status, String message) {

        public static EnsureResult created(String message) {
            return new EnsureResult(EnsureStatus.CREATED, message);
        }

        public static EnsureResult alreadyExists(String message) {
            return new EnsureResult(EnsureStatus.ALREADY_EXISTS, message);
        }

        public static EnsureResult updated(String message) {
            return new EnsureResult(EnsureStatus.UPDATED, message);
        }

        public static EnsureResult skippedDisabled(String message) {
            return new EnsureResult(EnsureStatus.SKIPPED_DISABLED, message);
        }

        public static EnsureResult skippedNoop(String message) {
            return new EnsureResult(EnsureStatus.SKIPPED_NOOP, message);
        }

        public boolean isSkippedDisabled() {
            return status == EnsureStatus.SKIPPED_DISABLED;
        }
    }

    record EnsureCollectionCommand(
            String collectionName,
            int vectorDim,
            String distanceMetric,
            String qdrantVectorName,
            String onDiskPayload,
            String hnswConfigJson,
            String quantizationConfigJson,
            String collectionConfigJson
    ) {
    }

    record EnsureAliasCommand(
            String aliasName,
            String collectionName
    ) {
    }

    record EnsureIndexCommand(
            String collectionName,
            String profileName,
            String indexType,
            String metricType,
            String indexParamsJson,
            String searchParamsJson,
            String payloadIndexJson
    ) {
    }
}
