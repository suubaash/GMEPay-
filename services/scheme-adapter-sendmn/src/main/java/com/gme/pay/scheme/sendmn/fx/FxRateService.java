package com.gme.pay.scheme.sendmn.fx;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.scheme.sendmn.dto.FxRateRegistrationRequest;
import com.gme.pay.scheme.sendmn.dto.FxRateRegistrationResponse;
import com.gme.pay.scheme.sendmn.persistence.SmnFxRateEntity;
import com.gme.pay.scheme.sendmn.persistence.SmnFxRateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Owns the SendMN-registered FX buy rates ({@code smn_fx_rates}).
 *
 * <p>SendMN pushes rates into us via the partner-hosted endpoint (we design the
 * contract — doc §9); the latest registered rate per currency pair is then the ONLY
 * legal input to Confirm's {@code FX_USD_BUY_RATE}, and {@code SETTLEMENT_AMOUNT} must
 * equal {@code LOCAL_PAYMENT_AMOUNT / rate} at scale 4 or SendMN rejects with 307.
 * (Exact rounding rule unconfirmed — open issue O3; HALF_UP assumed.)</p>
 */
@Service
public class FxRateService {

    /** USD settlement amounts are Decimal(18,4) on the SendMN wire. */
    public static final int SETTLEMENT_SCALE = 4;

    private final SmnFxRateRepository repository;

    public FxRateService(SmnFxRateRepository repository) {
        this.repository = repository;
    }

    /**
     * Registers a rate pushed by SendMN. Idempotent on {@code FX_TICKER_NO}: replays
     * acknowledge success without duplicating the row (SendMN may retry deliveries).
     */
    @Transactional
    public FxRateRegistrationResponse register(FxRateRegistrationRequest req) {
        if (req == null || isBlank(req.fxTickerNo())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "fx-rate: FX_TICKER_NO is required");
        }
        if (req.rate() == null || req.rate().signum() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "fx-rate: RATE must be a positive number");
        }
        if (isBlank(req.noticeDate())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "fx-rate: NOTICE_DATE is required");
        }
        Optional<SmnFxRateEntity> existing = repository.findByFxTickerNo(req.fxTickerNo());
        if (existing.isPresent()) {
            return new FxRateRegistrationResponse("0", "already registered", req.fxTickerNo(), true);
        }
        repository.save(new SmnFxRateEntity(
                req.fxTickerNo(),
                req.noticeDate(),
                req.rate(),
                defaultIfBlank(req.localCurCode(), "MNT"),
                defaultIfBlank(req.settlementCurCode(), "USD")));
        return new FxRateRegistrationResponse("0", "success", req.fxTickerNo(), false);
    }

    /** Latest registered rate for a pair; empty when SendMN has not registered one yet. */
    @Transactional(readOnly = true)
    public Optional<SmnFxRateEntity> latestRate(String localCurCode, String settlementCurCode) {
        return repository.findFirstByLocalCurCodeAndSettlementCurCodeOrderByNoticeDateDescIdDesc(
                localCurCode, settlementCurCode);
    }

    /**
     * {@code SETTLEMENT_AMOUNT} = local amount / registered buy rate, scale 4 HALF_UP —
     * the value SendMN recomputes and verifies (error 307 on mismatch).
     */
    public BigDecimal settlementAmount(BigDecimal localAmount, BigDecimal rate) {
        if (localAmount == null || rate == null || rate.signum() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "fx-rate: cannot compute settlement amount (amount=" + localAmount + ", rate=" + rate + ")");
        }
        return localAmount.divide(rate, SETTLEMENT_SCALE, RoundingMode.HALF_UP);
    }

    /** True when a proposed settlement amount matches our recomputation (pre-empts 307). */
    public boolean verifySettlementAmount(BigDecimal localAmount, BigDecimal rate, BigDecimal proposed) {
        return proposed != null && settlementAmount(localAmount, rate).compareTo(proposed) == 0;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String defaultIfBlank(String s, String dflt) {
        return isBlank(s) ? dflt : s;
    }
}
