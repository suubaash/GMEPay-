package com.gme.pay.auth.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.auth.approval.ApprovalDtos.ApprovalRequestView;
import com.gme.pay.auth.approval.ApprovalDtos.RequestApprovalCommand;
import com.gme.pay.auth.persistence.ApprovalDecisionRepository;
import com.gme.pay.auth.persistence.ApprovalPolicyRepository;
import com.gme.pay.auth.persistence.ApprovalRequestRepository;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.web.server.ResponseStatusException;

/**
 * H2 (PostgreSQL-compat) slice test for the multi-level approval engine against the V005 seed:
 * self-service auto-approval, single-step (L1) and sequential two-step (L2/CFO) tiers, the
 * permission gate per step, maker-checker (no self-approval, distinct approvers), CFO break-glass,
 * rejection, terminal-state guards, and the constraint-bridge decision lookup.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ApprovalWorkflowServiceSliceTest {

    @Autowired private ApprovalPolicyRepository policies;
    @Autowired private ApprovalRequestRepository requests;
    @Autowired private ApprovalDecisionRepository decisions;

    private ApprovalWorkflowService svc;

    private static final Set<String> L1 = Set.of("refund.approve_l1");
    private static final Set<String> L2 = Set.of("refund.approve_l2");
    private static final Set<String> CFO = Set.of("approval.cfo_override");
    private static final Set<String> SUPER = Set.of("*");

    @BeforeEach
    void setUp() {
        svc = new ApprovalWorkflowService(policies, requests, decisions);
    }

    private RequestApprovalCommand refund(String ref, String amount, String requester) {
        return new RequestApprovalCommand("REFUND", ref, new BigDecimal(amount), "USD", requester, null);
    }

    // ------------------------------------------------------------- self-service

    @Test
    void selfService_belowTier_isAutoApproved() {
        ApprovalRequestView v = svc.request(refund("RF-1", "500.00", "op.maria"));
        assertThat(v.status()).isEqualTo("AUTO_APPROVED");
        assertThat(v.tierLabel()).isEqualTo("SELF_SERVE");
        assertThat(v.requiredSteps()).isZero();
        assertThat(svc.decision("REFUND", "RF-1", null).approved()).isTrue();
    }

    // --------------------------------------------------------------------- L1

    @Test
    void l1_singleApproval_byAuthorisedApprover_approves() {
        ApprovalRequestView v = svc.request(refund("RF-2", "2500.00", "op.maria"));
        assertThat(v.status()).isEqualTo("PENDING");
        assertThat(v.tierLabel()).isEqualTo("L1");
        assertThat(v.requiredSteps()).isEqualTo(1);

        ApprovalRequestView done = svc.approve(v.id(), "op.kim", L1, "ok to refund");
        assertThat(done.status()).isEqualTo("APPROVED");
        assertThat(done.decisions()).singleElement()
                .satisfies(d -> {
                    assertThat(d.decision()).isEqualTo("APPROVE");
                    assertThat(d.approverId()).isEqualTo("op.kim");
                    assertThat(d.cfoOverride()).isFalse();
                });
        assertThat(svc.decision("REFUND", "RF-2", null).approved()).isTrue();
    }

    @Test
    void l1_wrongPermission_isForbidden() {
        ApprovalRequestView v = svc.request(refund("RF-3", "2500.00", "op.maria"));
        assertThatThrownBy(() -> svc.approve(v.id(), "op.kim", L2, "trying"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("refund.approve_l1");
    }

    @Test
    void l1_selfApproval_isForbidden() {
        ApprovalRequestView v = svc.request(refund("RF-4", "2500.00", "op.maria"));
        assertThatThrownBy(() -> svc.approve(v.id(), "op.maria", L1, "self"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("own request");
    }

    // ----------------------------------------------------------- L2 (two-step)

    @Test
    void l2_twoStep_requiresTwoDistinctApprovers_inOrder() {
        ApprovalRequestView v = svc.request(refund("RF-5", "9000.00", "op.maria"));
        assertThat(v.tierLabel()).isEqualTo("L2_CFO");
        assertThat(v.requiredSteps()).isEqualTo(2);

        // step 0: L1 approver advances but request stays pending
        ApprovalRequestView afterL1 = svc.approve(v.id(), "op.kim", L1, "L1 ok");
        assertThat(afterL1.status()).isEqualTo("PENDING");
        assertThat(afterL1.currentStep()).isEqualTo(1);

        // step 1: a DIFFERENT L2 approver finalises
        ApprovalRequestView afterL2 = svc.approve(v.id(), "op.lee", L2, "L2 ok");
        assertThat(afterL2.status()).isEqualTo("APPROVED");
        assertThat(afterL2.decisions()).hasSize(2);
    }

    @Test
    void l2_sameApproverTwice_isRejected_distinctApproversRequired() {
        ApprovalRequestView v = svc.request(refund("RF-6", "9000.00", "op.maria"));
        svc.approve(v.id(), "op.kim", Set.of("refund.approve_l1", "refund.approve_l2"), "step0");
        // same person tries step 1 — blocked even though they hold L2
        assertThatThrownBy(() -> svc.approve(v.id(), "op.kim",
                Set.of("refund.approve_l1", "refund.approve_l2"), "step1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already acted");
    }

    @Test
    void l2_wrongPermissionAtStep1_isForbidden() {
        ApprovalRequestView v = svc.request(refund("RF-7", "9000.00", "op.maria"));
        svc.approve(v.id(), "op.kim", L1, "step0");
        // step 1 needs refund.approve_l2; an L1-only approver cannot finalise
        assertThatThrownBy(() -> svc.approve(v.id(), "op.lee", L1, "step1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("refund.approve_l2");
    }

    // ----------------------------------------------------------- CFO break-glass

    @Test
    void cfo_breakGlass_finalisesAllStepsInOneSignature() {
        ApprovalRequestView v = svc.request(refund("RF-8", "20000.00", "op.maria"));
        assertThat(v.requiredSteps()).isEqualTo(2);

        ApprovalRequestView done = svc.approve(v.id(), "cfo.park", CFO, "break-glass: urgent chargeback");
        assertThat(done.status()).isEqualTo("APPROVED");
        assertThat(done.currentStep()).isEqualTo(2); // jumped past both steps
        assertThat(done.decisions()).singleElement()
                .satisfies(d -> assertThat(d.cfoOverride()).isTrue());
    }

    @Test
    void superuserWildcard_alsoActsAsCfo() {
        ApprovalRequestView v = svc.request(refund("RF-9", "8000.00", "op.maria"));
        ApprovalRequestView done = svc.approve(v.id(), "root", SUPER, "superuser");
        assertThat(done.status()).isEqualTo("APPROVED");
        assertThat(done.decisions().get(0).cfoOverride()).isTrue();
    }

    @Test
    void cfo_holdingStepPermission_recordedAsNormalApproval_notBreakGlass() {
        // A senior approver who holds BOTH the step permission AND cfo_override must be treated as an
        // ordinary step approval (not break-glass), so a multi-level flow is not silently collapsed.
        ApprovalRequestView v = svc.request(refund("RF-14", "9000.00", "op.maria"));
        var both = Set.of("refund.approve_l1", "approval.cfo_override");
        ApprovalRequestView afterStep0 = svc.approve(v.id(), "op.kim", both, "L1 ok");
        assertThat(afterStep0.status()).isEqualTo("PENDING");      // did NOT collapse all steps
        assertThat(afterStep0.currentStep()).isEqualTo(1);
        assertThat(afterStep0.decisions().get(0).cfoOverride()).isFalse(); // recorded as normal
        // a genuine break-glass (lacks the step perm) still finalises in one signature
        ApprovalRequestView done = svc.approve(v.id(), "cfo.park", CFO, "break-glass");
        assertThat(done.status()).isEqualTo("APPROVED");
        assertThat(done.decisions().get(1).cfoOverride()).isTrue();
    }

    @Test
    void decision_isTenantScoped() {
        svc.request(new RequestApprovalCommand("REFUND", "RF-T", new BigDecimal("500"), "USD", "op.maria", 100L));
        assertThat(svc.decision("REFUND", "RF-T", 100L).approved()).isTrue();   // same tenant
        assertThat(svc.decision("REFUND", "RF-T", 200L).approved()).isFalse();  // sibling tenant — no leak
        assertThat(svc.decision("REFUND", "RF-T", null).approved()).isFalse();  // platform-global — no match
    }

    @Test
    void amount_isRoundedToScale4_andOverLargeRejected() {
        ApprovalRequestView v = svc.request(refund("RF-15", "2500.123456", "op.maria"));
        assertThat(v.amount()).isEqualByComparingTo("2500.1235"); // HALF_UP to scale 4

        assertThatThrownBy(() -> svc.request(refund("RF-16", "12345678901234567", "op.maria")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("range");
    }

    @Test
    void reason_overMaxLength_isBadRequest() {
        ApprovalRequestView v = svc.request(refund("RF-17", "2500.00", "op.maria"));
        String tooLong = "x".repeat(513);
        assertThatThrownBy(() -> svc.approve(v.id(), "op.kim", L1, tooLong))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("512");
    }

    @Test
    void uniqueConstraint_blocksDuplicateApproverDecision() {
        // DB backstop for maker-checker under concurrency: same approver twice on one request → rejected.
        ApprovalRequestView v = svc.request(refund("RF-18", "9000.00", "op.maria"));
        decisions.saveAndFlush(new com.gme.pay.auth.persistence.ApprovalDecisionEntity(
                v.id(), 0, "refund.approve_l1", "op.kim", "APPROVE", false, "first", java.time.Instant.now()));
        assertThatThrownBy(() -> decisions.saveAndFlush(new com.gme.pay.auth.persistence.ApprovalDecisionEntity(
                v.id(), 1, "refund.approve_l2", "op.kim", "APPROVE", false, "second", java.time.Instant.now())))
                .isInstanceOf(Exception.class);
    }

    @Test
    void cfo_cannotApproveOwnRequest() {
        ApprovalRequestView v = svc.request(refund("RF-10", "8000.00", "cfo.park"));
        assertThatThrownBy(() -> svc.approve(v.id(), "cfo.park", CFO, "self"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("own request");
    }

    // -------------------------------------------------------------- reject / guards

    @Test
    void reject_withReason_setsRejected() {
        ApprovalRequestView v = svc.request(refund("RF-11", "2500.00", "op.maria"));
        ApprovalRequestView done = svc.reject(v.id(), "op.kim", L1, "duplicate refund");
        assertThat(done.status()).isEqualTo("REJECTED");
        assertThat(done.rejectReason()).isEqualTo("duplicate refund");
        assertThat(svc.decision("REFUND", "RF-11", null).approved()).isFalse();
    }

    @Test
    void reject_withoutReason_isBadRequest() {
        ApprovalRequestView v = svc.request(refund("RF-12", "2500.00", "op.maria"));
        assertThatThrownBy(() -> svc.reject(v.id(), "op.kim", L1, "  "))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void approve_alreadyDecided_isConflict() {
        ApprovalRequestView v = svc.request(refund("RF-13", "2500.00", "op.maria"));
        svc.approve(v.id(), "op.kim", L1, "ok");
        assertThatThrownBy(() -> svc.approve(v.id(), "op.lee", L1, "again"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not PENDING");
    }

    @Test
    void noMatchingPolicy_isUnprocessable() {
        // EUR has no seeded policy
        assertThatThrownBy(() -> svc.request(new RequestApprovalCommand(
                "REFUND", "RF-EUR", new BigDecimal("100"), "EUR", "op.maria", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no approval policy");
    }

    @Test
    void pendingQueue_isFifo() {
        svc.request(refund("RF-A", "2500", "op.maria"));
        svc.request(refund("RF-B", "2500", "op.maria"));
        var pending = svc.listPending();
        assertThat(pending).extracting(ApprovalRequestView::subjectRef).containsExactly("RF-A", "RF-B");
    }
}
