package com.gme.pay.qr.prefunding;

import com.gme.pay.contracts.PrefundingReleaseRequest;
import com.gme.pay.contracts.PrefundingReserveRequest;
import com.gme.pay.contracts.PrefundingReserveResponse;
import com.gme.pay.qr.domain.cpm.PrefundingReservationPort;
import com.gme.pay.qr.exception.QRErrorCode;
import com.gme.pay.qr.exception.QRParseException;
import com.gme.pay.qr.exception.SchemeUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;

/**
 * Production {@link PrefundingReservationPort} — calls prefunding's internal reserve/release API
 * (Phase 2, IR-qr-3). Gated by {@code gmepay.prefunding.reserve.enabled=true}; when absent (tests /
 * no-prefunding runs) {@link InMemoryPrefundingReservationFixture} is wired instead.
 *
 * <ul>
 *   <li>{@code POST /internal/v1/prefunding/{partnerId}/reserve} — 402 → {@link QRErrorCode#INSUFFICIENT_PREFUNDING}.</li>
 *   <li>{@code POST /internal/v1/prefunding/{partnerId}/release} — idempotent on the reserve key.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "gmepay.prefunding.reserve.enabled", havingValue = "true")
public class RestPrefundingReservationClient implements PrefundingReservationPort {

    private static final Logger log = LoggerFactory.getLogger(RestPrefundingReservationClient.class);

    private final RestClient restClient;
    private final String internalToken;

    public RestPrefundingReservationClient(
            RestClient.Builder builder,
            @Value("${gmepay.prefunding.base-url:http://prefunding:8080}") String baseUrl,
            @Value("${internal.api.token:changeme-internal-token}") String internalToken) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.internalToken = internalToken;
    }

    @Override
    public Reservation reserve(long partnerId, BigDecimal amountUsd, String idempotencyKey, String txnRef) {
        try {
            PrefundingReserveResponse res = restClient.post()
                    .uri("/internal/v1/prefunding/{partnerId}/reserve", partnerId)
                    .header("X-Internal-Token", internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PrefundingReserveRequest(partnerId, amountUsd, idempotencyKey, txnRef))
                    .retrieve()
                    .body(PrefundingReserveResponse.class);
            if (res == null) {
                throw new SchemeUnavailableException("prefunding reserve returned an empty body");
            }
            return new Reservation(res.reservationId(), res.reservedAmountUsd());
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.PAYMENT_REQUIRED.value()) {
                throw new QRParseException(QRErrorCode.INSUFFICIENT_PREFUNDING,
                        "prefunding overdraw for partner " + partnerId, ex);
            }
            throw new SchemeUnavailableException(
                    "prefunding reserve failed: " + ex.getStatusCode(), ex);
        }
    }

    @Override
    public void release(long partnerId, String reservationId, String idempotencyKey, String reason) {
        try {
            restClient.post()
                    .uri("/internal/v1/prefunding/{partnerId}/release", partnerId)
                    .header("X-Internal-Token", internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PrefundingReleaseRequest(partnerId, reservationId, idempotencyKey, reason))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            // Release is best-effort + idempotent on the prefunding side; never fail the sweep.
            log.warn("prefunding release failed (partner={}, key={}, status={}) — will retry on next sweep",
                    partnerId, idempotencyKey, ex.getStatusCode());
        }
    }
}
