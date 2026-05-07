package com.krystal.vectorsidecarservice.application.port.out;

public interface RelationalSchemaPort {

    DatabaseDialect databaseDialect();

    boolean tableExists(String schemaName, String tableName);

    void executeDdl(String ddl);

    enum DatabaseDialect {
        ALTIBASE,
        H2,
        GENERIC
    }
}
