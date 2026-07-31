package com.gme.pay.scheme.sendmn.api;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.scheme.sendmn.dto.FxRateRegistrationRequest;
import com.gme.pay.scheme.sendmn.dto.FxRateRegistrationResponse;
import com.gme.pay.scheme.sendmn.fx.FxRateService;
import com.gme.pay.scheme.sendmn.persistence.SmnFxRateEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The partner-hosted FX rate surface — the one direction where SENDMN calls US
 * (doc §9): SendMN regularly registers its settled buy rate here, and Confirm's
 * {@code SETTLEMENT_AMOUNT} must be computed against the latest registered rate
 * (SendMN verifies server-side, error 307).
 *
 * <p>{@code POST /partner-hosted/fx-rate} is the SendMN-facing registration endpoint;
 * {@code GET /internal/scheme/sendmn/fx-rate/latest} is an internal/ops lookup of the
 * effective rate.</p>
 */
@RestController
public class FxRateController {

    private final FxRateService fxRates;

    public FxRateController(FxRateService fxRates) {
        this.fxRates = fxRates;
    }

    /** POST /partner-hosted/fx-rate — SendMN registers a buy rate (idempotent on FX_TICKER_NO). */
    @PostMapping("/partner-hosted/fx-rate")
    public ResponseEntity<FxRateRegistrationResponse> register(@RequestBody FxRateRegistrationRequest req) {
        return ResponseEntity.ok(fxRates.register(req));
    }

    /** GET /internal/scheme/sendmn/fx-rate/latest — the rate Confirm would settle at right now. */
    @GetMapping("/internal/scheme/sendmn/fx-rate/latest")
    public ResponseEntity<Map<String, Object>> latest(
            @RequestParam(name = "localCurCode", defaultValue = "MNT") String localCurCode,
            @RequestParam(name = "settlementCurCode", defaultValue = "USD") String settlementCurCode) {
        SmnFxRateEntity rate = fxRates.latestRate(localCurCode, settlementCurCode)
                .orElseThrow(() -> new ApiException(ErrorCode.NO_SCHEME_FOR_LOCATION,
                        "no SendMN-registered rate for " + localCurCode + "/" + settlementCurCode));
        return ResponseEntity.ok(Map.of(
                "fxTickerNo", rate.getFxTickerNo(),
                "noticeDate", rate.getNoticeDate(),
                "rate", rate.getRate().toPlainString(),
                "localCurCode", rate.getLocalCurCode(),
                "settlementCurCode", rate.getSettlementCurCode()));
    }
}
