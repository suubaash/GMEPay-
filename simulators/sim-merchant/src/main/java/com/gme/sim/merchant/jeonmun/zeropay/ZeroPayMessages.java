package com.gme.sim.merchant.jeonmun.zeropay;

import static com.gme.sim.merchant.jeonmun.FieldType.A;
import static com.gme.sim.merchant.jeonmun.FieldType.AN;
import static com.gme.sim.merchant.jeonmun.FieldType.ANY;
import static com.gme.sim.merchant.jeonmun.FieldType.N;
import static com.gme.sim.merchant.jeonmun.FieldType.NSP;

import com.gme.sim.merchant.jeonmun.FieldSpec;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * ZeroPay 전문 layouts + protocol constants (KFTC ZeroPay API §3.5), modelled inside the
 * simulator so it can emit/parse the real KFTC wire format independently of the production
 * {@code scheme-adapter-zeropay} adapter.
 *
 * <p>Field No.0 (TCP/IP Header, 7B) is the transport frame and is NOT part of these
 * 1,000-byte body layouts. The authoritative field table is in
 * {@code Documentation/ZeroPay-API-Integration-Parameters.md}.
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
    // static-result (500000) specific
    public static final int F_APPROVAL_NO = 51;
    public static final int F_APPROVAL_DATE = 52;
    public static final int F_APPROVAL_TIME = 53;
    public static final int F_RESULT_CODE = 54;
    public static final int F_RESULT_MSG = 55;

    /** 공통정보부 (common header) fields 1..11 — field 0 (TCP/IP header) handled by transport. */
    static final List<FieldSpec> COMMON_HEADER = List.of(
            new FieldSpec(1, "system_code", "시스템구분코드", A, 3),
            new FieldSpec(2, "message_type", "전문구분코드", N, 4),
            new FieldSpec(3, "txn_division", "거래구분코드", N, 6),
            new FieldSpec(4, "send_recv_flag", "송수신구분", N, 1),
            new FieldSpec(5, "status", "응답상태구분", N, 3),
            new FieldSpec(6, "response_code", "응답코드", AN, 3),
            new FieldSpec(7, "response_code_org", "응답기관코드", AN, 3),
            new FieldSpec(8, "send_date", "전송일자", N, 8),
            new FieldSpec(9, "send_time", "전송시각", N, 6),
            new FieldSpec(10, "trace_no", "추적번호", N, 8),
            new FieldSpec(11, "common_filler", "공통예비", ANY, 35));

    /** 업무공통부 (business common) fields 21..26. */
    static final List<FieldSpec> BUSINESS_COMMON = List.of(
            new FieldSpec(21, "txn_date", "거래일자", N, 8),
            new FieldSpec(22, "txn_unique_no", "거래고유번호", AN, 13),
            new FieldSpec(23, "requesting_org", "요청기관코드", AN, 3),
            new FieldSpec(24, "responding_org", "응답기관코드", AN, 3),
            new FieldSpec(25, "org_error_msg", "기관오류메시지", ANY, 100),
            new FieldSpec(26, "biz_common_filler", "업무공통예비", ANY, 93));

    /** 업무부 (business body) for 변동형 MPM 결제/결제취소 (거래구분코드 420000), fields 31..50. */
    static final List<FieldSpec> MPM_DYNAMIC_BODY = List.of(
            new FieldSpec(31, "amount", "거래금액", N, 12),
            new FieldSpec(32, "service_charge", "봉사료", N, 12),
            new FieldSpec(33, "vat", "부가세", N, 12),
            new FieldSpec(34, "qr_division", "QR구분", AN, 1),
            new FieldSpec(35, "qr_registrar_id", "QR등록기관ID", AN, 2),
            new FieldSpec(36, "qr_serial", "거래일련번호", AN, 50),
            new FieldSpec(37, "qr_check_char", "체크문자", ANY, 4),
            new FieldSpec(38, "merchant_id", "가맹점ID", AN, 20),
            new FieldSpec(39, "terminal_no", "단말기관리번호", ANY, 20),
            new FieldSpec(40, "merchant_extra", "가맹점예비", ANY, 50),
            new FieldSpec(41, "merchant_fee", "가맹점수수료", N, 12),
            new FieldSpec(42, "firmbank_org", "펌뱅킹기관", N, 3),
            new FieldSpec(43, "firmbank_user_org", "펌뱅킹이용기관", AN, 20),
            new FieldSpec(44, "firmbank_sub_org", "펌뱅킹하위기관", AN, 10),
            new FieldSpec(45, "firmbank_msg_no", "펌뱅킹전문번호", AN, 20),
            new FieldSpec(46, "firmbank_extra", "펌뱅킹예비", AN, 20),
            new FieldSpec(47, "prepaid_combo_code", "선불조합코드", AN, 1),
            new FieldSpec(48, "operator_prepaid_amount", "결제사선불금액", NSP, 12),
            new FieldSpec(49, "direct_prepaid_amount", "직접선불금액", NSP, 12),
            new FieldSpec(50, "filler", "예비", ANY, 407));

    /** 업무부 (business body) for 고정형 MPM 결제결과등록 (거래구분코드 500000), fields 31..55. */
    static final List<FieldSpec> MPM_STATIC_RESULT_BODY = List.of(
            new FieldSpec(31, "amount", "거래금액", N, 12),
            new FieldSpec(32, "service_charge", "봉사료", N, 12),
            new FieldSpec(33, "vat", "부가세", N, 12),
            new FieldSpec(34, "qr_division", "QR구분", AN, 1),
            new FieldSpec(35, "qr_registrar_id", "QR등록기관ID", AN, 2),
            new FieldSpec(36, "qr_serial", "정적QR일련번호", AN, 50),
            new FieldSpec(37, "qr_check_char", "체크문자", ANY, 4),
            new FieldSpec(38, "merchant_id", "가맹점ID", AN, 20),
            new FieldSpec(39, "terminal_no", "단말기관리번호", ANY, 20),
            new FieldSpec(41, "merchant_fee", "가맹점수수료", N, 12),
            new FieldSpec(51, "approval_no", "승인번호", AN, 12),
            new FieldSpec(52, "approval_date", "승인일자", N, 8),
            new FieldSpec(53, "approval_time", "승인시각", N, 6),
            new FieldSpec(54, "result_code", "결과코드", AN, 4),
            new FieldSpec(55, "result_msg", "결과메시지", ANY, 100),
            new FieldSpec(50, "filler", "예비", ANY, 425));

    /** Full layout for the 변동형 MPM (420000) 결제/결제취소 전문 — totals 1,000 bytes. */
    public static final List<FieldSpec> MPM_DYNAMIC_420000 =
            concat(COMMON_HEADER, BUSINESS_COMMON, MPM_DYNAMIC_BODY);

    /** Full layout for the 고정형 MPM (500000) 결제결과등록 전문 — totals 1,000 bytes. */
    public static final List<FieldSpec> MPM_STATIC_RESULT_500000 =
            concat(COMMON_HEADER, BUSINESS_COMMON, MPM_STATIC_RESULT_BODY);

    @SafeVarargs
    private static List<FieldSpec> concat(List<FieldSpec>... parts) {
        List<FieldSpec> all = new ArrayList<>();
        for (List<FieldSpec> p : parts) all.addAll(p);
        return List.copyOf(all);
    }
}
