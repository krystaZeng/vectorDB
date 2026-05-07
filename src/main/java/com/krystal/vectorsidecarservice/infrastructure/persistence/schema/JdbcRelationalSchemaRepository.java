package com.krystal.vectorsidecarservice.infrastructure.persistence.schema;

import com.krystal.vectorsidecarservice.application.port.out.RelationalSchemaPort;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class JdbcRelationalSchemaRepository implements RelationalSchemaPort {

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    @Override
    public DatabaseDialect databaseDialect() {
        try (Connection connection = dataSource.getConnection()) {
            String name = connection.getMetaData().getDatabaseProductName();
            String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (normalized.contains("altibase")) {
                return DatabaseDialect.ALTIBASE;
            }
            if (normalized.contains("h2")) {
                return DatabaseDialect.H2;
            }
            return DatabaseDialect.GENERIC;
        } catch (SQLException ex) {
            throw new BizException("failed to detect database dialect", ex);
        }
    }

    @Override
    public boolean tableExists(String schemaName, String tableName) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            if (exists(metaData, connection.getCatalog(), schemaName, tableName)) {
                return true;
            }
            return exists(metaData, connection.getCatalog(), null, tableName);
        } catch (SQLException ex) {
            throw new BizException("failed to check table existence", ex);
        }
    }

    @Override
    public void validateTableDefinition(TableDefinition definition) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = connection.getCatalog();
            TableLookup lookup = tableLookup(metaData, catalog, definition.schemaName(), definition.tableName());
            if (lookup == null) {
                throw new BizException("table does not exist: " + definition.schemaName() + "." + definition.tableName());
            }
            Map<String, ActualColumn> actualColumns = columns(metaData, catalog, lookup.schema(), lookup.table());
            for (ColumnDefinition expected : definition.columns()) {
                ActualColumn actual = actualColumns.get(normalizeName(expected.name()));
                if (actual == null) {
                    throw new BizException("table column is missing: " + expected.name());
                }
                validateColumn(definition.tableName(), expected, actual);
            }
            Set<String> primaryKeys = primaryKeys(metaData, catalog, lookup.schema(), lookup.table());
            if (!primaryKeys.equals(Set.of(normalizeName(definition.primaryKeyName())))) {
                throw new BizException("table primary key does not match expected column: "
                        + definition.primaryKeyName());
            }
        } catch (SQLException ex) {
            throw new BizException("failed to validate table definition", ex);
        }
    }

    @Override
    public void executeDdl(String ddl) {
        try {
            jdbcTemplate.execute(ddl);
        } catch (DataAccessException ex) {
            throw new RelationalSchemaPort.DdlExecutionException(classifyDdlFailure(ex), "failed to execute ddl", ex);
        }
    }

    private RelationalSchemaPort.DdlFailureKind classifyDdlFailure(DataAccessException ex) {
        if (isObjectAlreadyExists(ex)) {
            return RelationalSchemaPort.DdlFailureKind.OBJECT_ALREADY_EXISTS;
        }
        if (isUnknownExecutionState(ex)) {
            return RelationalSchemaPort.DdlFailureKind.UNKNOWN_EXECUTION_STATE;
        }
        return RelationalSchemaPort.DdlFailureKind.NON_RETRYABLE;
    }

    private boolean isObjectAlreadyExists(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) {
                String sqlState = normalize(sqlException.getSQLState());
                if (Set.of("42S01", "42P07", "42710").contains(sqlState)) {
                    return true;
                }
                if (sqlException.getErrorCode() == 42101 || sqlException.getErrorCode() == 955) {
                    return true;
                }
            }
            String message = current.getMessage();
            if (message != null && looksLikeObjectAlreadyExists(message)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeObjectAlreadyExists(String message) {
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("already exists")
                || normalized.contains("duplicate table")
                || normalized.contains("duplicate object")
                || normalized.contains("object name already exists")
                || normalized.contains("ora-00955");
    }

    private boolean isUnknownExecutionState(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof SQLTimeoutException
                    || current instanceof SQLRecoverableException
                    || current instanceof SQLTransientException) {
                return true;
            }
            if (current instanceof SQLException sqlException) {
                String sqlState = normalize(sqlException.getSQLState());
                if (sqlState.startsWith("08") || sqlState.startsWith("40")
                        || sqlState.equals("HYT00") || sqlState.equals("HYT01")) {
                    return true;
                }
            }
            String simpleName = current.getClass().getSimpleName();
            if (simpleName.contains("Timeout")
                    || simpleName.contains("Transient")
                    || simpleName.contains("Recoverable")
                    || simpleName.contains("CannotGetJdbcConnection")
                    || simpleName.contains("ConnectionException")) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private TableLookup tableLookup(
            DatabaseMetaData metaData,
            String catalog,
            String schema,
            String table
    ) throws SQLException {
        TableLookup exact = tableLookup(metaData, catalog, schema, table, true);
        if (exact != null) {
            return exact;
        }
        return tableLookup(metaData, catalog, null, table, false);
    }

    private TableLookup tableLookup(
            DatabaseMetaData metaData,
            String catalog,
            String schema,
            String table,
            boolean useSchema
    ) throws SQLException {
        try (ResultSet rs = metaData.getTables(catalog, useSchema ? schema : null, table, new String[]{"TABLE"})) {
            if (rs.next()) {
                return new TableLookup(rs.getString("TABLE_SCHEM"), rs.getString("TABLE_NAME"));
            }
        }
        return null;
    }

    private Map<String, ActualColumn> columns(
            DatabaseMetaData metaData,
            String catalog,
            String schema,
            String table
    ) throws SQLException {
        Map<String, ActualColumn> columns = new HashMap<>();
        try (ResultSet rs = metaData.getColumns(catalog, schema, table, null)) {
            while (rs.next()) {
                columns.put(
                        normalizeName(rs.getString("COLUMN_NAME")),
                        new ActualColumn(
                                rs.getString("COLUMN_NAME"),
                                rs.getString("TYPE_NAME"),
                                rs.getInt("COLUMN_SIZE"),
                                rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable
                        )
                );
            }
        }
        return columns;
    }

    private Set<String> primaryKeys(
            DatabaseMetaData metaData,
            String catalog,
            String schema,
            String table
    ) throws SQLException {
        Set<String> primaryKeys = new HashSet<>();
        try (ResultSet rs = metaData.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                primaryKeys.add(normalizeName(rs.getString("COLUMN_NAME")));
            }
        }
        return primaryKeys;
    }

    private void validateColumn(String tableName, ColumnDefinition expected, ActualColumn actual) {
        String expectedType = normalizeType(expected.type());
        String actualType = normalizeType(actual.type());
        if (!expectedType.equals(actualType)) {
            throw new BizException("table column type mismatch for " + tableName + "." + expected.name()
                    + ": expected " + expected.type() + ", actual " + actual.type());
        }
        if (expected.length() != null && actual.size() != expected.length()) {
            throw new BizException("table column length mismatch for " + tableName + "." + expected.name()
                    + ": expected " + expected.length() + ", actual " + actual.size());
        }
        if (actual.nullable() != expected.nullable()) {
            throw new BizException("table column nullability mismatch for " + tableName + "." + expected.name());
        }
    }

    private String normalizeType(String type) {
        String normalized = type == null ? "" : type.toUpperCase(Locale.ROOT);
        if (normalized.contains("CHARACTER VARYING") || normalized.contains("VARCHAR")) {
            return "VARCHAR";
        }
        if (normalized.equals("CHARACTER") || normalized.startsWith("CHAR(") || normalized.equals("CHAR")) {
            return "CHAR";
        }
        if (normalized.contains("BINARY VARYING") || normalized.contains("VARBINARY") || normalized.contains("VARBYTE")) {
            return "VARBINARY";
        }
        if (normalized.contains("BIGINT")) {
            return "BIGINT";
        }
        if (normalized.contains("INTEGER") || normalized.equals("INT")) {
            return "INTEGER";
        }
        if (normalized.contains("DOUBLE")) {
            return "DOUBLE";
        }
        if (normalized.equals("REAL") || normalized.contains("FLOAT")) {
            return "FLOAT";
        }
        if (normalized.contains("TIMESTAMP")) {
            return "TIMESTAMP";
        }
        if (normalized.equals("DATE")) {
            return "DATE";
        }
        return normalized;
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private boolean exists(
            DatabaseMetaData metaData,
            String catalog,
            String schema,
            String table
    ) throws SQLException {
        try (ResultSet rs = metaData.getTables(catalog, schema, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private record TableLookup(String schema, String table) {
    }

    private record ActualColumn(String name, String type, int size, boolean nullable) {
    }
}
