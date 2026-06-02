package com.krystal.vectorsidecarservice.application.port.out;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface VectorDataRelationalPort {

    int insert(InsertRowCommand command);

    int update(UpdateRowCommand command);

    int delete(DeleteRowCommand command);

    Optional<VectorRow> findByPk(FindRowCommand command);

    List<RelationalRow> queryRows(QueryRowsCommand command);

    List<RelationalRow> findRowsByPks(FindRowsByPksCommand command);

    Optional<VectorRowState> findRowStateForUpdate(FindRowStateCommand command);

    enum VectorPresenceCondition {
        ANY,
        PRESENT,
        ABSENT
    }

    record InsertRowCommand(
            String schemaName,
            String tableName,
            String pkColumn,
            Object pkValue,
            String vectorColumn,
            byte[] vectorBytes,
            String rowVersionColumn,
            Long rowVersion,
            String vectorIndexVersionColumn,
            Long vectorIndexVersion,
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
            String vectorIndexVersionColumn,
            Long vectorIndexVersion,
            Map<String, Object> scalarValues,
            VectorPresenceCondition vectorPresenceCondition
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
            String vectorIndexVersionColumn,
            List<String> scalarColumns
    ) {
    }

    record VectorRow(byte[] vectorBytes, Long rowVersion, Long vectorIndexVersion, Map<String, Object> scalarValues) {
    }

    record Condition(
            String column,
            String op,
            Object value,
            List<Object> values
    ) {
    }

    record OrderBy(
            String column,
            String direction
    ) {
    }

    record QueryRowsCommand(
            String schemaName,
            String tableName,
            String pkColumn,
            List<String> selectColumns,
            List<Condition> conditions,
            List<OrderBy> orderBy,
            int limit,
            int offset
    ) {
    }

    record FindRowsByPksCommand(
            String schemaName,
            String tableName,
            String pkColumn,
            List<Object> pkValues,
            List<String> selectColumns,
            String vectorIndexVersionColumn
    ) {
    }

    record RelationalRow(Object pk, Long vectorIndexVersion, Map<String, Object> values) {
    }

    record FindRowStateCommand(
            String schemaName,
            String tableName,
            String pkColumn,
            Object pkValue,
            String vectorColumn,
            String rowVersionColumn
    ) {
    }

    record VectorRowState(boolean vectorPresent, Long rowVersion) {
    }
}
