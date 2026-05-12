package com.krystal.vectorsidecarservice.application.port.out;

import com.krystal.vectorsidecarservice.common.exception.BizException;

import java.util.List;

public interface RelationalSchemaPort {

    DatabaseDialect databaseDialect();

    boolean tableExists(String schemaName, String tableName);

    boolean columnExists(String schemaName, String tableName, String columnName);

    void validateTableDefinition(TableDefinition definition);

    void executeDdl(String ddl);

    record TableDefinition(
            String schemaName,
            String tableName,
            String primaryKeyName,
            List<ColumnDefinition> columns
    ) {
    }

    record ColumnDefinition(
            String name,
            String type,
            Integer length,
            boolean nullable
    ) {
    }

    enum DatabaseDialect {
        ALTIBASE,
        H2,
        GENERIC
    }

    enum DdlFailureKind {
        OBJECT_ALREADY_EXISTS,
        UNKNOWN_EXECUTION_STATE,
        NON_RETRYABLE
    }

    class DdlExecutionException extends BizException {

        private final DdlFailureKind failureKind;

        public DdlExecutionException(DdlFailureKind failureKind, String message, Throwable cause) {
            super(message, cause);
            this.failureKind = failureKind;
        }

        public DdlFailureKind failureKind() {
            return failureKind;
        }
    }
}
