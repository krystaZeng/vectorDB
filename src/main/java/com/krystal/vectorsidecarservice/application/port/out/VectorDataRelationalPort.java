package com.krystal.vectorsidecarservice.application.port.out;

import java.util.Map;

public interface VectorDataRelationalPort {

    int insert(InsertRowCommand command);

    record InsertRowCommand(
            String schemaName,
            String tableName,
            String pkColumn,
            Object pkValue,
            String vectorColumn,
            byte[] vectorBytes,
            Map<String, Object> scalarValues
    ) {
    }
}
