package com.krystal.vectorsidecarservice.infrastructure.persistence.data;

import com.krystal.vectorsidecarservice.application.port.out.VectorSourceVersionPort;
import com.krystal.vectorsidecarservice.infrastructure.persistence.support.JdbcTimeSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcVectorSourceVersionRepository extends JdbcTimeSupport implements VectorSourceVersionPort {

    private static final String UPDATE_SQL = """
            UPDATE SYS_VECTOR_SOURCE_VERSIONS_
            SET CURRENT_VERSION = CURRENT_VERSION + 1,
                UPDATED_AT = ?
            WHERE EVENT_KEY = ?
            """;

    private static final String INSERT_SQL = """
            INSERT INTO SYS_VECTOR_SOURCE_VERSIONS_ (
                EVENT_KEY, TENANT_ID, COLUMN_ID, SOURCE_PK, CURRENT_VERSION, CREATED_AT, UPDATED_AT
            ) VALUES (?, ?, ?, ?, 1, ?, ?)
            """;

    private static final String SELECT_VERSION_SQL = """
            SELECT CURRENT_VERSION
            FROM SYS_VECTOR_SOURCE_VERSIONS_
            WHERE EVENT_KEY = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public long nextVersion(NextVersionCommand command) {
        int updated = jdbcTemplate.update(UPDATE_SQL, timestamp(command.now()), command.eventKey());
        if (updated == 0) {
            try {
                jdbcTemplate.update(
                        INSERT_SQL,
                        command.eventKey(),
                        command.tenantId(),
                        command.columnId(),
                        command.sourcePk(),
                        timestamp(command.now()),
                        timestamp(command.now())
                );
            } catch (DataIntegrityViolationException ex) {
                jdbcTemplate.update(UPDATE_SQL, timestamp(command.now()), command.eventKey());
            }
        }
        Long version = jdbcTemplate.queryForObject(SELECT_VERSION_SQL, Long.class, command.eventKey());
        if (version == null) {
            throw new IllegalStateException("source version was not persisted: " + command.eventKey());
        }
        return version;
    }
}
