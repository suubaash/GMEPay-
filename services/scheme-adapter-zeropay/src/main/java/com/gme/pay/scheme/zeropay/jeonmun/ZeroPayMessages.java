package com.gme.pay.scheme.zeropay.jeonmun;

import static com.gme.pay.scheme.zeropay.jeonmun.FieldType.A;
import static com.gme.pay.scheme.zeropay.jeonmun.FieldType.AN;
import static com.gme.pay.scheme.zeropay.jeonmun.FieldType.ANY;
import static com.gme.pay.scheme.zeropay.jeonmun.FieldType.N;
import static com.gme.pay.scheme.zeropay.jeonmun.FieldType.NSP;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * ZeroPay 전문 layouts + protocol constants (KFTC ZeroPay API §3.5).
 *
 * <p>Field keys are ASCII (English); the authoritative Korean names + the full field table
 * are in {@code Documentation/ZeroPay-API-Integration-Parameters.md}. Field No.0 (TCP/IP
 * Header, 7B) is the transport frame and is NOT part of these 1,000-byte body layouts.
 */
public final class ZeroPayMessages {

    private ZeroPayMessages() {}

    /** ZeroPay messages are byte-framed in EUC-KR. */
    public static final Charset CHARSET = Charset.forName("EUC-KR");

    /** Online 전문 body length (fields 1..50), excluding the 7-byte TCP/IP header. */
    public static final int ONLINE_MESSAGE_LENGTH = 1000;

    // ---- protocol constants ----
    public static final String SYSTEM_CODE = "ZPY";                 // field 1
    public static final String TXN_DIV_CPM = "400000";              // field 3
    public static final String TXN_DIV_MPM_DYNAMIC = "420000";      // field 3
    public static final String TXN_DIV_MPM_STATIC_REG = "500000";   // field 3
    public static final String MSG_PAYMENT_REQ = "0200";            // field 2
    public static final String MSG_PAYMENT_RESP = "0210";
    public static final String MSG_CANCEL_REQ = "0400";
    public static final String MSG_CANCEL_RESP = "0410";
    public static final String SEND_FLAG = "0";                     // field 4 (request)
    public static final String RELAY_CENTRE_ORG = "099";            // field 24 on request
    public static final String QR_DIV_MPM_STATIC = "1";             // field 34
    public static final String QR_DIV_MPM_DYNAMIC = "2";
    public static final String QR_DIV_CPM_DYNAMIC = "3";
    public static final String PREPAID_COMBO_OPERATOR = "O";        // field 47 (overseas pay)

    // ---- field No. constants (the ones callers populate / read) ----
    public static final int F_SYSTEM_CODE = 1;
    public static final int F_MESSAGE_TYPE = 2;
    public static final int F_TXN_DIVISION = 3;
    public static final int F_SEND_RECV_FLAG = 4;
    public static final int F_STATUS = 5;
    public static final int F_RESPONSE_CODE = 6;
    public static final int F_RESPONSE_CODE_ORG = 7;
    public static final int F_SEND_DATE = 8;
    public static final int F_SEND_TIME = 9;
    public static final int F_TRACE_NO = 10;
    public static final int F_TXN_DATE = 21;
    public static final int F_TXN_UNIQUE_NO = 22;
    public static final int F_REQUESTING_ORG = 23;
    public static final int F_RESPONDING_ORG = 24;
    public static final int F_AMOUNT = 31;
    public static final int F_SERVICE_CHARGE = 32;
    public static final int F_VAT = 33;
    public static final int F_QR_DIVISION = 34;
    public static final int F_QR_REGISTRAR_ID = 35;
    public static final int F_QR_SERIAL = 36;
    public static final int F_QR_CHECK_CHAR = 37;
    public static final int F_MERCHANT_ID = 38;
    public static final int F_TERMINAL_NO = 39;
    public static final int F_MERCHANT_EXTRA = 40;
    public static final int F_MERCHANT_FEE = 41;
    public static final int F_PREPAID_COMBO_CODE = 47;
    public static final int F_OPERATOR_PREPAID_AMOUNT = 48;
    public static final int F_DIRECT_PREPAID_AMOUNT = 49;

    /** 공통정보부 (common header) fields 1..11 — field 0 (TCP/IP header) handled by transport. */
    static final List<FieldSpec> COMMON_HEADER = List.of(
            new FieldSpec(1, "system_code", A, 3),
            new FieldSpec(2, "message_type", N, 4),
            new FieldSpec(3, "txn_division", N, 6),
            new FieldSpec(4, "send_recv_flag", N, 1),
            new FieldSpec(5, "status", N, 3),
            new FieldSpec(6, "response_code", AN, 3),
            new FieldSpec(7, "response_code_org", AN, 3),
            new FieldSpec(8, "send_date", N, 8),
            new FieldSpec(9, "send_time", N, 6),
            new FieldSpec(10, "trace_no", N, 8),
            new FieldSpec(11, "common_filler", ANY, 35));

    /** 업무공통부 (business common) fields 21..26. */
    static final List<FieldSpec> BUSINESS_COMMON = List.of(
            new FieldSpec(21, "txn_date", N, 8),
            new FieldSpec(22, "txn_unique_no", AN, 13),
            new FieldSpec(23, "requesting_org", AN, 3),
            new FieldSpec(24, "responding_org", AN, 3),
            new FieldSpec(25, "org_error_msg", ANY, 100),
            new FieldSpec(26, "biz_common_filler", ANY, 93));

    /** 업무부 (business body) for 변동형 MPM 결제/결제취소 (거래구분코드 420000), fields 31..50. */
    static final List<FieldSpec> MPM_DYNAMIC_BODY = List.of(
            new FieldSpec(31, "amount", N, 12),
            new FieldSpec(32, "service_charge", N, 12),
            new FieldSpec(33, "vat", N, 12),
            new FieldSpec(34, "qr_division", AN, 1),
            new FieldSpec(35, "qr_registrar_id", AN, 2),
            new FieldSpec(36, "qr_serial", AN, 50),
            new FieldSpec(37, "qr_check_char", ANY, 4),
            new FieldSpec(38, "merchant_id", AN, 20),
            new FieldSpec(39, "terminal_no", ANY, 20),
            new FieldSpec(40, "merchant_extra", ANY, 50),
            new FieldSpec(41, "merchant_fee", N, 12),
            new FieldSpec(42, "firmbank_org", N, 3),
            new FieldSpec(43, "firmbank_user_org", AN, 20),
            new FieldSpec(44, "firmbank_sub_org", AN, 10),
            new FieldSpec(45, "firmbank_msg_no", AN, 20),
            new FieldSpec(46, "firmbank_extra", AN, 20),
            new FieldSpec(47, "prepaid_combo_code", AN, 1),
            new FieldSpec(48, "operator_prepaid_amount", NSP, 12),
            new FieldSpec(49, "direct_prepaid_amount", NSP, 12),
            new FieldSpec(50, "filler", ANY, 407));

    /** Full layout for the 변동형 MPM (420000) 결제/결제취소 전문 — must total 1,000 bytes. */
    public static final List<FieldSpec> MPM_DYNAMIC_420000 = concat(COMMON_HEADER, BUSINESS_COMMON, MPM_DYNAMIC_BODY);

    @SafeVarargs
    private static List<FieldSpec> concat(List<FieldSpec>... parts) {
        List<FieldSpec> all = new ArrayList<>();
        for (List<FieldSpec> p : parts) all.addAll(p);
        return List.copyOf(all);
    }
}
