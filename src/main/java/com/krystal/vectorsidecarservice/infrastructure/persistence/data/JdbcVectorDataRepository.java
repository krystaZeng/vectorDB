package com.krystal.vectorsidecarservice.infrastructure.persistence.data;

import com.krystal.vectorsidecarservice.application.port.out.VectorDataRelationalPort;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Repository
@RequiredArgsConstructor
public class JdbcVectorDataRepository implements VectorDataRelationalPort {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    private final JdbcTemplate jdbcTemplate;

    @Override
    public int insert(InsertRowCommand command) {
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        columns.add(identifier(command.pkColumn(), "pkColumn"));
        values.add(command.pkValue());
        if (command.vectorBytes() != null) {
            columns.add(identifier(command.vectorColumn(), "vectorColumn"));
            values.add(command.vectorBytes());
        }
        if (command.rowVersion() != null) {
            columns.add(identifier(command.rowVersionColumn(), "rowVersionColumn"));
            values.add(command.rowVersion());
        }
        for (Map.Entry<String, Object> entry : command.scalarValues().entrySet()) {
            columns.add(identifier(entry.getKey(), "scalar column"));
            values.add(entry.getValue());
        }

        String placeholders = String.join(", ", columns.stream().map(column -> "?").toList());
        String sql = "INSERT INTO "
                + identifier(command.schemaName(), "schemaName")
                + "."
                + identifier(command.tableName(), "tableName")
                + " ("
                + String.join(", ", columns)
                + ") VALUES ("
                + placeholders
                + ")";
        try {
            return jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(sql);
                bindValues(ps, values);
                return ps;
            });
        } catch (DataAccessException ex) {
            throw new BizException("failed to insert vector row", ex);
        }
    }

    @Override
    public int update(UpdateRowCommand command) {
        List<String> assignments = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        if (command.vectorBytes() != null) {
            assignments.add(identifier(command.vectorColumn(), "vectorColumn") + " = ?");
            values.add(command.vectorBytes());
        }
        for (Map.Entry<String, Object> entry : command.scalarValues().entrySet()) {
            assignments.add(identifier(entry.getKey(), "scalar column") + " = ?");
            values.add(entry.getValue());
        }
        if (command.rowVersionColumn() != null) {
            String rowVersionColumn = identifier(command.rowVersionColumn(), "rowVersionColumn");
            if (command.rowVersion() == null) {
                assignments.add(rowVersionColumn + " = " + rowVersionColumn + " + 1");
            } else {
                assignments.add(rowVersionColumn + " = ?");
                values.add(command.rowVersion());
            }
        }
        if (assignments.isEmpty()) {
            throw new BizException("update must change at least one column");
        }

        String sql = "UPDATE "
                + identifier(command.schemaName(), "schemaName")
                + "."
                + identifier(command.tableName(), "tableName")
                + " SET "
                + String.join(", ", assignments)
                + " WHERE "
                + identifier(command.pkColumn(), "pkColumn")
                + " = ?";
        values.add(command.pkValue());
        try {
            return jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(sql);
                bindValues(ps, values);
                return ps;
            });
        } catch (DataAccessException ex) {
            throw new BizException("failed to update vector row", ex);
        }
    }

    @Override
    public int delete(DeleteRowCommand command) {
        String sql = "DELETE FROM "
                + identifier(command.schemaName(), "schemaName")
                + "."
                + identifier(command.tableName(), "tableName")
                + " WHERE "
                + identifier(command.pkColumn(), "pkColumn")
                + " = ?";
        try {
            return jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(sql);
                ps.setObject(1, command.pkValue());
                return ps;
            });
        } catch (DataAccessException ex) {
            throw new BizException("failed to delete vector row", ex);
        }
    }

    @Override
    public Optional<VectorRow> findByPk(FindRowCommand command) {
        String vectorColumn = identifier(command.vectorColumn(), "vectorColumn");
        String rowVersionColumn = command.rowVersionColumn() == null
                ? null
                : identifier(command.rowVersionColumn(), "rowVersionColumn");
        List<String> scalarColumns = command.scalarColumns() == null ? List.of() : command.scalarColumns()
                .stream()
                .map(column -> identifier(column, "scalar column"))
                .distinct()
                .toList();
        List<String> selectColumns = new ArrayList<>();
        selectColumns.add(vectorColumn);
        if (rowVersionColumn != null) {
            selectColumns.add(rowVersionColumn);
        }
        selectColumns.addAll(scalarColumns);
        String sql = "SELECT "
                + String.join(", ", selectColumns)
                + " FROM "
                + identifier(command.schemaName(), "schemaName")
                + "."
                + identifier(command.tableName(), "tableName")
                + " WHERE "
                + identifier(command.pkColumn(), "pkColumn")
                + " = ?";

        return jdbcTemplate.query(connection -> {
                    PreparedStatement ps = connection.prepareStatement(sql);
                    ps.setObject(1, command.pkValue());
                    return ps;
                }, (rs, rowNum) -> {
                    Map<String, Object> scalarValues = new LinkedHashMap<>();
                    for (String scalarColumn : scalarColumns) {
                        scalarValues.put(scalarColumn, rs.getObject(scalarColumn));
                    }
                    Long rowVersion = null;
                    if (rowVersionColumn != null) {
                        Number value = (Number) rs.getObject(rowVersionColumn);
                        rowVersion = value == null ? null : value.longValue();
                    }
                    return new VectorRow(rs.getBytes(vectorColumn), rowVersion, scalarValues);
                })
                .stream()
                .findFirst();
    }

    private void bindValues(PreparedStatement ps, List<Object> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value instanceof byte[] bytes) {
                ps.setBytes(i + 1, bytes);
            } else {
                ps.setObject(i + 1, value);
            }
        }
    }

    private String identifier(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BizException(fieldName + " must not be blank");
        }
        String normalized = value.trim();
        if (!IDENTIFIER_PATTERN.matcher(normalized).matches()) {
            throw new BizException(fieldName + " must match [A-Za-z][A-Za-z0-9_]*");
        }
        return normalized;
    }
}
