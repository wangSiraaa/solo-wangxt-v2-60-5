package com.example.evidencechain.chain;

import com.example.evidencechain.AbstractIntegrationTest;
import com.example.evidencechain.domain.EventType;
import com.example.evidencechain.domain.PlanStatus;
import com.example.evidencechain.repository.BusinessRecordDao;
import com.example.evidencechain.repository.EvidenceEventDao;
import com.example.evidencechain.repository.PlanDao;
import com.example.evidencechain.service.ChainVerifier;
import com.example.evidencechain.service.CommandResult;
import com.example.evidencechain.service.EvidenceService;
import com.example.evidencechain.service.PlanCommand;
import com.example.evidencechain.service.VerificationResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance: complete 开工(START) → 确认(CONFIRMATION) → 模拟回执(MOCK_RECEIPT)
 * → 销记(WRITEOFF) chain can be verified, and duplicate requests do not
 * produce new events or business results.
 */
class FullLifecycleChainTest extends AbstractIntegrationTest {

    @Autowired
    private EvidenceService evidenceService;
    @Autowired
    private ChainVerifier verifier;
    @Autowired
    private PlanDao planDao;
    @Autowired
    private EvidenceEventDao eventDao;
    @Autowired
    private BusinessRecordDao recordDao;

    private long newPlan(String createKey) {
        return evidenceService.createPlan("PLAN-001", "2026 检修计划", "alice", createKey).planId();
    }

    @Test
    void fullStartToWriteoffChainVerifies() {
        long planId = newPlan("key-create-1");

        CommandResult start = evidenceService.execute(
                PlanCommand.start(planId, "alice", "key-start-1"));
        CommandResult confirm = evidenceService.execute(
                PlanCommand.confirm(planId, "bob", "现场已确认", "key-confirm-1"));
        CommandResult receipt = evidenceService.execute(
                PlanCommand.receipt(planId, "ERP-MOCK", "{\"code\":\"OK\"}", "key-receipt-1"));
        CommandResult writeoff = evidenceService.execute(
                PlanCommand.writeoff(planId, "carol", "年度销记", "key-writeoff-1"));

        assertThat(start.toStatus()).isEqualTo(PlanStatus.STARTED);
        assertThat(confirm.toStatus()).isEqualTo(PlanStatus.CONFIRMED);
        assertThat(receipt.toStatus()).isEqualTo(PlanStatus.RECEIPTED);
        assertThat(writeoff.toStatus()).isEqualTo(PlanStatus.WRITTEN_OFF);

        // Sequences are contiguous and each event links the previous hash.
        List<EvidenceEventDao.EventRow> chain = eventDao.findByPlanOrderBySeq(planId);
        assertThat(chain).hasSize(5);
        assertThat(chain.get(1).prevHash()).isEqualTo(chain.get(0).eventHash());
        assertThat(chain.get(2).prevHash()).isEqualTo(chain.get(1).eventHash());
        assertThat(chain.get(3).prevHash()).isEqualTo(chain.get(2).eventHash());
        assertThat(chain.get(4).prevHash()).isEqualTo(chain.get(3).eventHash());
        assertThat(chain.stream().map(EvidenceEventDao.EventRow::eventType))
                .containsExactly(EventType.CREATE, EventType.START, EventType.CONFIRMATION,
                        EventType.MOCK_RECEIPT, EventType.WRITEOFF);

        VerificationResult result = verifier.verify(planId);
        assertThat(result.valid())
                .as("chain verification should pass: %s", result)
                .isTrue();
        assertThat(result.firstBrokenSeq()).isNull();
        assertThat(result.eventCount()).isEqualTo(5);
        assertThat(result.expectedTailStatus()).isEqualTo("WRITTEN_OFF");

        // Plan status matches the tail.
        assertThat(planDao.findById(planId).status()).isEqualTo(PlanStatus.WRITTEN_OFF);
    }

    @Test
    void duplicateRequestsAreIdempotentAndProduceNoNewEventsOrRecords() {
        long planId = newPlan("key-create-2");
        evidenceService.execute(PlanCommand.start(planId, "alice", "k-start"));
        CommandResult first = evidenceService.execute(
                PlanCommand.confirm(planId, "bob", "first remark", "k-confirm"));

        // Replay the exact same request twice more.
        CommandResult replay1 = evidenceService.execute(
                PlanCommand.confirm(planId, "bob", "first remark", "k-confirm"));
        CommandResult replay2 = evidenceService.execute(
                PlanCommand.confirm(planId, "bob", "first remark", "k-confirm"));

        assertThat(replay1.replayed()).isTrue();
        assertThat(replay2.replayed()).isTrue();
        assertThat(replay1.seq()).isEqualTo(first.seq());
        assertThat(replay1.eventHash()).isEqualTo(first.eventHash());
        assertThat(replay1.businessRecordId()).isEqualTo(first.businessRecordId());

        // Exactly one confirmation business row and no extra events.
        assertThat(recordDao.countConfirmations(planId)).isEqualTo(1);
        // CREATE + START + CONFIRMATION only; replays appended nothing.
        assertThat(eventDao.findByPlanOrderBySeq(planId)).hasSize(3);

        // The chain still verifies after replays.
        assertThat(verifier.verify(planId).valid()).isTrue();
    }

    @Test
    void sameKeyUsedForDifferentActionIsRejected() {
        long planId = newPlan("key-create-3");
        evidenceService.execute(PlanCommand.start(planId, "alice", "shared-key"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                evidenceService.execute(PlanCommand.confirm(planId, "bob", null, "shared-key"))
        ).isInstanceOf(com.example.evidencechain.service.IdempotencyConflictException.class);

        // Nothing was appended by the rejected call.
        assertThat(eventDao.countByPlan(planId)).isEqualTo(2);
        assertThat(recordDao.countConfirmations(planId)).isZero();
    }
}
