package com.gme.pay.scheme.ninepay.dto;

/**
 * Request for POST /scheme/payout — submit a VND disbursement to 9Pay.
 *
 * @param requestId       hub-generated globally-unique idempotency key, ≤50 chars
 *                        (recommended 9Pay format {@code PartnerID+9P+YYYYMMDD+UniqueId}).
 *                        NEVER reuse for a different payment — 9Pay rejects reuse (1062)
 *                        and transfers cannot be cancelled.
 * @param bankNo          9Pay bank/wallet code (see GET /scheme/bank-list passthrough)
 * @param accountNo       beneficiary account or card number
 * @param accountType     0 = bank account, 1 = bank card
 * @param accountName     beneficiary name (bank-verified; First/Last order matters)
 * @param amountVnd       integer VND, minimum 2,000
 * @param content         transfer narrative — unaccented letters/digits/spaces only
 *                        (9Pay rejects special chars incl. {@code - _ | '})
 * @param senderName      optional AML: sender full name / enterprise
 * @param senderId        optional AML: sender ID/passport/business licence no.
 * @param recipientId     optional AML: recipient ID document
 * @param transferPurpose optional AML: purpose of transfer
 * @param qrStr           raw QR payload — REQUIRED when bankNo=VNPAY (from /scheme/decode-qr)
 * @param uCountry        optional ISO 3166-1 alpha-2 sender country
 * @param senderUid       optional per-sender limit key ({@code PARTNERID+UID}, uppercase)
 */
public record PayoutRequest(
        String requestId,
        String bankNo,
        String accountNo,
        int accountType,
        String accountName,
        long amountVnd,
        String content,
        String senderName,
        String senderId,
        String recipientId,
        String transferPurpose,
        String qrStr,
        String uCountry,
        String senderUid
) {}
