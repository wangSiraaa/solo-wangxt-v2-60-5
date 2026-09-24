package com.example.evidencechain;

import com.example.evidencechain.service.AnchorMigrationService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Base for integration tests: each test gets freshly migrated tables inside
 * the embedded PostgreSQL. The startup anchor runner is disabled in test
 * config; tests invoke migration explicitly.
 */
@SpringBootTest(classes = {EvidenceChainApplication.class, EmbeddedPostgresConfig.class})
public abstract class AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected AnchorMigrationService migrationService;

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE TABLE plan_evidence_event, plan_confirmation, "
                + "plan_mock_receipt, plan_rejection, plan_writeoff, "
                + "legacy_plan_snapshot, plan RESTART IDENTITY CASCADE");
    }
}
