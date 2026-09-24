package com.example.evidence;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.io.IOException;

/**
 * Boots the full Spring Boot web stack against a real PostgreSQL (embedded,
 * started once per JVM) so the acceptance tests exercise actual transaction
 * commit/rollback, real SQL constraints and JSONB — no mocks, no external
 * service or docker dependency.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    static final EmbeddedPostgres PG;

    static {
        try {
            PG = EmbeddedPostgres.builder()
                    .setServerConfig("timezone", "UTC")
                    .start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:postgresql://localhost:" + PG.getPort() + "/postgres");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        // endpoints for tampering simulation and one-shot fault injection
        registry.add("evidence.test-endpoints-enabled", () -> "true");
        registry.add("evidence.anchor-on-startup", () -> "false");
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected DataSource dataSource;

    protected TestRestTemplate http = new TestRestTemplate();

    @BeforeEach
    void cleanTables() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("TRUNCATE TABLE plan_evidence_event, plan, legacy_plan RESTART IDENTITY CASCADE");
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }
}
