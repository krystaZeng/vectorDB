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
import java.util.Locale;

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
    public void executeDdl(String ddl) {
        try {
            jdbcTemplate.execute(ddl);
        } catch (DataAccessException ex) {
            throw new BizException("failed to execute ddl", ex);
        }
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
}
