package com.gme.sim.scheme.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * In-memory payment record maintained by the FSM.
 * <p>
 * Jackson annotations are additive so this mutable class can round-trip through
 * JSONL persistence (SchemeStore). The package-private all-args {@link JsonCreator}
 * constructor rebuilds a record — including mutable FSM state — on load; runtime
 * code continues to use the public 7-arg constructor + capture()/refund().
 */
public class PaymentRecord {

    private final String authId;
    private final String merchantId;
    private final BigDecimal amount;
    private final String currency;
    private final String payerRef;
    private final String schemeRef;
    private final Instant authorizedAt;

    private PaymentState state;
    private String schemeTxnRef;
    private Instant committedAt;
    private String refundId;
    private Instant refundedAt;

    public PaymentRecord(String authId, String merchantId, BigDecimal amount,
                         String currency, String payerRef, String schemeRef,
                         Instant authorizedAt) {
        this.authId       = authId;
        this.merchantId   = merchantId;
        this.amount       = amount;
        this.currency     = currency;
        this.payerRef     = payerRef;
        this.schemeRef    = schemeRef;
        this.authorizedAt = authorizedAt;
        this.state        = PaymentState.AUTHORIZED;
    }

    /**
     * All-args constructor used only by Jackson to rehydrate a persisted record,
     * including mutable FSM state. Not for runtime use.
     */
    @JsonCreator
    PaymentRecord(@JsonProperty("authId")       String authId,
                  @JsonProperty("merchantId")   String merchantId,
                  @JsonProperty("amount")       BigDecimal amount,
                  @JsonProperty("currency")     String currency,
                  @JsonProperty("payerRef")     String payerRef,
                  @JsonProperty("schemeRef")    String schemeRef,
                  @JsonProperty("authorizedAt") Instant authorizedAt,
                  @JsonProperty("state")        PaymentState state,
                  @JsonProperty("schemeTxnRef") String schemeTxnRef,
                  @JsonProperty("committedAt")  Instant committedAt,
                  @JsonProperty("refundId")     String refundId,
                  @JsonProperty("refundedAt")   Instant refundedAt) {
        this.authId       = authId;
        this.merchantId   = merchantId;
        this.amount       = amount;
        this.currency     = currency;
        this.payerRef     = payerRef;
        this.schemeRef    = schemeRef;
        this.authorizedAt = authorizedAt;
        this.state        = state;
        this.schemeTxnRef = schemeTxnRef;
        this.committedAt  = committedAt;
        this.refundId     = refundId;
        this.refundedAt   = refundedAt;
    }

    public String getAuthId()       { return authId; }
    public String getMerchantId()   { return merchantId; }
    public BigDecimal getAmount()   { return amount; }
    public String getCurrency()     { return currency; }
    public String getPayerRef()     { return payerRef; }
    public String getSchemeRef()    { return schemeRef; }
    public Instant getAuthorizedAt(){ return authorizedAt; }
    public PaymentState getState()  { return state; }
    public String getSchemeTxnRef() { return schemeTxnRef; }
    public Instant getCommittedAt() { return committedAt; }
    public String getRefundId()     { return refundId; }
    public Instant getRefundedAt()  { return refundedAt; }

    /** Transition AUTHORIZED → CAPTURED. */
    public void capture(String schemeTxnRef, Instant committedAt) {
        if (state != PaymentState.AUTHORIZED) {
            throw new IllegalStateException(
                    "Cannot capture from state " + state);
        }
        this.state        = PaymentState.CAPTURED;
        this.schemeTxnRef = schemeTxnRef;
        this.committedAt  = committedAt;
    }

    /** Transition CAPTURED → REFUNDED. */
    public void refund(String refundId, Instant refundedAt) {
        if (state != PaymentState.CAPTURED) {
            throw new IllegalStateException(
                    "Cannot refund from state " + state);
        }
        this.state      = PaymentState.REFUNDED;
        this.refundId   = refundId;
        this.refundedAt = refundedAt;
    }
}
