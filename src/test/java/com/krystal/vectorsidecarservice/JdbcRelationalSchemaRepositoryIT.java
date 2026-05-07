package com.krystal.vectorsidecarservice;

import com.krystal.vectorsidecarservice.application.port.out.RelationalSchemaPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("local")
@Sql(statements = {
        "DROP TABLE IF EXISTS PUBLIC.DDL_CLASSIFY",
        "DROP TABLE IF EXISTS PUBLIC.BAD_DDL"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class JdbcRelationalSchemaRepositoryIT {

    @Autowired
    private RelationalSchemaPort relationalSchemaPort;

    @Test
    void shouldClassifyDuplicateTableDdlFailure() {
        relationalSchemaPort.executeDdl("CREATE TABLE PUBLIC.DDL_CLASSIFY (ID BIGINT NOT NULL)");

        assertThatThrownBy(() -> relationalSchemaPort.executeDdl("CREATE TABLE PUBLIC.DDL_CLASSIFY (ID BIGINT NOT NULL)"))
                .isInstanceOf(RelationalSchemaPort.DdlExecutionException.class)
                .satisfies(ex -> assertThat(((RelationalSchemaPort.DdlExecutionException) ex).failureKind())
                        .isEqualTo(RelationalSchemaPort.DdlFailureKind.OBJECT_ALREADY_EXISTS));
    }

    @Test
    void shouldClassifyBadSyntaxDdlFailureAsNonRetryable() {
        assertThatThrownBy(() -> relationalSchemaPort.executeDdl("CREATE TABL PUBLIC.BAD_DDL (ID BIGINT NOT NULL)"))
                .isInstanceOf(RelationalSchemaPort.DdlExecutionException.class)
                .satisfies(ex -> assertThat(((RelationalSchemaPort.DdlExecutionException) ex).failureKind())
                        .isEqualTo(RelationalSchemaPort.DdlFailureKind.NON_RETRYABLE));
    }
}
