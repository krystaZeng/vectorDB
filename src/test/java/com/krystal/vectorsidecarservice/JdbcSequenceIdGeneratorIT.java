package com.krystal.vectorsidecarservice;

import com.krystal.vectorsidecarservice.application.port.out.IdGeneratorPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("local")
class JdbcSequenceIdGeneratorIT {

    @Autowired
    private IdGeneratorPort idGenerator;

    @Test
    void shouldGenerateIncreasingIdsFromDatabaseSequence() {
        long first = idGenerator.nextId();
        long second = idGenerator.nextId();

        assertThat(first).isPositive();
        assertThat(second).isGreaterThan(first);
    }
}
