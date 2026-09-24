package com.example.evidencechain;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.io.IOException;

/**
 * Starts a real PostgreSQL instance (no mocks) for the integration tests so
 * transaction, row-lock and Flyway behaviour are exercised exactly as in
 * production.
 */
@TestConfiguration
public class EmbeddedPostgresConfig {

    @Bean(destroyMethod = "close")
    public EmbeddedPostgres embeddedPostgres() throws IOException {
        return EmbeddedPostgres.builder()
                .setServerConfig("timezone", "UTC")
                .start();
    }

    @Bean
    public DataSource dataSource(EmbeddedPostgres pg) {
        return pg.getPostgresDatabase();
    }
}
