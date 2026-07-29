package com.gme.pay.scheme.ninepay.persistence;

import com.gme.pay.scheme.ninepay.status.PayoutStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Spring Data repository over {@code np_payouts}. */
public interface NpPayoutRepository extends JpaRepository<NpPayoutEntity, Long> {

    Optional<NpPayoutEntity> findByRequestId(String requestId);

    /** Non-final rows a reconciliation sweep would poll (SUBMITTED/PENDING/PROCESSING/UNKNOWN/HELD). */
    List<NpPayoutEntity> findByStatusIn(List<PayoutStatus> statuses);
}
