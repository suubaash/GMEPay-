package com.gme.pay.scheme.ninepay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Inbound 9Pay IPN push (spec 3.5 / 4.4). Field-name drift: the IPN carries
 * {@code trans_id} where every other surface says {@code transaction_id}.
 *
 * <p>9Pay signs {@code request_id|partner_id|trans_id|request_amount|fee|transfer_amount|
 * type|status|created_at} — {@code message}, {@code approved_at} and {@code code} are NOT
 * part of the signed string.</p>
 *
 * @param requestId      our original payout request_id
 * @param partnerId      our partner id
 * @param transId        9Pay transaction code
 * @param requestAmount  requested amount (integer VND)
 * @param fee            fee charged
 * @param transferAmount amount taken from the prefunded balance
 * @param type           {@code TRANSFER_BANK} = pay-out
 * @param status         PENDING / PROCESSING / FAIL / SUCCESS
 * @param createdAt      Y-m-d H:i:s (GMT+7)
 * @param signature      9Pay RSA signature (verify with 9Pay's public key)
 * @param message        bank approval message (unsigned)
 * @param approvedAt     approval time (unsigned)
 * @param code           message code 000–009 (unsigned): 000 success, 004 retryable,
 *                       008 held pending merchant confirmation, 009 post-SUCCESS bank reversal
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IpnRequest(
        @JsonProperty("request_id") String requestId,
        @JsonProperty("partner_id") String partnerId,
        @JsonProperty("trans_id") String transId,
        @JsonProperty("request_amount") Long requestAmount,
        @JsonProperty("fee") Long fee,
        @JsonProperty("transfer_amount") Long transferAmount,
        @JsonProperty("type") String type,
        @JsonProperty("status") String status,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("signature") String signature,
        @JsonProperty("message") String message,
        @JsonProperty("approved_at") String approvedAt,
        @JsonProperty("code") String code
) {}
