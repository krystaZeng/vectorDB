package com.krystal.vectorsidecarservice.infrastructure.persistence.registry;

import com.krystal.vectorsidecarservice.application.port.out.VectorIndexPort;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.registry.VectorIndexMeta;
import com.krystal.vectorsidecarservice.infrastructure.persistence.support.JdbcTimeSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JdbcVectorIndexRepository extends JdbcTimeSupport implements VectorIndexPort {

    private static final String INSERT_SQL = """
            INSERT INTO SYS_VECTOR_INDEXES_ (
                INDEX_ID, COLUMN_ID, COLLECTION_ID, PROFILE_NAME, INDEX_TYPE, METRIC_TYPE,
                INDEX_PARAMS_JSON, SEARCH_PARAMS_JSON, PAYLOAD_INDEX_JSON, IS_DEFAULT, SERVING_STATE,
                INDEX_STATUS, BUILD_VERSION, CREATED_AT, UPDATED_AT
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_SQL = """
            SELECT
                INDEX_ID, COLUMN_ID, COLLECTION_ID, PROFILE_NAME, INDEX_TYPE, METRIC_TYPE,
                INDEX_PARAMS_JSON, SEARCH_PARAMS_JSON, PAYLOAD_INDEX_JSON, IS_DEFAULT, SERVING_STATE,
                INDEX_STATUS, BUILD_VERSION, CREATED_AT, UPDATED_AT
            FROM SYS_VECTOR_INDEXES_
            WHERE COLUMN_ID = ?
            ORDER BY CREATED_AT DESC
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT
                INDEX_ID, COLUMN_ID, COLLECTION_ID, PROFILE_NAME, INDEX_TYPE, METRIC_TYPE,
                INDEX_PARAMS_JSON, SEARCH_PARAMS_JSON, PAYLOAD_INDEX_JSON, IS_DEFAULT, SERVING_STATE,
                INDEX_STATUS, BUILD_VERSION, CREATED_AT, UPDATED_AT
            FROM SYS_VECTOR_INDEXES_
            WHERE INDEX_ID = ?
            """;

    private static final String UPDATE_STATUS_SQL = """
            UPDATE SYS_VECTOR_INDEXES_
            SET SERVING_STATE = ?, INDEX_STATUS = ?, UPDATED_AT = CURRENT_TIMESTAMP
            WHERE INDEX_ID = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public VectorIndexMeta save(VectorIndexMeta indexMeta) {
        try {
            jdbcTemplate.update(
                    INSERT_SQL,
                    indexMeta.indexId(),
                    indexMeta.columnId(),
                    indexMeta.collectionId(),
                    indexMeta.profileName(),
                    indexMeta.indexType(),
                    indexMeta.metricType(),
                    indexMeta.indexParamsJson(),
                    indexMeta.searchParamsJson(),
                    indexMeta.payloadIndexJson(),
                    indexMeta.isDefault(),
                    indexMeta.servingState(),
                    indexMeta.indexStatus(),
                    indexMeta.buildVersion(),
                    timestamp(indexMeta.createdAt()),
                    timestamp(indexMeta.updatedAt())
            );
            return indexMeta;
        } catch (DataIntegrityViolationException ex) {
            throw new BizException("vector index profile already registered", ex);
        }
    }

    @Override
    public Optional<VectorIndexMeta> findById(long indexId) {
        return jdbcTemplate.query(FIND_BY_ID_SQL, this::mapRow, indexId)
                .stream()
                .findFirst();
    }

    @Override
    public List<VectorIndexMeta> findByColumnId(long columnId) {
        return jdbcTemplate.query(FIND_SQL, this::mapRow, columnId);
    }

    @Override
    public void updateStatus(long indexId, String servingState, String indexStatus) {
        jdbcTemplate.update(UPDATE_STATUS_SQL, servingState, indexStatus, indexId);
    }

    private VectorIndexMeta mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new VectorIndexMeta(
                rs.getLong("INDEX_ID"),
                rs.getLong("COLUMN_ID"),
                (Long) rs.getObject("COLLECTION_ID"),
                rs.getString("PROFILE_NAME"),
                rs.getString("INDEX_TYPE"),
                rs.getString("METRIC_TYPE"),
                rs.getString("INDEX_PARAMS_JSON"),
                rs.getString("SEARCH_PARAMS_JSON"),
                rs.getString("PAYLOAD_INDEX_JSON"),
                rs.getString("IS_DEFAULT"),
                rs.getString("SERVING_STATE"),
                rs.getString("INDEX_STATUS"),
                rs.getString("BUILD_VERSION"),
                instant(rs.getTimestamp("CREATED_AT")),
                instant(rs.getTimestamp("UPDATED_AT"))
        );
    }
}
