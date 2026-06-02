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
        if (command.vectorIndexVersionColumn() != null && command.vectorIndexVersion() != null) {
            columns.add(identifier(command.vectorIndexVersionColumn(), "vectorIndexVersionColumn"));
            values.add(command.vectorIndexVersion());
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
        if (command.vectorIndexVersionColumn() != null && command.vectorIndexVersion() != null) {
            assignments.add(identifier(command.vectorIndexVersionColumn(), "vectorIndexVersionColumn") + " = ?");
            values.add(command.vectorIndexVersion());
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
                + " = ?"
                + vectorPresencePredicate(command);
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
        String vectorIndexVersionColumn = command.vectorIndexVersionColumn() == null
                ? null
                : identifier(command.vectorIndexVersionColumn(), "vectorIndexVersionColumn");
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
        if (vectorIndexVersionColumn != null) {
            selectColumns.add(vectorIndexVersionColumn);
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
                    Long vectorIndexVersion = null;
                    if (vectorIndexVersionColumn != null) {
                        Number value = (Number) rs.getObject(vectorIndexVersionColumn);
                        vectorIndexVersion = value == null ? null : value.longValue();
                    }
                    return new VectorRow(rs.getBytes(vectorColumn), rowVersion, vectorIndexVersion, scalarValues);
                })
                .stream()
                .findFirst();
    }

    @Override
    public List<RelationalRow> queryRows(QueryRowsCommand command) {
        String pkColumn = identifier(command.pkColumn(), "pkColumn");
        List<String> valueColumns = normalizedColumns(command.selectColumns(), "select column");
        List<String> sqlColumns = sqlColumns(pkColumn, null, valueColumns);
        List<Object> values = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(String.join(", ", sqlColumns))
                .append(" FROM ")
                .append(identifier(command.schemaName(), "schemaName"))
                .append(".")
                .append(identifier(command.tableName(), "tableName"));
        appendWhere(sql, values, command.conditions());
        appendOrderBy(sql, command.orderBy());
        sql.append(" LIMIT ").append(requireNonNegative(command.limit(), "limit"));
        sql.append(" OFFSET ").append(requireNonNegative(command.offset(), "offset"));
        return jdbcTemplate.query(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql.toString());
            bindValues(ps, values);
            return ps;
        }, (rs, rowNum) -> {
            Object pk = rs.getObject(pkColumn);
            return new RelationalRow(pk, null, rowValues(rs, pkColumn, pk, valueColumns));
        });
    }

    @Override
    public List<RelationalRow> findRowsByPks(FindRowsByPksCommand command) {
        if (command.pkValues() == null || command.pkValues().isEmpty()) {
            return List.of();
        }
        String pkColumn = identifier(command.pkColumn(), "pkColumn");
        String vectorIndexVersionColumn = command.vectorIndexVersionColumn() == null
                ? null
                : identifier(command.vectorIndexVersionColumn(), "vectorIndexVersionColumn");
        List<String> valueColumns = normalizedColumns(command.selectColumns(), "select column");
        List<String> sqlColumns = sqlColumns(pkColumn, vectorIndexVersionColumn, valueColumns);
        String placeholders = String.join(", ", command.pkValues().stream().map(value -> "?").toList());
        String sql = "SELECT "
                + String.join(", ", sqlColumns)
                + " FROM "
                + identifier(command.schemaName(), "schemaName")
                + "."
                + identifier(command.tableName(), "tableName")
                + " WHERE "
                + pkColumn
                + " IN ("
                + placeholders
                + ")";
        return jdbcTemplate.query(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql);
            bindValues(ps, command.pkValues());
            return ps;
        }, (rs, rowNum) -> {
            Object pk = rs.getObject(pkColumn);
            Long vectorIndexVersion = null;
            if (vectorIndexVersionColumn != null) {
                Number value = (Number) rs.getObject(vectorIndexVersionColumn);
                vectorIndexVersion = value == null ? null : value.longValue();
            }
            return new RelationalRow(pk, vectorIndexVersion, rowValues(rs, pkColumn, pk, valueColumns));
        });
    }

    @Override
    public Optional<VectorRowState> findRowStateForUpdate(FindRowStateCommand command) {
        String vectorColumn = identifier(command.vectorColumn(), "vectorColumn");
        String rowVersionColumn = command.rowVersionColumn() == null
                ? null
                : identifier(command.rowVersionColumn(), "rowVersionColumn");
        List<String> selectColumns = new ArrayList<>();
        selectColumns.add("CASE WHEN " + vectorColumn + " IS NULL THEN 0 ELSE 1 END AS VECTOR_PRESENT");
        if (rowVersionColumn != null) {
            selectColumns.add(rowVersionColumn);
        }
        String sql = "SELECT "
                + String.join(", ", selectColumns)
                + " FROM "
                + identifier(command.schemaName(), "schemaName")
                + "."
                + identifier(command.tableName(), "tableName")
                + " WHERE "
                + identifier(command.pkColumn(), "pkColumn")
                + " = ? FOR UPDATE";

        return jdbcTemplate.query(connection -> {
                    PreparedStatement ps = connection.prepareStatement(sql);
                    ps.setObject(1, command.pkValue());
                    return ps;
                }, (rs, rowNum) -> {
                    Long rowVersion = null;
                    if (rowVersionColumn != null) {
                        Number value = (Number) rs.getObject(rowVersionColumn);
                        rowVersion = value == null ? null : value.longValue();
                    }
                    return new VectorRowState(rs.getInt("VECTOR_PRESENT") == 1, rowVersion);
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

    private List<String> normalizedColumns(List<String> columns, String fieldName) {
        if (columns == null || columns.isEmpty()) {
            return List.of();
        }
        return columns.stream()
                .map(column -> identifier(column, fieldName))
                .distinct()
                .toList();
    }

    private List<String> sqlColumns(String pkColumn, String vectorIndexVersionColumn, List<String> valueColumns) {
        List<String> columns = new ArrayList<>();
        columns.add(pkColumn);
        if (vectorIndexVersionColumn != null && !vectorIndexVersionColumn.equals(pkColumn)) {
            columns.add(vectorIndexVersionColumn);
        }
        for (String valueColumn : valueColumns) {
            if (!columns.contains(valueColumn)) {
                columns.add(valueColumn);
            }
        }
        return columns;
    }

    private Map<String, Object> rowValues(
            java.sql.ResultSet rs,
            String pkColumn,
            Object pk,
            List<String> valueColumns
    ) throws SQLException {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String column : valueColumns) {
            values.put(column, column.equals(pkColumn) ? pk : rs.getObject(column));
        }
        return values;
    }

    private void appendWhere(StringBuilder sql, List<Object> values, List<Condition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return;
        }
        List<String> predicates = new ArrayList<>();
        for (Condition condition : conditions) {
            String column = identifier(condition.column(), "where field");
            String op = condition.op() == null ? "" : condition.op().trim().toUpperCase();
            switch (op) {
                case "EQ" -> {
                    predicates.add(column + " = ?");
                    values.add(condition.value());
                }
                case "IN" -> {
                    List<Object> inValues = condition.values() == null ? List.of() : condition.values();
                    if (inValues.isEmpty()) {
                        throw new BizException("where IN values must not be empty: " + column);
                    }
                    predicates.add(column + " IN (" + String.join(", ", inValues.stream().map(value -> "?").toList()) + ")");
                    values.addAll(inValues);
                }
                case "GT", "GTE", "LT", "LTE" -> {
                    predicates.add(column + " " + relationalOperator(op) + " ?");
                    values.add(condition.value());
                }
                case "IS_NULL" -> predicates.add(column + " IS NULL");
                case "IS_NOT_NULL" -> predicates.add(column + " IS NOT NULL");
                default -> throw new BizException("unsupported where op: " + condition.op());
            }
        }
        sql.append(" WHERE ").append(String.join(" AND ", predicates));
    }

    private String relationalOperator(String op) {
        return switch (op) {
            case "GT" -> ">";
            case "GTE" -> ">=";
            case "LT" -> "<";
            case "LTE" -> "<=";
            default -> throw new BizException("unsupported where op: " + op);
        };
    }

    private void appendOrderBy(StringBuilder sql, List<OrderBy> orderBy) {
        if (orderBy == null || orderBy.isEmpty()) {
            return;
        }
        List<String> clauses = new ArrayList<>();
        for (OrderBy order : orderBy) {
            String column = identifier(order.column(), "orderBy field");
            String direction = order.direction() == null ? "ASC" : order.direction().trim().toUpperCase();
            if (!direction.equals("ASC") && !direction.equals("DESC")) {
                throw new BizException("orderBy direction must be ASC or DESC");
            }
            clauses.add(column + " " + direction);
        }
        sql.append(" ORDER BY ").append(String.join(", ", clauses));
    }

    private int requireNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new BizException(fieldName + " must not be negative");
        }
        return value;
    }

    private String vectorPresencePredicate(UpdateRowCommand command) {
        VectorPresenceCondition condition = command.vectorPresenceCondition() == null
                ? VectorPresenceCondition.ANY
                : command.vectorPresenceCondition();
        return switch (condition) {
            case ANY -> "";
            case PRESENT -> " AND " + identifier(command.vectorColumn(), "vectorColumn") + " IS NOT NULL";
            case ABSENT -> " AND " + identifier(command.vectorColumn(), "vectorColumn") + " IS NULL";
        };
    }
}
