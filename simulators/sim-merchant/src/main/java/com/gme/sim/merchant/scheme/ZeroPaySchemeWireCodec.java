package com.gme.sim.merchant.scheme;

import com.gme.sim.merchant.emvco.ZeroPayQrBuilder;
import com.gme.sim.merchant.jeonmun.JeonmunCodec;
import com.gme.sim.merchant.jeonmun.zeropay.ZeroPayMessages;
import com.gme.sim.merchant.jeonmun.zeropay.ZeroPayMpm420000;
import com.gme.sim.merchant.jeonmun.zeropay.ZeroPayStatic500000;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * ZeroPay implementation of {@link SchemeWireCodec}: EMVCo MPM QR + KFTC 전문 (420000/500000).
 *
 * <p>GMEPay+ is a 해외페이 (overseas) 결제사업자, so dynamic charges are encoded with the
 * overseas prepaid-combo branch (settle as 결제사 자체선불 전액).
 */
@Component
public class ZeroPaySchemeWireCodec implements SchemeWireCodec {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String PROTOCOL = "전문 / jeonmun (KFTC ZeroPay, TCP, EUC-KR)";

    private final Clock clock;

    public ZeroPaySchemeWireCodec() {
        this(Clock.system(KST));
    }

    /** Test seam: inject a fixed clock for deterministic timestamps. */
    public ZeroPaySchemeWireCodec(Clock clock) {
        this.clock = clock;
    }

    @Override public String schemeId() { return "ZEROPAY"; }
    @Override public String displayName() { return "ZeroPay"; }
    @Override public String payoutCurrency() { return "KRW"; }

    @Override
    public QrView buildStaticQr(MerchantView m) {
        ZeroPayQrBuilder.QrResult q = ZeroPayQrBuilder.buildStatic(
                m.registrarId(), m.merchantId(), m.mcc(), m.name(), m.city());
        return toQrView(q, "MPM_STATIC");
    }

    @Override
    public DynamicCharge buildDynamicCharge(MerchantView m, long amountMinor,
                                            String txnUniqueNo, String traceNo) {
        String serial = qrSerial(traceNo);
        ZeroPayQrBuilder.QrResult q = ZeroPayQrBuilder.buildDynamic(
                m.registrarId(), m.merchantId(), serial, m.mcc(), m.name(), m.city(), amountMinor);

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = LocalDate.now(clock);
        ZeroPayMpm420000.PaymentRequest req = new ZeroPayMpm420000.PaymentRequest(
                txnUniqueNo, m.requestingOrg(),
                amountMinor, vatIncluded(amountMinor),
                q.registrarId(), q.qrSerial(), q.checkChar(),
                m.merchantId(), m.terminalNo(),
                true /* 해외페이 */, traceNo,
                today, now);

        byte[] frame = ZeroPayMpm420000.encodePayment(req);
        WireMessage wire = new WireMessage(
                schemeId(), PROTOCOL,
                ZeroPayMessages.TXN_DIV_MPM_DYNAMIC, ZeroPayMessages.MSG_PAYMENT_REQ,
                "변동형 MPM 결제요청 (overseas 해외페이)",
                "EUC-KR", frame.length, hex(frame),
                toWireFields(ZeroPayMpm420000.annotatePayment(req)));

        return new DynamicCharge(toQrView(q, "MPM_DYNAMIC"), wire);
    }

    @Override
    public WireMessage buildStaticResult(MerchantView m, long amountMinor,
                                         String approvalNo, String txnUniqueNo, String traceNo) {
        // The static QR's identifier is stable per merchant/registrar (no per-txn serial).
        ZeroPayQrBuilder.QrResult staticQr = ZeroPayQrBuilder.buildStatic(
                m.registrarId(), m.merchantId(), m.mcc(), m.name(), m.city());
        String staticSerial = "STATIC-" + m.merchantId();
        String checkChar = ZeroPayQrBuilder.checkChar(m.registrarId(), m.merchantId(), staticSerial, null);

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = LocalDate.now(clock);
        ZeroPayStatic500000.StaticResultRequest req = new ZeroPayStatic500000.StaticResultRequest(
                txnUniqueNo, m.requestingOrg(),
                amountMinor, vatIncluded(amountMinor),
                m.registrarId(), staticSerial, checkChar,
                m.merchantId(), m.terminalNo(),
                approvalNo, traceNo,
                today, now);

        byte[] frame = ZeroPayStatic500000.encodeRegistration(req);
        return new WireMessage(
                schemeId(), PROTOCOL,
                ZeroPayMessages.TXN_DIV_MPM_STATIC_REG, ZeroPayMessages.MSG_PAYMENT_REQ,
                "고정형 MPM 결제결과등록",
                "EUC-KR", frame.length, hex(frame),
                toWireFields(ZeroPayStatic500000.annotateRegistration(req)));
    }

    // ---- helpers ----

    private QrView toQrView(ZeroPayQrBuilder.QrResult q, String mode) {
        return new QrView(
                q.qrPayload(), mode, q.qrDivision(), q.registrarId(), q.merchantId(),
                emptyToNull(q.qrSerial()), emptyToNull(q.checkChar()),
                "KRW", ZeroPayQrBuilder.KRW_NUMERIC, q.amountKrw());
    }

    private static List<WireField> toWireFields(List<JeonmunCodec.AnnotatedField> ann) {
        List<WireField> out = new ArrayList<>(ann.size());
        for (JeonmunCodec.AnnotatedField a : ann) {
            out.add(new WireField(a.no(), a.key(), a.korean(), a.type().name(),
                    a.offset(), a.length(), a.value()));
        }
        return out;
    }

    /** Korean VAT is 10% tax-included: VAT portion = amount / 11 (floor). */
    private static long vatIncluded(long amount) {
        return amount / 11;
    }

    /** Per-transaction QR serial derived from the trace number (≤50 AN). */
    private static String qrSerial(String traceNo) {
        return "ZPQR" + traceNo;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte value : b) sb.append(String.format("%02X", value & 0xFF));
        return sb.toString();
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
