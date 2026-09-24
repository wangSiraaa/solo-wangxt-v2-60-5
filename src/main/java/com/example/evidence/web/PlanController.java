package com.example.evidence.web;

import com.example.evidence.chain.ChainVerificationService;
import com.example.evidence.chain.EvidenceEvent;
import com.example.evidence.migration.LegacyAnchorService;
import com.example.evidence.migration.LegacyPlanRepository;
import com.example.evidence.plan.PlanAction;
import com.example.evidence.plan.PlanException;
import com.example.evidence.plan.PlanRepository;
import com.example.evidence.plan.PlanService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/plans")
public class PlanController {

    private final PlanService planService;
    private final PlanRepository planRepository;
    private final ChainVerificationService verificationService;
    private final LegacyAnchorService legacyAnchorService;
    private final LegacyPlanRepository legacyPlanRepository;

    public PlanController(PlanService planService,
                          PlanRepository planRepository,
                          ChainVerificationService verificationService,
                          LegacyAnchorService legacyAnchorService,
                          LegacyPlanRepository legacyPlanRepository) {
        this.planService = planService;
        this.planRepository = planRepository;
        this.verificationService = verificationService;
        this.legacyAnchorService = legacyAnchorService;
        this.legacyPlanRepository = legacyPlanRepository;
    }

    @PostMapping
    public ApiDtos.OperationResponse create(
            @RequestHeader(name = "Idempotency-Key", required = false) String headerKey,
            @RequestBody(required = false) ApiDtos.CreatePlanRequest request) {
        if (request == null) {
            throw PlanException.invalidRequest("request body is required");
        }
        String key = firstNonNull(headerKey, request.requestId());
        var result = planService.createPlan(
                new PlanService.CreatePlanCommand(key, request.planNo(), request.title()));
        return toOperation(result);
    }

    @PostMapping("/{id}/actions")
    public ApiDtos.OperationResponse action(
            @PathVariable long id,
            @RequestHeader(name = "Idempotency-Key", required = false) String headerKey,
            @RequestBody(required = false) ApiDtos.TransitionRequest request) {
        if (request == null || request.action() == null) {
            throw PlanException.invalidRequest("action is required");
        }
        PlanAction action = PlanAction.fromName(request.action())
                .orElseThrow(() -> PlanException.invalidRequest("unknown action: " + request.action()));
        String key = firstNonNull(headerKey, request.requestId());
        var result = planService.transition(new PlanService.TransitionCommand(
                key, id, action, request.payload() == null ? Map.of() : request.payload()));
        return toOperation(result);
    }

    @GetMapping("/{id}")
    public ApiDtos.PlanResponse get(@PathVariable long id) {
        return toPlan(planRepository.findById(id)
                .orElseThrow(() -> PlanException.notFound("plan not found: " + id)));
    }

    /** 复盘查询: plan snapshot + every evidence event in chain order. */
    @GetMapping("/{id}/evidence")
    public ApiDtos.PlanChainResponse evidence(@PathVariable long id) {
        PlanRepository.PlanRow plan = planRepository.findById(id)
                .orElseThrow(() -> PlanException.notFound("plan not found: " + id));
        List<ApiDtos.EventResponse> events = planService.events(id).stream()
                .map(this::toEvent)
                .toList();
        return new ApiDtos.PlanChainResponse(toPlan(plan), events);
    }

    /** 校验接口: verify one chain and locate the first break, if any. */
    @GetMapping("/{id}/verify")
    public ApiDtos.VerifyResponse verify(@PathVariable long id) {
        var r = verificationService.verifyPlan(id);
        return new ApiDtos.VerifyResponse(r.planId(), r.valid(), r.firstBrokenSeq(),
                r.reasonCode(), r.detail(), r.verifiedEventHashes());
    }

    /** Verify every plan chain in the system (audit / compliance sweep). */
    @GetMapping("/verify")
    public ApiDtos.VerifyAllResponse verifyAll() {
        var all = verificationService.verifyAll();
        List<ApiDtos.VerifyResponse> results = all.results().stream()
                .map(r -> new ApiDtos.VerifyResponse(r.planId(), r.valid(), r.firstBrokenSeq(),
                        r.reasonCode(), r.detail(), r.verifiedEventHashes()))
                .toList();
        return new ApiDtos.VerifyAllResponse(all.allValid(), all.planCount(), results);
    }

    /** Manually trigger safe, idempotent anchoring of historical plans. */
    @PostMapping("/legacy/anchor")
    public ApiDtos.AnchorResponse anchorLegacy() {
        var r = legacyAnchorService.anchorAll();
        List<ApiDtos.AnchorItemResponse> items = r.items().stream()
                .map(i -> new ApiDtos.AnchorItemResponse(i.legacyId(), i.planId(), i.status(), i.detail()))
                .toList();
        return new ApiDtos.AnchorResponse(r.totalLegacy(), r.anchored(), r.skipped(), r.failed(), items);
    }

    /** Test support: seed a historical row directly into the legacy table. */
    @PostMapping("/legacy/seed")
    public ResponseEntity<Void> seedLegacy(@RequestBody ApiDtos.LegacySeedRequest request) {
        if (request.id() == 0 || request.planNo() == null || request.title() == null || request.status() == null) {
            throw PlanException.invalidRequest("id, planNo, title and status are required");
        }
        legacyPlanRepository.insert(request.id(), request.planNo(), request.title(), request.status(),
                request.createdAt(), request.updatedAt(), request.extra());
        return ResponseEntity.ok().build();
    }

    private ApiDtos.OperationResponse toOperation(PlanService.PlanOperationResult result) {
        return new ApiDtos.OperationResponse(result.replayed(),
                toPlan(result.plan()), toEvent(result.event()));
    }

    private ApiDtos.PlanResponse toPlan(PlanRepository.PlanRow p) {
        return new ApiDtos.PlanResponse(p.id(), p.planNo(), p.title(), p.status(),
                p.source(), p.legacyRef(), p.createdAt(), p.updatedAt());
    }

    private ApiDtos.EventResponse toEvent(EvidenceEvent e) {
        boolean genesis = com.example.evidence.chain.HashChain.GENESIS_PREV_HASH.equals(e.prevHash());
        return new ApiDtos.EventResponse(e.seq(), e.eventType(), e.stateBefore(), e.stateAfter(),
                e.payload(), e.requestId(), e.prevHash(), e.eventHash(), e.createdAt(), genesis);
    }

    private static String firstNonNull(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
