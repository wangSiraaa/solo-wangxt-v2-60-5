package com.example.evidencechain.chain;

import com.example.evidencechain.AbstractIntegrationTest;
import com.example.evidencechain.repository.EvidenceEventDao;
import com.example.evidencechain.service.ChainVerifier;
import com.example.evidencechain.service.EvidenceService;
import com.example.evidencechain.service.PlanCommand;
import com.example.evidencechain.support.FailureSimulator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acceptance:
 * <ul>
 *   <li>a failure inside the state-change transaction rolls back the plan
 *       status, the business row AND the evidence event together — no
 *       half-finished "state without evidence" / "evidence without state"
 *       records;</li>
 *   <li>concurrent commands on one plan serialize and never corrupt the
 *       sequence (simulates interleaving around a restart boundary);</li>
 *   <li>after a failed transaction the same idempotency key can be retried
 *       successfully exactly once.</li>
 * </ul>
 */
class TransactionAtomicityTest extends AbstractIntegrationTest {

    @Autowired
    private EvidenceService evidenceService;
    @Autowired
    private ChainVerifier verifier;
    @Autowired
    private EvidenceEventDao eventDao;
    @Autowired
    private FailureSimulator failureSimulator;

    @Test
    void failureAfterWritesRollsBackEverything() {
        long planId = evidenceService.createPlan("P-ATOMIC", "a", "a", "kc").planId();
        evidenceService.execute(PlanCommand.start(planId, "a", "ks"));

        long eventsBefore = eventDao.countByPlan(planId);
        var statusBefore = verifier.verify(planId);
        assertThat(statusBefore.valid()).isTrue();

        failureSimulator.install(() -> {
            throw new IllegalStateException("simulated crash before commit");
        });
        try {
            assertThatThrownBy(() -> evidenceService.execute(
                    PlanCommand.confirm(planId, "b", "never persisted", "kcf")))
                    .hasMessageContaining("simulated crash");
        } finally {
            failureSimulator.clear();
        }

        // No new event, no confirmation row, plan still at STARTED.
        assertThat(eventDao.countByPlan(planId)).isEqualTo(eventsBefore);
        assertThat(jdbc.queryForObject("SELECT status FROM plan WHERE id = ?", String.class, planId))
                .isEqualTo("STARTED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM plan_confirmation WHERE plan_id = ?", Long.class, planId))
                .isZero();

        // The (non-)state is still a fully verifiable chain.
        assertThat(verifier.verify(planId).valid()).isTrue();

        // Retry with the same key after rollback succeeds exactly once.
        evidenceService.execute(PlanCommand.confirm(planId, "b", "now persisted", "kcf"));
        assertThat(eventDao.countByPlan(planId)).isEqualTo(eventsBefore + 1);
        assertThat(verifier.verify(planId).valid()).isTrue();

        // Replaying that key after a successful commit adds nothing.
        evidenceService.execute(PlanCommand.confirm(planId, "b", "now persisted", "kcf"));
        assertThat(eventDao.countByPlan(planId)).isEqualTo(eventsBefore + 1);
    }

    @Test
    void concurrentCommandsOnSamePlanNeverCorruptTheSequence() throws Exception {
        // The happy path chain is inherently sequential, so here we hammer a
        // DRAFT plan with START commands under distinct keys; only one can win
        // the state transition, and all others must fail cleanly without
        // leaving evidence behind.
        long planId = evidenceService.createPlan("P-CONC", "c", "a", "kc").planId();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try {
            Future<?>[] futures = new Future<?>[threads];
            for (int i = 0; i < threads; i++) {
                final String key = "k-start-" + i;
                futures[i] = pool.submit(() -> {
                    ready.countDown();
                    try {
                        fire.await();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ie);
                    }
                    try {
                        evidenceService.execute(PlanCommand.start(planId, "a", key));
                        success.incrementAndGet();
                    } catch (com.example.evidencechain.service.IllegalTransitionException e) {
                        rejected.incrementAndGet();
                    }
                });
            }
            ready.await();
            fire.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);

        // Exactly one START event, no duplicate seqs/hash links, chain valid.
        var chain = eventDao.findByPlanOrderBySeq(planId);
        assertThat(chain).hasSize(2); // CREATE + one START
        assertThat(chain.get(1).seq()).isEqualTo(2);
        assertThat(verifier.verify(planId).valid()).isTrue();
    }
}
