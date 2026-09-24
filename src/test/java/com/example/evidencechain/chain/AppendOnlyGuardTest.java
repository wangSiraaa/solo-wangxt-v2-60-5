package com.example.evidencechain.chain;

import com.example.evidencechain.AbstractIntegrationTest;
import com.example.evidencechain.service.EvidenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The evidence table is append-only at the database layer: ordinary
 * application sessions cannot UPDATE or DELETE history even if a bug or a
 * compromised DAO tried to.
 */
class AppendOnlyGuardTest extends AbstractIntegrationTest {

    @Autowired
    private EvidenceService evidenceService;
    @Autowired
    private JdbcTemplate jdbc;

    private static void assertAppendOnlyRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .rootCause()
                .hasMessageContaining("append-only");
    }

    @Test
    void updatesAndDeletesAreRejectedWithoutTheOverride() {
        long planId = evidenceService.createPlan("P-GUARD", "g", "a", "kc").planId();

        assertAppendOnlyRejected(() -> jdbc.update(
                "UPDATE plan_evidence_event SET event_hash = ? WHERE plan_id = ?",
                "1".repeat(64), planId));

        assertAppendOnlyRejected(() -> jdbc.update(
                "DELETE FROM plan_evidence_event WHERE plan_id = ?", planId));
    }
}
