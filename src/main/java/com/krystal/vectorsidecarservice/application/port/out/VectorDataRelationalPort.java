package com.krystal.vectorsidecarservice.application.port.out;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface VectorDataRelationalPort {

    int insert(InsertRowCommand command);

    int update(UpdateRowCommand command);

    int delete(DeleteRowCommand command);

    Optional<VectorRow> findByPk(FindRowCommand command);

    record InsertRowCommand(
            String schemaName,
            String tableName,
            String pkColumn,
            Object pkValue,
            String vectorColumn,
            byte[] vectorBytes,
            String rowVersionColumn,
            Long rowVersion,
            Map<String, Object> scalarValues
    ) {
    }

    record UpdateRowCommand(
            String schemaName,
            String tableName,
            String pkColumn,
            Object pkValue,
            String vectorColumn,
            byte[] vectorBytes,
            String rowVersionColumn,
            Long rowVersion,
            Map<String, Object> scalarValues
    ) {
    }

    record DeleteRowCommand(
            String schemaName,
            String tableName,
            String pkColumn,
            Object pkValue
    ) {
    }

    record FindRowCommand(
            String schemaName,
            String tableName,
            String pkColumn,
            Object pkValue,
            String vectorColumn,
            String rowVersionColumn,
            List<String> scalarColumns
    ) {
    }

    record VectorRow(byte[] vectorBytes, Long rowVersion, Map<String, Object> scalarValues) {
    }
}
