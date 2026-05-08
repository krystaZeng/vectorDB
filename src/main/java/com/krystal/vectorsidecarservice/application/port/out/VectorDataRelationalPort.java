package com.krystal.vectorsidecarservice.application.port.out;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface VectorDataRelationalPort {

    int insert(InsertRowCommand command);

    Optional<VectorRow> findByPk(FindRowCommand command);

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

    record FindRowCommand(
            String schemaName,
            String tableName,
            String pkColumn,
            Object pkValue,
            String vectorColumn,
            List<String> scalarColumns
    ) {
    }

    record VectorRow(byte[] vectorBytes, Map<String, Object> scalarValues) {
    }
}
