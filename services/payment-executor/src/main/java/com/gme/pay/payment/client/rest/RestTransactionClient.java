package com.gme.pay.payment.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.payment.domain.PaymentException;
import com.gme.pay.payment.domain.PaymentStatus;
import com.gme.pay.payment.domain.client.TransactionClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * REST adapter that persists transaction lifecycle changes via transaction-mgmt.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST  /v1/transactions} — create PENDING
 *   <li>{@code PATCH /v1/transactions/{ref}/status} — commit status change
 * </ul>
 *
 * <p>Base URL is read from {@code gmepay.transaction-mgmt.base-url} (default
 * {@code http://transaction-mgmt:8080}).
 */
@Component
@Primary
public class RestTransactionClient implements TransactionClient {

    private final RestClient restClient;

    @Autowired
    public RestTransactionClient(
            RestClient.Builder builder,
            @Value("${gmepay.transaction-mgmt.base-url:http://transaction-mgmt:8080}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    RestTransactionClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public CreateResult createPending(CreateRequest request) {
        try {
            TransactionCreatedResponse body = restClient.post()
                    .uri("/v1/transactions")
                    .body(new TransactionCreateRequest(
                            request.partnerId(),
                            request.partnerTxnRef(),
                            request.schemeId(),
                            request.direction(),
                            request.paymentMode(),
                            request.targetPayout(),
                            request.payoutCurrency(),
                            request.collectionAmount(),
                            request.collectionCurrency(),
                            request.merchantId(),
                            request.quoteId()))
                    .retrieve()
                    .body(TransactionCreatedResponse.class);

            if (body == null) {
                throw new PaymentException("transaction-mgmt returned empty body on create");
            }
            return new CreateResult(body.txnRef(), body.paymentId(), body.createdAt());
        } catch (RestClientResponseException ex) {
            throw new PaymentException(
                    "transaction-mgmt POST /v1/transactions failed: "
                            + ex.getStatusCode() + " " + ex.getResponseBodyAsString(), ex);
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException(
                    "transaction-mgmt POST /v1/transactions failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void commitStatus(String txnRef, StatusPatch patch) {
        try {
            restClient.patch()
                    .uri("/v1/transactions/{ref}/status", txnRef)
                    .body(new StatusPatchRequest(
                            patch.newStatus(),
                            patch.schemeTxnRef(),
                            patch.schemeApprovalCode(),
                            patch.prefundDeductedUsd(),
                            patch.approvedAt()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw new PaymentException(
                    "transaction-mgmt PATCH /v1/transactions/" + txnRef + "/status failed: "
                            + ex.getStatusCode() + " " + ex.getResponseBodyAsString(), ex);
        } catch (RuntimeException ex) {
            throw new PaymentException(
                    "transaction-mgmt PATCH /v1/transactions/" + txnRef + "/status failed: "
                            + ex.getMessage(), ex);
        }
    }

    // ---- wire formats ----

    record TransactionCreateRequest(
            long partnerId,
            String partnerTxnRef,
            String schemeId,
            String direction,
            String paymentMode,
            BigDecimal targetPayout,
            String payoutCurrency,
            BigDecimal collectionAmount,
            String collectionCurrency,
            String merchantId,
            String quoteId
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransactionCreatedResponse(
            String txnRef,
            String paymentId,
            Instant createdAt
    ) {}

    record StatusPatchRequest(
            PaymentStatus newStatus,
            String schemeTxnRef,
            String schemeApprovalCode,
            BigDecimal prefundDeductedUsd,
            Instant approvedAt
    ) {}
}
