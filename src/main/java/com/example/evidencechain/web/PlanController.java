package com.example.evidencechain.web;

import com.example.evidencechain.domain.EventType;
import com.example.evidencechain.repository.EvidenceEventDao;
import com.example.evidencechain.repository.LegacySnapshotDao;
import com.example.evidencechain.repository.PlanDao;
import com.example.evidencechain.service.AnchorMigrationService;
import com.example.evidencechain.service.ChainVerifier;
import com.example.evidencechain.service.CommandResult;
import com.example.evidencechain.service.EvidenceService;
import com.example.evidencechain.service.PlanCommand;
import com.example.evidencechain.service.VerificationResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan lifecycle + evidence chain review/verification HTTP API.
 */
@RestController
@RequestMapping("/api/plans")
public class PlanController {

    private final EvidenceService evidenceService;
    private final ChainVerifier verifier;
    private final AnchorMigrationService migrationService;
    private final PlanDao planDao;
    private final EvidenceEventDao eventDao;
    private final LegacySnapshotDao legacyDao;

    public PlanController(EvidenceService evidenceService,
                          ChainVerifier verifier,
                          AnchorMigrationService migrationService,
                          PlanDao planDao,
                          EvidenceEventDao eventDao,
                          LegacySnapshotDao legacyDao) {
        this.evidenceService = evidenceService;
        this.verifier = verifier;
        this.migrationService = migrationService;
        this.planDao = planDao;
        this.eventDao = eventDao;
        this.legacyDao = legacyDao;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreatePlanRequest request) {
        CommandResult result = evidenceService.createPlan(
                request.getPlanNo(), request.getTitle(),
                request.getOperator(), request.getIdempotencyKey());
        return ResponseEntity.created(URI.create("/api/plans/" + result.planId()))
                .body(renderResult(result));
    }

    @PostMapping("/{id}/actions")
    public Map<String, Object> act(@PathVariable long id,
                                   @Valid @RequestBody PlanActionRequest request) {
        EventType type;
        try {
            type = EventType.valueOf(request.getAction());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown action: " + request.getAction()
                    + " (expected one of START, CONFIRMATION, MOCK_RECEIPT, REJECTION, WRITEOFF)");
        }
        if (type == EventType.ANCHOR || type == EventType.CREATE) {
            throw new IllegalArgumentException("Action " + type + " cannot be requested directly");
        }
        PlanCommand command = new PlanCommand(id, type, request.getOperator(),
                request.getRemark(), request.getChannel(), request.getReceiptPayload(),
                request.getReason(), request.getIdempotencyKey());
        return renderResult(evidenceService.execute(command));
    }

    // ------------------------------------------------------------------
    // Review / verification
    // ------------------------------------------------------------------

    @GetMapping
    public List<Map<String, Object>> list() {
        return planDao.findAll().stream().map(p -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", p.id());
            map.put("planNo", p.planNo());
            map.put("status", p.status());
            map.put("title", p.title());
            map.put("createdAt", p.createdAt());
            map.put("updatedAt", p.updatedAt());
            return map;
        }).toList();
    }

    /** 复盘查询: full ordered evidence chain of one plan. */
    @GetMapping("/{id}/evidence")
    public Map<String, Object> evidence(@PathVariable long id) {
        var plan = planDao.findById(id);
        if (plan == null) {
            throw new IllegalArgumentException("Plan not found: " + id);
        }
        List<Map<String, Object>> events = eventDao.findByPlanOrderBySeq(id).stream()
                .map(this::renderEvent)
                .toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("planId", id);
        response.put("planNo", plan.planNo());
        response.put("currentStatus", plan.status().name());
        response.put("eventCount", events.size());
        response.put("events", events);
        return response;
    }

    /** 校验接口: recompute the chain and locate the first break point. */
    @GetMapping("/{id}/verify")
    public VerificationResult verify(@PathVariable long id) {
        return verifier.verify(id);
    }

    // ------------------------------------------------------------------
    // Historical migration admin endpoints
    // ------------------------------------------------------------------

    /** Imports a raw legacy record into the staging table (test/admin use). */
    @PostMapping("/legacy-snapshots")
    public Map<String, Object> ingestLegacy(@RequestBody Map<String, String> body) {
        long snapshotId = legacyDao.insert(
                body.get("planNo"),
                body.get("status"),
                body.get("title"),
                body.get("legacyCreatedAt") == null
                        ? Instant.now()
                        : Instant.parse(body.get("legacyCreatedAt")));
        return Map.of("snapshotId", snapshotId);
    }

    /** Re-runs anchoring on demand; idempotent. */
    @PostMapping("/anchor-now")
    public AnchorMigrationService.MigrationReport anchorNow() {
        return migrationService.migrateAll();
    }

    @GetMapping("/legacy-snapshots")
    public List<Map<String, Object>> snapshots() {
        return legacyDao.findAll().stream().map(row -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", row.id());
            map.put("planNo", row.planNo());
            map.put("status", row.status());
            map.put("title", row.title());
            map.put("legacyCreatedAt", row.legacyCreatedAt());
            map.put("migratedAt", row.migratedAt());
            map.put("anchorEventId", row.anchorEventId());
            return map;
        }).toList();
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private Map<String, Object> renderResult(CommandResult r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("planId", r.planId());
        map.put("fromStatus", r.fromStatus());
        map.put("toStatus", r.toStatus());
        map.put("seq", r.seq());
        map.put("prevHash", r.prevHash());
        map.put("eventHash", r.eventHash());
        map.put("businessRecordId", r.businessRecordId());
        map.put("replayed", r.replayed());
        return map;
    }

    private Map<String, Object> renderEvent(EvidenceEventDao.EventRow e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", e.id());
        map.put("seq", e.seq());
        map.put("eventType", e.eventType());
        map.put("fromStatus", e.fromStatus());
        map.put("toStatus", e.toStatus());
        map.put("prevHash", e.prevHash());
        map.put("eventHash", e.eventHash());
        map.put("payloadHash", e.payloadHash());
        map.put("payloadJson", e.payloadJson());
        map.put("businessRecordId", e.businessRecordId());
        map.put("idempotencyKey", e.idempotencyKey());
        map.put("anchor", e.anchor());
        map.put("eventTime", Timestamp.from(e.eventTime()).toInstant());
        return map;
    }
}
