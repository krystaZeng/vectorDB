package com.krystal.vectorsidecarservice.infrastructure.persistence.registry;

import com.krystal.vectorsidecarservice.application.port.out.VectorPayloadFieldPort;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.registry.VectorPayloadFieldMeta;
import com.krystal.vectorsidecarservice.infrastructure.persistence.support.JdbcTimeSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class JdbcVectorPayloadFieldRepository extends JdbcTimeSupport implements VectorPayloadFieldPort {

    private static final String INSERT_SQL = """
            INSERT INTO SYS_VECTOR_PAYLOAD_FIELDS_ (
                FIELD_ID, COLUMN_ID, SOURCE_COLUMN_NAME, PAYLOAD_KEY, FIELD_TYPE, IS_FILTERABLE,
                IS_RETURNABLE, IS_INDEXED, SYNC_ENABLED, FIELD_STATUS, INDEX_PARAMS_JSON,
                PAYLOAD_INDEX_STATUS, PAYLOAD_INDEX_VERIFIED_AT, PAYLOAD_INDEX_ERROR_CODE,
                PAYLOAD_INDEX_ERROR_MESSAGE, CREATED_AT, UPDATED_AT
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_SQL = """
            SELECT
                FIELD_ID, COLUMN_ID, SOURCE_COLUMN_NAME, PAYLOAD_KEY, FIELD_TYPE, IS_FILTERABLE,
                IS_RETURNABLE, IS_INDEXED, SYNC_ENABLED, FIELD_STATUS, INDEX_PARAMS_JSON,
                PAYLOAD_INDEX_STATUS, PAYLOAD_INDEX_VERIFIED_AT, PAYLOAD_INDEX_ERROR_CODE,
                PAYLOAD_INDEX_ERROR_MESSAGE, CREATED_AT, UPDATED_AT
            FROM SYS_VECTOR_PAYLOAD_FIELDS_
            WHERE COLUMN_ID = ?
            ORDER BY CREATED_AT DESC
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public VectorPayloadFieldMeta save(VectorPayloadFieldMeta payloadFieldMeta) {
        try {
            jdbcTemplate.update(
                    INSERT_SQL,
                    payloadFieldMeta.fieldId(),
                    payloadFieldMeta.columnId(),
                    payloadFieldMeta.sourceColumnName(),
                    payloadFieldMeta.payloadKey(),
                    payloadFieldMeta.fieldType(),
                    payloadFieldMeta.isFilterable(),
                    payloadFieldMeta.isReturnable(),
                    payloadFieldMeta.isIndexed(),
                    payloadFieldMeta.syncEnabled(),
                    payloadFieldMeta.fieldStatus(),
                    payloadFieldMeta.indexParamsJson(),
                    payloadFieldMeta.payloadIndexStatus(),
                    timestamp(payloadFieldMeta.payloadIndexVerifiedAt()),
                    payloadFieldMeta.payloadIndexErrorCode(),
                    payloadFieldMeta.payloadIndexErrorMessage(),
                    timestamp(payloadFieldMeta.createdAt()),
                    timestamp(payloadFieldMeta.updatedAt())
            );
            return payloadFieldMeta;
        } catch (DataIntegrityViolationException ex) {
            throw new BizException("vector payload field already registered", ex);
        }
    }

    @Override
    public List<VectorPayloadFieldMeta> findByColumnId(long columnId) {
        return jdbcTemplate.query(FIND_SQL, (rs, rowNum) -> new VectorPayloadFieldMeta(
                rs.getLong("FIELD_ID"),
                rs.getLong("COLUMN_ID"),
                rs.getString("SOURCE_COLUMN_NAME"),
                rs.getString("PAYLOAD_KEY"),
                rs.getString("FIELD_TYPE"),
                rs.getString("IS_FILTERABLE"),
                rs.getString("IS_RETURNABLE"),
                rs.getString("IS_INDEXED"),
                rs.getString("SYNC_ENABLED"),
                rs.getString("FIELD_STATUS"),
                rs.getString("INDEX_PARAMS_JSON"),
                rs.getString("PAYLOAD_INDEX_STATUS"),
                instant(rs.getTimestamp("PAYLOAD_INDEX_VERIFIED_AT")),
                rs.getString("PAYLOAD_INDEX_ERROR_CODE"),
                rs.getString("PAYLOAD_INDEX_ERROR_MESSAGE"),
                instant(rs.getTimestamp("CREATED_AT")),
                instant(rs.getTimestamp("UPDATED_AT"))
        ), columnId);
    }
}
