package com.krystal.vectorsidecarservice.infrastructure.persistence.registry;

import com.krystal.vectorsidecarservice.application.port.out.VectorMetadataPort;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.registry.VectorColumnMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

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
                CREATED_AT,
                UPDATED_AT
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                STATUS,
                SYNC_MODE,
                CREATED_AT,
                UPDATED_AT
            FROM SYS_VECTOR_COLUMNS_
            ORDER BY CREATED_AT DESC
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
                    "FLOAT32_LE",
                    "DIRECT",
                    "SOFT",
                    meta.status(),
                    meta.syncMode(),
                    Timestamp.from(meta.createdAt()),
                    Timestamp.from(meta.updatedAt())
            );
            return meta;
        } catch (DataIntegrityViolationException ex) {
            throw new BizException("vector column already registered", ex);
        }
    }

    @Override
    public List<VectorColumnMeta> findAll() {
        return jdbcTemplate.query(
                FIND_ALL_SQL,
                (rs, rowNum) -> new VectorColumnMeta(
                        rs.getLong("COLUMN_ID"),
                        rs.getString("TENANT_ID"),
                        rs.getString("SCHEMA_NAME"),
                        rs.getString("TABLE_NAME"),
                        rs.getString("PK_COLUMN_NAME"),
                        rs.getString("COLUMN_NAME"),
                        rs.getInt("VECTOR_DIM"),
                        rs.getString("METRIC_TYPE"),
                        rs.getString("STATUS"),
                        rs.getString("SYNC_MODE"),
                        rs.getTimestamp("CREATED_AT").toInstant(),
                        rs.getTimestamp("UPDATED_AT").toInstant()
                )
        );
    }
}
