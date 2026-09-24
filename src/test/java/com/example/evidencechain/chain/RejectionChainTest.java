package com.example.evidencechain.chain;

import com.example.evidencechain.AbstractIntegrationTest;
import com.example.evidencechain.domain.PlanStatus;
import com.example.evidencechain.service.ChainVerifier;
import com.example.evidencechain.service.EvidenceService;
import com.example.evidencechain.service.IllegalTransitionException;
import com.example.evidencechain.service.PlanCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Rejection results are first-class evidence events with business rows. */
class RejectionChainTest extends AbstractIntegrationTest {

    @Autowired
    private EvidenceService evidenceService;
    @Autowired
    private ChainVerifier verifier;

    @Test
    void rejectionFromStartedStateVerifiesAndTerminalStateBlocksFurtherEvents() {
        long planId = evidenceService.createPlan("P-REJ", "r", "a", "kc").planId();
        evidenceService.execute(PlanCommand.start(planId, "a", "ks"));

        var rejection = evidenceService.execute(
                PlanCommand.reject(planId, "auditor", "资料不全", "krej"));
        assertThat(rejection.toStatus()).isEqualTo(PlanStatus.REJECTED);
        assertThat(rejection.businessRecordId()).isNotNull();
        assertThat(verifier.verify(planId).valid()).isTrue();

        // REJECTED is terminal: further events must fail without writes.
        assertThatThrownBy(() -> evidenceService.execute(
                PlanCommand.confirm(planId, "b", null, "kcflater")))
                .isInstanceOf(IllegalTransitionException.class);
        assertThat(verifier.verify(planId).valid()).isTrue();
    }
}
