package com.gme.pay.auth.approval;

import com.gme.pay.auth.approval.ApprovalDtos.ApprovalRequestView;
import com.gme.pay.auth.approval.ApprovalDtos.DecisionLookup;
import com.gme.pay.auth.approval.ApprovalDtos.DecisionView;
import com.gme.pay.auth.approval.ApprovalDtos.RequestApprovalCommand;
import com.gme.pay.auth.persistence.ApprovalDecisionEntity;
import com.gme.pay.auth.persistence.ApprovalDecisionRepository;
import com.gme.pay.auth.persistence.ApprovalPolicyEntity;
import com.gme.pay.auth.persistence.ApprovalPolicyRepository;
import com.gme.pay.auth.persistence.ApprovalRequestEntity;
import com.gme.pay.auth.persistence.ApprovalRequestRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Multi-level approval engine. A {@link ApprovalPolicyEntity} maps a (request type, currency,
 * amount band) to an ordered list of approver permissions — DB-driven, no hardcoded tiers. A
 * request with no approval steps is auto-approved (self-service); otherwise it advances through its
 * steps in order, each gated on the approver holding that step's permission.
 *
 * <p><b>CFO break-glass:</b> an approver holding the {@code "*"} super-grant or
 * {@code approval.cfo_override} who does <em>not</em> already hold the current step's permission
 * may override it and finalise all remaining steps in one decision (recorded {@code cfoOverride=true}).
 * An approver who legitimately holds the step permission is recorded as an ordinary step approval
 * (so a senior who also has CFO rights does not silently collapse a multi-level flow).
 *
 * <p><b>Maker-checker:</b> the requester can never decide their own request, and no person may act
 * on a request twice — a 2-step (&gt;$5k) refund therefore needs three distinct identities. This is
 * enforced in-service <em>and</em> by a DB UNIQUE(request_id, approver_id); concurrent decisions are
 * serialised by an optimistic-lock {@code @Version} on the request (a lost race → 409).
 */
@Service
public class ApprovalWorkflowService {

    /** Break-glass grants: the universal super-permission and the explicit CFO override permission. */
    static final String SUPERUSER = "*";
    static final String CFO_OVERRIDE = "approval.cfo_override";

    private static final int MAX_REASON_LEN = 512;
    private static final int AMOUNT_SCALE = 4;        // NUMERIC(20,4)
    private static final int MAX_INTEGER_DIGITS = 16; // 20 - 4

    private final ApprovalPolicyRepository policies;
    private final ApprovalRequestRepository requests;
    private final ApprovalDecisionRepository decisions;

    public ApprovalWorkflowService(ApprovalPolicyRepository policies, ApprovalRequestRepository requests,
                                   ApprovalDecisionRepository decisions) {
        this.policies = policies;
        this.requests = requests;
        this.decisions = decisions;
    }

    // ------------------------------------------------------------------ request

    @Transactional
    public ApprovalRequestView request(RequestApprovalCommand cmd) {
        String type = require(cmd.requestType(), "requestType").toUpperCase(Locale.ROOT);
        String subjectRef = require(cmd.subjectRef(), "subjectRef");
        String currency = require(cmd.currency(), "currency").toUpperCase(Locale.ROOT);
        String requestedBy = require(cmd.requestedBy(), "requestedBy");
        BigDecimal amount = normalizeAmount(cmd.amount());

        ApprovalPolicyEntity policy = matchPolicy(type, currency, amount);
        List<String> steps = policy.steps();
        // A tier with no configured steps is auto-granted; steps always win over the auto_approve
        // flag, so a misconfigured (auto_approve=TRUE + steps) policy still requires its sign-offs.
        boolean auto = steps.isEmpty();
        Instant now = Instant.now();

        ApprovalRequestEntity e = new ApprovalRequestEntity(type, subjectRef, amount, currency,
                policy.getTierLabel(), (auto ? ApprovalStatus.AUTO_APPROVED : ApprovalStatus.PENDING).name(),
                String.join(",", steps), steps.size(), 0, requestedBy, now, cmd.tenantId());
        if (auto) {
            e.setDecidedAt(now);
        }
        requests.save(e);
        return view(e);
    }

    // ------------------------------------------------------------------ approve

