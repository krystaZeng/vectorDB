package com.krystal.vectorsidecarservice.infrastructure.persistence.support;

import com.krystal.vectorsidecarservice.application.port.out.IdGeneratorPort;
import com.krystal.vectorsidecarservice.application.port.out.RelationalSchemaPort;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcSequenceIdGenerator implements IdGeneratorPort {

    private static final String SEQUENCE_NAME = "SYS_VECTOR_ID_SEQ";

    private final JdbcTemplate jdbcTemplate;
    private final String nextValueSql;

    public JdbcSequenceIdGenerator(JdbcTemplate jdbcTemplate, RelationalSchemaPort relationalSchemaPort) {
        this.jdbcTemplate = jdbcTemplate;
        this.nextValueSql = nextValueSql(relationalSchemaPort.databaseDialect());
    }

    @Override
    public long nextId() {
        Long id = jdbcTemplate.queryForObject(nextValueSql, Long.class);
        if (id == null) {
            throw new BizException("failed to generate id from sequence: " + SEQUENCE_NAME);
        }
        return id;
    }

    private String nextValueSql(RelationalSchemaPort.DatabaseDialect dialect) {
        return switch (dialect) {
            case ALTIBASE -> "SELECT " + SEQUENCE_NAME + ".NEXTVAL FROM DUAL";
            case H2, GENERIC -> "SELECT NEXT VALUE FOR " + SEQUENCE_NAME;
        };
    }
}
