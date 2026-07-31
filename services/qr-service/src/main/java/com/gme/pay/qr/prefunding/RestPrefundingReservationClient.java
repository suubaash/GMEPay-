package com.gme.pay.qr.prefunding;

import com.gme.pay.contracts.PrefundingReleaseRequest;
import com.gme.pay.contracts.PrefundingReserveRequest;
import com.gme.pay.contracts.PrefundingReserveResponse;
import com.gme.pay.internalauth.InternalAuthHeaders;
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
 *
 * <h2>Internal auth (T0-5 / T0-2)</h2>
 *
 * <p>prefunding's entire balance API is behind the platform's service-to-service internal-auth gate
 * ({@code com.gme.pay.internalauth}, header {@link InternalAuthHeaders#INTERNAL_TOKEN}), so this
 * client presents the shared secret from {@code gmepay.internal-auth.secret}. Without it, every CPM
 * reserve/release 401s against a correctly deployed prefunding.
 *
 * <p>This replaces an invented, unverified scheme: the client used to send
 * {@code X-Internal-Token: ${internal.api.token:changeme-internal-token}} — a header <b>nothing on
 * the platform reads</b>, defaulted to a literal checked into main source and into
 * {@code application.yml}. It provided no authentication whatsoever while looking as though it did.
 * The new value has <b>no default</b>: a blank secret sends no header at all, so a gated prefunding
 * answers 401 (fail-closed) rather than the client shipping a fake credential.
 */
@Component
@ConditionalOnProperty(name = "gmepay.prefunding.reserve.enabled", havingValue = "true")
public class RestPrefundingReservationClient implements PrefundingReservationPort {

    private static final Logger log = LoggerFactory.getLogger(RestPrefundingReservationClient.class);

    private final RestClient restClient;

    public RestPrefundingReservationClient(
            RestClient.Builder builder,
            @Value("${gmepay.prefunding.base-url:http://prefunding:8080}") String baseUrl,
            @Value("${gmepay.internal-auth.secret:}") String internalSecret) {
        RestClient.Builder b = builder.baseUrl(baseUrl);
        if (internalSecret != null && !internalSecret.isBlank()) {
            b.defaultHeader(InternalAuthHeaders.INTERNAL_TOKEN, internalSecret);
        } else {
            log.warn("gmepay.internal-auth.secret is blank — CPM reserve/release calls to prefunding "
                    + "will carry no {} header and a gated prefunding will refuse them (401), which "
                    + "declines CPM issuance. Set GMEPAY_INTERNAL_AUTH_SECRET.",
                    InternalAuthHeaders.INTERNAL_TOKEN);
        }
        this.restClient = b.build();
    }

    @Override
    public Reservation reserve(long partnerId, BigDecimal amountUsd, String idempotencyKey, String txnRef) {
        try {
            PrefundingReserveResponse res = restClient.post()
                    .uri("/internal/v1/prefunding/{partnerId}/reserve", partnerId)
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
