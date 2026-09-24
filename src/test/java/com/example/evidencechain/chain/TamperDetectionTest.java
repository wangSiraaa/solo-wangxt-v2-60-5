package com.example.evidencechain.chain;

import com.example.evidencechain.AbstractIntegrationTest;
import com.example.evidencechain.domain.PlanStatus;
import com.example.evidencechain.repository.EvidenceEventDao;
import com.example.evidencechain.service.ChainVerifier;
import com.example.evidencechain.service.EvidenceService;
import com.example.evidencechain.service.PlanCommand;
import com.example.evidencechain.service.VerificationResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance: simulated tampering is detected and the FIRST break point is
 * located precisely. Tampering simulates a privileged attacker: it opens a
 * transaction, flips the append-only guard session flag, and mutates rows
 * directly.
 */
class TamperDetectionTest extends AbstractIntegrationTest {

    @Autowired
    private EvidenceService evidenceService;
    @Autowired
    private ChainVerifier verifier;
    @Autowired
    private EvidenceEventDao eventDao;
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;

    /** Runs mutating SQL with the append-only trigger bypassed. */
    private void asAttacker(Runnable mutation) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL evidence.allow_tamper = 'on'");
            mutation.run();
        });
    }

    private long completeChain() {
        long planId = evidenceService.createPlan("P-TAMPER", "t", "a", "kc").planId();
        evidenceService.execute(PlanCommand.start(planId, "a", "ks"));
        evidenceService.execute(PlanCommand.confirm(planId, "b", "ok", "kcf"));
        evidenceService.execute(PlanCommand.receipt(planId, "CH", "{}", "kr"));
        evidenceService.execute(PlanCommand.writeoff(planId, "c", "done", "kw"));
        assertThat(verifier.verify(planId).valid()).isTrue();
        return planId;
    }

    @Test
    void untouchedChainIsValid() {
        long planId = completeChain();
        VerificationResult result = verifier.verify(planId);
        assertThat(result.valid()).isTrue();
    }

    @Test
    void tamperingEventHashOnMiddleEventIsLocatedAtItsSeq() {
        long planId = completeChain();
        List<EvidenceEventDao.EventRow> chain = eventDao.findByPlanOrderBySeq(planId);
        int targetSeq = chain.get(2).seq(); // CONFIRMATION, seq=3

        asAttacker(() -> jdbc.update(
                "UPDATE plan_evidence_event SET event_hash = ? WHERE plan_id = ? AND seq = ?",
                "f".repeat(64), planId, targetSeq));

        VerificationResult result = verifier.verify(planId);
        assertThat(result.valid()).isFalse();
        assertThat(result.firstBrokenSeq()).isEqualTo(targetSeq);
        assertThat(result.reason()).isEqualTo("EVENT_HASH_MISMATCH");
    }

    @Test
    void tamperingPrevHashBreaksTheFollowingLink() {
        long planId = completeChain();
        List<EvidenceEventDao.EventRow> chain = eventDao.findByPlanOrderBySeq(planId);
        int targetSeq = chain.get(3).seq(); // MOCK_RECEIPT, seq=4

        // Corrupt seq=4's prevHash pointer.
        asAttacker(() -> jdbc.update(
                "UPDATE plan_evidence_event SET prev_hash = ? WHERE plan_id = ? AND seq = ?",
                "a".repeat(64), planId, targetSeq));

        VerificationResult result = verifier.verify(planId);
        assertThat(result.valid()).isFalse();
        assertThat(result.firstBrokenSeq()).isEqualTo(targetSeq);
        assertThat(result.reason()).isEqualTo("PREV_HASH_MISMATCH");
    }

    @Test
    void tamperingPayloadIsDetectedEvenWithoutHashColumnChanges() {
        long planId = completeChain();
        List<EvidenceEventDao.EventRow> chain = eventDao.findByPlanOrderBySeq(planId);
        int targetSeq = chain.get(3).seq(); // MOCK_RECEIPT

        // Attacker edits only payload JSON.
        asAttacker(() -> jdbc.update(
                "UPDATE plan_evidence_event SET payload_json = ? WHERE plan_id = ? AND seq = ?",
                "{\"eventType\":\"MOCK_RECEIPT\"}", planId, targetSeq));

        VerificationResult result = verifier.verify(planId);
        assertThat(result.valid()).isFalse();
        assertThat(result.firstBrokenSeq()).isEqualTo(targetSeq);
        // Either the payload hash disagrees or canonical re-hash disagrees;
        // in both cases the exact event is pinpointed.
        assertThat(result.reason()).isIn("PAYLOAD_HASH_MISMATCH", "PAYLOAD_NOT_CANONICAL");
    }

    @Test
    void deletingAnEventCreatesAGapAndIsLocated() {
        long planId = completeChain();
        asAttacker(() -> jdbc.update(
                "DELETE FROM plan_evidence_event WHERE plan_id = ? AND seq = 2", planId));

        VerificationResult result = verifier.verify(planId);
        assertThat(result.valid()).isFalse();
        // After deleting seq=2, the next stored row is seq=3 -> gap found there.
        assertThat(result.firstBrokenSeq()).isEqualTo(3);
        assertThat(result.reason()).isEqualTo("SEQ_GAP");
    }

    @Test
    void changingPlanStatusWithoutEvidenceIsDetected() {
        long planId = completeChain();
        jdbc.update("UPDATE plan SET status = 'CONFIRMED' WHERE id = ?", planId);

        VerificationResult result = verifier.verify(planId);
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isEqualTo("TAIL_STATUS_MISMATCH");
        assertThat(result.detail()).contains("state without evidence");
        assertThat(PlanStatus.CONFIRMED.name()).isEqualTo("CONFIRMED");
    }

    @Test
    void deletingBusinessRowIsDetectedAsEvidenceWithoutState() {
        long planId = completeChain();
        jdbc.update("DELETE FROM plan_writeoff WHERE plan_id = ?", planId);

        VerificationResult result = verifier.verify(planId);
        assertThat(result.valid()).isFalse();
        assertThat(result.firstBrokenSeq()).isEqualTo(5);
        assertThat(result.reason()).isEqualTo("BUSINESS_RECORD_MISSING");
    }
}
