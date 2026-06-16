package com.gme.pay.scheme.zeropay.jeonmun;

import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.CHARSET;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_AMOUNT;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_DIRECT_PREPAID_AMOUNT;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_MERCHANT_FEE;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_MERCHANT_ID;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_MESSAGE_TYPE;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_OPERATOR_PREPAID_AMOUNT;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_PREPAID_COMBO_CODE;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_QR_CHECK_CHAR;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_QR_DIVISION;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_QR_REGISTRAR_ID;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_QR_SERIAL;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_REQUESTING_ORG;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_RESPONDING_ORG;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_RESPONSE_CODE;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_RESPONSE_CODE_ORG;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_SEND_DATE;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_SEND_RECV_FLAG;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_SEND_TIME;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_STATUS;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_SYSTEM_CODE;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_TERMINAL_NO;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_TRACE_NO;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_TXN_DATE;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_TXN_DIVISION;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_TXN_UNIQUE_NO;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_VAT;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.MPM_DYNAMIC_420000;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.MSG_PAYMENT_REQ;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.ONLINE_MESSAGE_LENGTH;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.PREPAID_COMBO_OPERATOR;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.QR_DIV_MPM_DYNAMIC;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.RELAY_CENTRE_ORG;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.SEND_FLAG;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.SYSTEM_CODE;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.TXN_DIV_MPM_DYNAMIC;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds and parses the 변동형 MPM 결제/결제취소 전문 (거래구분코드 420000) — merchant-presented
 * dynamic QR. Encodes a {@link PaymentRequest} to the 1,000-byte body and decodes a scheme
 * {@link Response}. See {@code Documentation/ZeroPay-API-Integration-Parameters.md}.
 */
public final class ZeroPayMpm420000 {

    private ZeroPayMpm420000() {}

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter T = DateTimeFormatter.ofPattern("HHmmss");

    /**
     * @param txnUniqueNo   GMEPay+ partner txn ref (≤13 AN) → field 22
     * @param requestingOrg GMEPay+ KFTC 결제사업자 code (3 AN) → field 23
     * @param amountKrw     total amount incl. merchant fee + VAT (KRW) → field 31
     * @param vatKrw        VAT portion → field 33
     * @param qrRegistrarId 등록기관ID from the scanned QR → field 35
     * @param qrSerial      거래일련번호 from the dynamic QR → field 36
     * @param qrCheckChar   체크문자 from the QR → field 37
     * @param merchantId    merchant id → field 38
     * @param terminalNo    terminal management no. → field 39
     * @param overseas      true for 해외페이 (sets prepaid-combo "O", self-prepaid = amount)
     * @param traceNo       unique 8-digit message trace → field 10
     * @param txnDate       KST business date → field 21
     * @param sentAt        message send timestamp (KST) → fields 8/9
     */
    public record PaymentRequest(
            String txnUniqueNo, String requestingOrg,
            long amountKrw, long vatKrw,
            String qrRegistrarId, String qrSerial, String qrCheckChar,
            String merchantId, String terminalNo,
            boolean overseas, String traceNo,
            LocalDate txnDate, LocalDateTime sentAt) {}

    /** Parsed scheme response. {@code approved} is a heuristic — see {@link #decodeResponse}. */
    public record Response(
            String messageType, String status, String responseCode, String responseCodeOrg,
            String txnUniqueNo, long merchantFeeKrw, boolean approved) {}

    /** Encodes a 0200 payment request to the 1,000-byte 420000 body. */
    public static byte[] encodePayment(PaymentRequest r) {
        Map<Integer, String> v = new HashMap<>();
        v.put(F_SYSTEM_CODE, SYSTEM_CODE);
        v.put(F_MESSAGE_TYPE, MSG_PAYMENT_REQ);
        v.put(F_TXN_DIVISION, TXN_DIV_MPM_DYNAMIC);
        v.put(F_SEND_RECV_FLAG, SEND_FLAG);
        v.put(F_SEND_DATE, r.sentAt().format(D));
        v.put(F_SEND_TIME, r.sentAt().format(T));
        v.put(F_TRACE_NO, r.traceNo());
        v.put(F_TXN_DATE, r.txnDate().format(D));
        v.put(F_TXN_UNIQUE_NO, r.txnUniqueNo());
        v.put(F_REQUESTING_ORG, r.requestingOrg());
        v.put(F_RESPONDING_ORG, RELAY_CENTRE_ORG);
        v.put(F_AMOUNT, Long.toString(r.amountKrw()));
        v.put(F_VAT, Long.toString(r.vatKrw()));
        v.put(F_QR_DIVISION, QR_DIV_MPM_DYNAMIC);
        v.put(F_QR_REGISTRAR_ID, r.qrRegistrarId());
        v.put(F_QR_SERIAL, r.qrSerial());
        v.put(F_QR_CHECK_CHAR, r.qrCheckChar());
        v.put(F_MERCHANT_ID, r.merchantId());
        v.put(F_TERMINAL_NO, r.terminalNo());
        if (r.overseas()) {
            // 해외페이: settle as 결제사 자체선불 전액 (§4 of the integration spec)
            v.put(F_PREPAID_COMBO_CODE, PREPAID_COMBO_OPERATOR);
            v.put(F_OPERATOR_PREPAID_AMOUNT, Long.toString(r.amountKrw()));
            v.put(F_DIRECT_PREPAID_AMOUNT, "0");
        }
        return JeonmunCodec.encode(MPM_DYNAMIC_420000, v, CHARSET, ONLINE_MESSAGE_LENGTH);
    }

    /**
     * Decodes a 0210 response. {@code approved} is a heuristic (blank or "000" response code):
     * the authoritative success/decline codes live in the KFTC 응답코드 table, not in the
     * field-layout excerpt — wire the exact mapping when the code list is available.
     */
    public static Response decodeResponse(byte[] data) {
        Map<Integer, String> m = JeonmunCodec.decode(MPM_DYNAMIC_420000, data, CHARSET, ONLINE_MESSAGE_LENGTH);
        String rc = m.getOrDefault(F_RESPONSE_CODE, "").trim();
        String status = m.getOrDefault(F_STATUS, "").trim();
        boolean approved = rc.isEmpty() || "000".equals(rc);
        return new Response(
                m.get(F_MESSAGE_TYPE), status, rc, m.get(F_RESPONSE_CODE_ORG),
                m.get(F_TXN_UNIQUE_NO), parseLongOr0(m.get(F_MERCHANT_FEE)), approved);
    }

    private static long parseLongOr0(String s) {
        try {
            return (s == null || s.isBlank()) ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