    @Transactional
    public ApprovalRequestView approve(Long requestId, String approverId, Set<String> approverPermissions,
                                       String reason) {
        ApprovalRequestEntity e = pending(requestId);
        String approver = require(approverId, "approver");
        Set<String> perms = approverPermissions == null ? Set.of() : approverPermissions;
        validateReason(reason, false);

        guardMakerChecker(e, approver);
        String requiredPerm = e.steps().get(e.getCurrentStep());
        boolean holdsStep = perms.contains(requiredPerm);
        boolean cfo = !holdsStep && isCfo(perms); // break-glass only when not legitimately authorised
        if (!holdsStep && !cfo) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "approver lacks required permission '" + requiredPerm + "' for step " + e.getCurrentStep());
        }

        Instant now = Instant.now();
        ApprovalDecisionEntity d = new ApprovalDecisionEntity(e.getId(), e.getCurrentStep(),
                cfo ? null : requiredPerm, approver, "APPROVE", cfo, reason, now);

        // CFO break-glass finalises every remaining step in one signature; else advance one step.
        e.setCurrentStep(cfo ? e.getRequiredSteps() : e.getCurrentStep() + 1);
        if (e.getCurrentStep() >= e.getRequiredSteps()) {
            e.setStatus(ApprovalStatus.APPROVED.name());
            e.setDecidedAt(now);
        }
        persist(e, d);
        return view(e);
    }

    // ------------------------------------------------------------------- reject

    @Transactional
    public ApprovalRequestView reject(Long requestId, String approverId, Set<String> approverPermissions,
                                      String reason) {
        ApprovalRequestEntity e = pending(requestId);
        String approver = require(approverId, "approver");
        validateReason(reason, true);
        Set<String> perms = approverPermissions == null ? Set.of() : approverPermissions;
        guardMakerChecker(e, approver);
        // Authority to reject: CFO, or hold any of the request's step permissions.
        boolean cfo = isCfo(perms);
        if (!cfo && e.steps().stream().noneMatch(perms::contains)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "approver not authorised on this request");
        }

        Instant now = Instant.now();
        ApprovalDecisionEntity d = new ApprovalDecisionEntity(e.getId(), e.getCurrentStep(), null, approver,
                "REJECT", cfo, reason, now);
        e.setStatus(ApprovalStatus.REJECTED.name());
        e.setRejectReason(reason);
        e.setDecidedAt(now);
        persist(e, d);
        return view(e);
    }

    // -------------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public List<ApprovalRequestView> listPending() {
        return requests.findByStatusOrderByIdAsc(ApprovalStatus.PENDING.name())
                .stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public ApprovalRequestView get(Long id) {
        return view(requests.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "approval request not found: " + id)));
    }

    /**
     * Bridge for the RBAC APPROVAL constraint: is the latest request for this operation granted?
     * Scoped to {@code tenantId} (NULL = platform-global) so one tenant's approval can never satisfy
     * another tenant's identically-referenced operation.
     */
    @Transactional(readOnly = true)
    public DecisionLookup decision(String requestType, String subjectRef, Long tenantId) {
        String type = require(requestType, "requestType").toUpperCase(Locale.ROOT);
        return requests.findLatestForOperation(type, require(subjectRef, "subjectRef"), tenantId)
                .stream().findFirst()
                .map(e -> new DecisionLookup(ApprovalStatus.valueOf(e.getStatus()).isGranted(), e.getStatus()))
                .orElse(new DecisionLookup(false, "NONE"));
    }

    // ----------------------------------------------------------------- internals

    private ApprovalRequestEntity pending(Long requestId) {
        ApprovalRequestEntity e = requests.findById(requestId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "approval request not found: " + requestId));
        if (!ApprovalStatus.PENDING.name().equals(e.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "approval request " + requestId + " is " + e.getStatus() + ", not PENDING");
        }
        return e;
    }

    /** No self-approval, and no person acts on the same request twice (distinct approvers). */
    private void guardMakerChecker(ApprovalRequestEntity e, String approver) {
        if (approver.equals(e.getRequestedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "requester cannot decide their own request");
        }
        Set<String> priorApprovers = new HashSet<>();
        for (ApprovalDecisionEntity d : decisions.findByRequestIdOrderByStepIndexAsc(e.getId())) {
            priorApprovers.add(d.getApproverId());
        }
        if (priorApprovers.contains(approver)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "approver has already acted on this request (distinct approvers required)");
        }
    }

    /**
     * Persist the decision + request together, flushing so a concurrency conflict surfaces here:
     * the {@code @Version} optimistic lock (lost state-transition race) or the
     * UNIQUE(request_id, approver_id) (duplicate-approver race) both map to 409.
     */
    private void persist(ApprovalRequestEntity e, ApprovalDecisionEntity d) {
        try {
            decisions.saveAndFlush(d);
            requests.saveAndFlush(e);
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "concurrent decision on this request — reload and retry");
        }
    }

    private static boolean isCfo(Set<String> perms) {
        return perms.contains(SUPERUSER) || perms.contains(CFO_OVERRIDE);
    }

    /** Pick the policy whose band contains the amount: exact-currency first, then a NULL wildcard. */
    private ApprovalPolicyEntity matchPolicy(String type, String currency, BigDecimal amount) {
        List<ApprovalPolicyEntity> candidates = policies.findByRequestTypeAndActiveTrueOrderByMinAmountAsc(type);
        return candidates.stream()
                .filter(p -> currency.equalsIgnoreCase(p.getCurrency()) && p.matchesAmount(amount))
                .findFirst()
                .or(() -> candidates.stream()
                        .filter(p -> p.getCurrency() == null && p.matchesAmount(amount))
                        .findFirst())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "no approval policy matches " + type + " " + currency + " " + amount.toPlainString()));
    }

    private static BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must be >= 0");
        }
        BigDecimal rounded = amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        if (rounded.precision() - rounded.scale() > MAX_INTEGER_DIGITS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "amount exceeds the supported range (NUMERIC(20,4))");
        }
        return rounded;
    }

    private static void validateReason(String reason, boolean required) {
        if (required && (reason == null || reason.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reject reason is required");
        }
        if (reason != null && reason.length() > MAX_REASON_LEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "reason exceeds " + MAX_REASON_LEN + " characters");
        }
    }

    private static String require(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return v.trim();
    }

    private ApprovalRequestView view(ApprovalRequestEntity e) {
        List<DecisionView> ds = decisions.findByRequestIdOrderByStepIndexAsc(e.getId()).stream()
                .map(d -> new DecisionView(d.getId(), d.getStepIndex(), d.getRequiredPermission(),
                        d.getApproverId(), d.getDecision(), d.isCfoOverride(), d.getReason(), d.getDecidedAt()))
                .toList();
        return new ApprovalRequestView(e.getId(), e.getRequestType(), e.getSubjectRef(), e.getAmount(),
                e.getCurrency(), e.getTierLabel(), e.getStatus(), e.steps(), e.getRequiredSteps(),
                e.getCurrentStep(), e.getRequestedBy(), e.getRequestedAt(), e.getDecidedAt(),
                e.getRejectReason(), e.getTenantId(), ds);
    }
}
