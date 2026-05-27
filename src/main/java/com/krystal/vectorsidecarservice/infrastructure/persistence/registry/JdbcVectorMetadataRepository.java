package com.krystal.vectorsidecarservice.infrastructure.persistence.registry;

import com.krystal.vectorsidecarservice.application.port.out.VectorMetadataPort;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.registry.VectorColumnMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class JdbcVectorMetadataRepository implements VectorMetadataPort {

    private static final String INSERT_SQL = """
            INSERT INTO SYS_VECTOR_COLUMNS_ (
                COLUMN_ID,
                TENANT_ID,
                SCHEMA_NAME,
                TABLE_NAME,
                COLUMN_NAME,
                PK_COLUMN_NAME,
                VECTOR_DIM,
                METRIC_TYPE,
                VECTOR_ENCODING,
                POINT_ID_MODE,
                DELETE_POLICY,
                STATUS,
                SYNC_MODE,
                DEFINITION_HASH,
                REMARK,
                CREATED_AT,
                UPDATED_AT
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_ALL_SQL = """
            SELECT
                COLUMN_ID,
                TENANT_ID,
                SCHEMA_NAME,
                TABLE_NAME,
                COLUMN_NAME,
                PK_COLUMN_NAME,
                VECTOR_DIM,
                METRIC_TYPE,
                VECTOR_ENCODING,
                STATUS,
                SYNC_MODE,
                DEFINITION_HASH,
                REMARK,
                CREATED_AT,
                UPDATED_AT
            FROM SYS_VECTOR_COLUMNS_
            ORDER BY CREATED_AT DESC
            """;

    private static final String FIND_BY_IDENTITY_SQL = """
            SELECT
                COLUMN_ID,
                TENANT_ID,
                SCHEMA_NAME,
                TABLE_NAME,
                COLUMN_NAME,
                PK_COLUMN_NAME,
                VECTOR_DIM,
                METRIC_TYPE,
                VECTOR_ENCODING,
                STATUS,
                SYNC_MODE,
                DEFINITION_HASH,
                REMARK,
                CREATED_AT,
                UPDATED_AT
            FROM SYS_VECTOR_COLUMNS_
            WHERE TENANT_ID = ?
              AND SCHEMA_NAME = ?
              AND TABLE_NAME = ?
              AND COLUMN_NAME = ?
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT
                COLUMN_ID,
                TENANT_ID,
                SCHEMA_NAME,
                TABLE_NAME,
                COLUMN_NAME,
                PK_COLUMN_NAME,
                VECTOR_DIM,
                METRIC_TYPE,
                VECTOR_ENCODING,
                STATUS,
                SYNC_MODE,
                DEFINITION_HASH,
                REMARK,
                CREATED_AT,
                UPDATED_AT
            FROM SYS_VECTOR_COLUMNS_
            WHERE COLUMN_ID = ?
            """;

    private static final String FIND_BY_TABLE_IDENTITY_SQL = """
            SELECT
                COLUMN_ID,
                TENANT_ID,
                SCHEMA_NAME,
                TABLE_NAME,
                COLUMN_NAME,
                PK_COLUMN_NAME,
                VECTOR_DIM,
                METRIC_TYPE,
                VECTOR_ENCODING,
                STATUS,
                SYNC_MODE,
                DEFINITION_HASH,
                REMARK,
                CREATED_AT,
                UPDATED_AT
            FROM SYS_VECTOR_COLUMNS_
            WHERE TENANT_ID = ?
              AND SCHEMA_NAME = ?
              AND TABLE_NAME = ?
            ORDER BY COLUMN_NAME
            """;

    private static final String UPDATE_STATUS_SQL = """
            UPDATE SYS_VECTOR_COLUMNS_
            SET STATUS = ?, REMARK = ?, UPDATED_AT = CURRENT_TIMESTAMP
            WHERE COLUMN_ID = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public VectorColumnMeta save(VectorColumnMeta meta) {
        try {
            jdbcTemplate.update(
                    INSERT_SQL,
                    meta.columnId(),
                    meta.tenantId(),
                    meta.schemaName(),
                    meta.tableName(),
                    meta.vectorColumn(),
                    meta.pkColumn(),
                    meta.dimension(),
                    meta.metricType(),
                    meta.vectorEncoding(),
                    "DIRECT",
                    "SOFT",
                    meta.status(),
                    meta.syncMode(),
                    meta.definitionHash(),
                    meta.remark(),
                    Timestamp.from(meta.createdAt()),
                    Timestamp.from(meta.updatedAt())
            );
            return meta;
        } catch (DataIntegrityViolationException ex) {
            throw new BizException("vector column already registered", ex);
        }
    }

    @Override
    public Optional<VectorColumnMeta> findById(long columnId) {
        return jdbcTemplate.query(FIND_BY_ID_SQL, this::mapRow, columnId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<VectorColumnMeta> findByIdentity(
            String tenantId,
            String schemaName,
            String tableName,
            String vectorColumn
    ) {
        return jdbcTemplate.query(FIND_BY_IDENTITY_SQL, this::mapRow, tenantId, schemaName, tableName, vectorColumn)
                .stream()
                .findFirst();
    }

    @Override
    public List<VectorColumnMeta> findAll() {
        return jdbcTemplate.query(FIND_ALL_SQL, this::mapRow);
    }

    @Override
    public List<VectorColumnMeta> findByTableIdentity(String tenantId, String schemaName, String tableName) {
        return jdbcTemplate.query(FIND_BY_TABLE_IDENTITY_SQL, this::mapRow, tenantId, schemaName, tableName);
    }

    @Override
    public void updateStatus(long columnId, String status, String remark) {
        jdbcTemplate.update(UPDATE_STATUS_SQL, status, remark, columnId);
    }

    @Override
    public int updateStatusIfCurrentIn(long columnId, String status, String remark, Set<String> currentStatuses) {
        if (currentStatuses == null || currentStatuses.isEmpty()) {
            throw new BizException("currentStatuses must not be empty");
        }
        String placeholders = String.join(", ", currentStatuses.stream().map(ignored -> "?").toList());
        String sql = """
                UPDATE SYS_VECTOR_COLUMNS_
                SET STATUS = ?, REMARK = ?, UPDATED_AT = CURRENT_TIMESTAMP
                WHERE COLUMN_ID = ?
                  AND STATUS IN (
                """ + placeholders + ")";
        Object[] args = new Object[3 + currentStatuses.size()];
        args[0] = status;
        args[1] = remark;
        args[2] = columnId;
        int index = 3;
        for (String currentStatus : currentStatuses) {
            args[index++] = currentStatus;
        }
        return jdbcTemplate.update(sql, args);
    }

    private VectorColumnMeta mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new VectorColumnMeta(
                rs.getLong("COLUMN_ID"),
                rs.getString("TENANT_ID"),
                rs.getString("SCHEMA_NAME"),
                rs.getString("TABLE_NAME"),
                rs.getString("PK_COLUMN_NAME"),
                rs.getString("COLUMN_NAME"),
                rs.getInt("VECTOR_DIM"),
                rs.getString("METRIC_TYPE"),
                rs.getString("VECTOR_ENCODING"),
                rs.getString("STATUS"),
                rs.getString("SYNC_MODE"),
                rs.getString("DEFINITION_HASH"),
                rs.getString("REMARK"),
                rs.getTimestamp("CREATED_AT").toInstant(),
                rs.getTimestamp("UPDATED_AT").toInstant()
        );
    }
}
