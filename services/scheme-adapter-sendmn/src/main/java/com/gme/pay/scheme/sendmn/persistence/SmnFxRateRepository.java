package com.gme.pay.scheme.sendmn.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Repository for SendMN-registered FX buy rates ({@code smn_fx_rates}). */
public interface SmnFxRateRepository extends JpaRepository<SmnFxRateEntity, Long> {

    Optional<SmnFxRateEntity> findByFxTickerNo(String fxTickerNo);

    /**
     * The current effective rate for a pair: latest NOTICE_DATE, id as a same-date
     * tiebreak (later registration wins).
     */
    Optional<SmnFxRateEntity> findFirstByLocalCurCodeAndSettlementCurCodeOrderByNoticeDateDescIdDesc(
            String localCurCode, String settlementCurCode);
}
