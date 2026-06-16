package com.gme.sim.merchant.jeonmun.zeropay;

import static com.gme.sim.merchant.jeonmun.zeropay.ZeroPayMessages.*;

import com.gme.sim.merchant.jeonmun.JeonmunCodec;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and parses the 고정형 MPM 결제결과등록 전문 (거래구분코드 500000).
 *
 * <p>A static QR (QR구분 "1") carries no amount and no per-transaction serial, so after the
 * consumer pays, the operator registers the <em>result</em> of that payment against the
 * static QR's identifier. This is that registration message. In the merchant terminal it is
 * the wire view for the "register static-QR result" action.
 */
public final class ZeroPayStatic500000 {

    private ZeroPayStatic500000() {}

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter T = DateTimeFormatter.ofPattern("HHmmss");

    /**
     * @param txnUniqueNo   partner txn ref (≤13 AN) → field 22
     * @param requestingOrg KFTC 결제사업자 code (3 AN) → field 23
     * @param amountKrw     amount the consumer paid → field 31
     * @param vatKrw        VAT portion → field 33
     * @param qrRegistrarId 등록기관ID of the static QR → field 35
     * @param staticQrSerial the static QR's identifier → field 36
     * @param qrCheckChar   체크문자 of the static QR → field 37
     * @param merchantId    가맹점ID → field 38
     * @param terminalNo    단말기관리번호 → field 39
     * @param approvalNo    승인번호 to register the result against → field 51
     * @param traceNo       unique 8-digit message trace → field 10
     * @param txnDate       KST business date → field 21
     * @param sentAt        message send + approval timestamp (KST)
     */
    public record StaticResultRequest(
            String txnUniqueNo, String requestingOrg,
            long amountKrw, long vatKrw,
            String qrRegistrarId, String staticQrSerial, String qrCheckChar,
            String merchantId, String terminalNo,
            String approvalNo, String traceNo,
            LocalDate txnDate, LocalDateTime sentAt) {}

    /** Parsed 0210 registration response. */
    public record Response(
            String messageType, String status, String responseCode,
            String txnUniqueNo, String resultCode, String resultMsg, boolean registered) {}

    /** Builds the field-value map for a 0200 static-result registration request. */
    public static Map<Integer, String> requestValues(StaticResultRequest r) {
        Map<Integer, String> v = new HashMap<>();
        v.put(F_SYSTEM_CODE, SYSTEM_CODE);
        v.put(F_MESSAGE_TYPE, MSG_PAYMENT_REQ);
        v.put(F_TXN_DIVISION, TXN_DIV_MPM_STATIC_REG);
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
        v.put(F_QR_DIVISION, QR_DIV_MPM_STATIC);
        v.put(F_QR_REGISTRAR_ID, r.qrRegistrarId());
        v.put(F_QR_SERIAL, r.staticQrSerial());
        v.put(F_QR_CHECK_CHAR, r.qrCheckChar());
        v.put(F_MERCHANT_ID, r.merchantId());
        v.put(F_TERMINAL_NO, r.terminalNo());
        v.put(F_APPROVAL_NO, r.approvalNo());
        v.put(F_APPROVAL_DATE, r.sentAt().format(D));
        v.put(F_APPROVAL_TIME, r.sentAt().format(T));
        return v;
    }

    /** Encodes a 0200 static-result registration request to the 1,000-byte 500000 body. */
    public static byte[] encodeRegistration(StaticResultRequest r) {
        return JeonmunCodec.encode(MPM_STATIC_RESULT_500000, requestValues(r), CHARSET, ONLINE_MESSAGE_LENGTH);
    }

    /** Annotated, field-by-field view of a 0200 registration request. */
    public static List<JeonmunCodec.AnnotatedField> annotateRegistration(StaticResultRequest r) {
        return JeonmunCodec.annotate(MPM_STATIC_RESULT_500000, requestValues(r), CHARSET);
    }

    /** Decodes a 0210 registration response. {@code registered} = blank/"000" result code. */
    public static Response decodeResponse(byte[] data) {
        Map<Integer, String> m = JeonmunCodec.decode(MPM_STATIC_RESULT_500000, data, CHARSET, ONLINE_MESSAGE_LENGTH);
        String rc = m.getOrDefault(F_RESPONSE_CODE, "").trim();
        String resultCode = m.getOrDefault(F_RESULT_CODE, "").trim();
        String status = m.getOrDefault(F_STATUS, "").trim();
        boolean registered = (rc.isEmpty() || "000".equals(rc))
                && (resultCode.isEmpty() || "0000".equals(resultCode) || "000".equals(resultCode));
        return new Response(
                m.get(F_MESSAGE_TYPE), status, rc,
                m.get(F_TXN_UNIQUE_NO), resultCode, m.get(F_RESULT_MSG), registered);
    }
}
