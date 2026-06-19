package com.gme.pay.auth.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data repository for {@link ApprovalPolicyEntity} ({@code approval_policies}). */
public interface ApprovalPolicyRepository extends JpaRepository<ApprovalPolicyEntity, Long> {

    /** Active policies for a request type, narrowest band first (service picks by amount/currency). */
    List<ApprovalPolicyEntity> findByRequestTypeAndActiveTrueOrderByMinAmountAsc(String requestType);
}
