package com.gme.pay.payment.client.rest;

import com.gme.pay.payment.domain.client.RevenueLedgerClient;
import com.gme.pay.payment.persistence.RevenuePostingFailureStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * Real REST client for revenue-ledger's {@code POST /v1/journals/rounding-residual} endpoint.
 * Annotated {@code @Primary} so it wins over any stub bean in the same context.
 *
 * <p>Failures DO NOT throw to the orchestrator (the commit path must not fail because of a
 * downstream residual posting). 4xx responses are logged at WARN; 5xx are logged at ERROR;
 * the caller continues.
 *
 * <h2>Durability (gap T2-1 / CFO#6)</h2>
 * "The caller continues" used to mean the posting was simply LOST: the only trace of a revenue capture
 * that never reached revenue-ledger was a log line, so a ledger outage during peak silently dropped
 * revenue. Every swallowed failure — on all four surfaces — is now handed to
 * {@link RevenuePostingFailureStore}, which persists the exact request payload keyed by
 * {@code (reference, postingType)} for replay (revenue-ledger is idempotent on those references, so a
 * replay cannot double-book). The recorder is optional: when absent (minimal/test contexts) the client
 * behaves exactly as before.
 */
@Component
@Primary
public class RestRevenueLedgerClient implements RevenueLedgerClient {

    private static final Logger log = LoggerFactory.getLogger(RestRevenueLedgerClient.class);
    private static final String PATH = "/v1/journals/rounding-residual";
    private static final String CAPTURE_PATH = "/v1/revenue/capture";
    private static final String REVERSAL_PATH = "/v1/journals/reversal";
    private static final String COMMISSION_SPLIT_PATH = "/v1/revenue/commission-split";

    private final RestClient restClient;
    /** Durable failure sink; null in minimal contexts (behaviour then matches the pre-T2-1 client). */
    @Nullable private final RevenuePostingFailureStore failureStore;

    @Autowired
    public RestRevenueLedgerClient(
            RestClient.Builder builder,
            @Value("${gmepay.revenue-ledger.base-url:http://revenue-ledger:8080}") String baseUrl,
            @Nullable RevenuePostingFailureStore failureStore) {
        // T3-11: the INJECTED builder, not the static RestClient.builder() factory. Only the builder
        // BEAN receives RestClientCustomizer beans, so only it carries
        // HttpClientTimeoutAutoConfiguration's connect/read floor and payment-executor's 5s override;
        // the static factory produces a client with NO read timeout that reads as if it had one.
        // This client is invoked inside the payment path (revenue capture, commission split, residual)
        // and its documented contract is to SWALLOW failures into the durable failureStore — a hop
        // that swallows is exactly the hop that must be bounded, because an unbounded one does not
        // swallow anything, it holds the payment's thread open.
        this(builder.baseUrl(baseUrl).build(), failureStore);
    }

    /** Test-friendly constructor that takes a pre-built {@link RestClient}; no durable failure sink. */
    public RestRevenueLedgerClient(RestClient restClient) {
        this(restClient, null);
    }

    /** Test-friendly constructor with an explicit failure sink. */
    public RestRevenueLedgerClient(RestClient restClient,
                                   @Nullable RevenuePostingFailureStore failureStore) {
        this.restClient = restClient;
        this.failureStore = failureStore;
    }

    /**
     * Persist a swallowed posting failure for replay. Never throws — see
     * {@link RevenuePostingFailureStore#record}.
     */
    private void recordFailure(String reference, String postingType,
                               Map<String, Object> payload, String error) {
        if (failureStore != null) {
            failureStore.record(reference, postingType, payload, error);
        }
    }

    @Override
    public void postRoundingResidual(String reference, BigDecimal residual, String currency) {
        if (residual == null || residual.signum() == 0) {
            return; // zero residual = no-op, by contract
        }
        Map<String, Object> body = Map.of(
                "reference", reference,
                "residual", residual,
                "currency", currency);
        try {
            restClient.post()
                    .uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpServerErrorException ex) {
            log.error("revenue-ledger 5xx posting rounding residual ref={} residual={} {}: {}",
                    reference, residual, currency, ex.getStatusCode(), ex);
            // do NOT propagate — persisted for replay instead (T2-1)
            recordFailure(reference, RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL, body,
                    ex.toString());
        } catch (Exception ex) {
            // Could be 4xx, connection refused, timeout, etc. Same policy: log and continue.
            log.warn("revenue-ledger residual post failed ref={} residual={} {}: {}",
                    reference, residual, currency, ex.toString());
            recordFailure(reference, RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL, body,
                    ex.toString());
        }
    }

    @Override
    public void postRevenueCapture(String txnRef, long partnerId, long schemeId, LocalDate revenueDate,
                                   BigDecimal collectionMarginUsd, BigDecimal payoutMarginUsd,
                                   BigDecimal serviceCharge, String serviceChargeCcy,
                                   BigDecimal feeSharePct) {
        // LinkedHashMap (not Map.of): 9 entries, and a stable order keeps the JSON deterministic.
        // revenueDate is sent as an ISO string so it does not depend on this client's date-format config.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("txnRef", txnRef);
        body.put("partnerId", partnerId);
        body.put("schemeId", schemeId);
        body.put("revenueDate", revenueDate == null ? null : revenueDate.toString());
        body.put("collectionMarginUsd", collectionMarginUsd);
        body.put("payoutMarginUsd", payoutMarginUsd);
        body.put("serviceChargeAmount", serviceCharge);
        body.put("serviceChargeCcy", serviceChargeCcy);
        body.put("feeSharePct", feeSharePct);
        try {
            restClient.post()
                    .uri(CAPTURE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpServerErrorException ex) {
            log.error("revenue-ledger 5xx posting revenue capture ref={} {}: {}",
                    txnRef, ex.getStatusCode(), ex.toString());
            // do NOT propagate — persisted for replay instead (T2-1)
            recordFailure(txnRef, RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE, body,
                    ex.toString());
        } catch (Exception ex) {
            log.warn("revenue-ledger capture post failed ref={}: {}", txnRef, ex.toString());
            recordFailure(txnRef, RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE, body,
                    ex.toString());
        }
    }

    @Override
    public void postCommissionSplit(String txnRef, long partnerId, long schemeId, LocalDate revenueDate,
                                    long payoutAmountKrw, BigDecimal merchantFeeRate,
                                    BigDecimal vanFeeRate, BigDecimal gmeSharePct,
                                    BigDecimal partnerSharePct) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("txnRef", txnRef);
        body.put("partnerId", partnerId);
        body.put("schemeId", schemeId);
        body.put("revenueDate", revenueDate == null ? null : revenueDate.toString());
        body.put("payoutAmountKrw", payoutAmountKrw);
        body.put("merchantFeeRate", merchantFeeRate);
        body.put("vanFeeRate", vanFeeRate);
        body.put("gmeSharePct", gmeSharePct);
        body.put("partnerSharePct", partnerSharePct);
        try {
            restClient.post()
                    .uri(COMMISSION_SPLIT_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpServerErrorException ex) {
            log.error("revenue-ledger 5xx posting commission split ref={} {}: {}",
                    txnRef, ex.getStatusCode(), ex.toString());
            // do NOT propagate — the payment already committed; persisted for replay (T2-1)
            recordFailure(txnRef, RevenuePostingFailureStore.TYPE_COMMISSION_SPLIT, body,
                    ex.toString());
        } catch (Exception ex) {
            log.warn("revenue-ledger commission-split post failed ref={}: {}", txnRef, ex.toString());
            recordFailure(txnRef, RevenuePostingFailureStore.TYPE_COMMISSION_SPLIT, body,
                    ex.toString());
        }
    }

    @Override
    public void postReversalJournal(String reference, BigDecimal reversalAmount, String currency) {
        if (reversalAmount == null || reversalAmount.signum() == 0) {
            return; // nothing to reverse — no-op by contract
        }
        Map<String, Object> body = Map.of(
                "reference", reference,
                "reversalAmount", reversalAmount,
                "currency", currency);
        try {
            restClient.post()
                    .uri(REVERSAL_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpServerErrorException ex) {
            log.error("revenue-ledger 5xx posting reversal ref={} amount={} {}: {}",
                    reference, reversalAmount, currency, ex.getStatusCode(), ex);
            // do NOT propagate — the cancel already happened; persisted for replay (T2-1)
            recordFailure(reference, RevenuePostingFailureStore.TYPE_REVERSAL_JOURNAL, body,
                    ex.toString());
        } catch (Exception ex) {
            log.warn("revenue-ledger reversal post failed ref={} amount={} {}: {}",
                    reference, reversalAmount, currency, ex.toString());
            recordFailure(reference, RevenuePostingFailureStore.TYPE_REVERSAL_JOURNAL, body,
                    ex.toString());
        }
    }
}
