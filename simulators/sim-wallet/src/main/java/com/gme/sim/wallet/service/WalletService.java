package com.gme.sim.wallet.service;

import com.gme.sim.wallet.config.WalletProperties;
import com.gme.sim.wallet.model.MpmPreview;
import com.gme.sim.wallet.model.PartnerProfile;
import com.gme.sim.wallet.model.Receipt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core wallet business logic.
 *
 * GMEREMIT (domestic, KRW):
 *   chargeAmount = payAmountKrw + 500 serviceFee, currency=KRW, fxApplied=false.
 *
 * SENDMN (overseas, MNT):
 *   midRate = KRW/MNT from rate sim.
 *   mntAmount = payAmountKrw * midRate * (1 + 0.02 margin), rounded to 0 dp.
 *   chargeAmount = mntAmount (MNT), plus KRW 500 shown separately on receipt.
 *   fxApplied=true, fxRate = effective rate = mntAmount / payAmountKrw.
 */
@Service
public class WalletService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final WalletProperties props;
    private final SchemeClient schemeClient;
    private final RateClient rateClient;

    // In-memory receipt store (dev harness — no DB needed)
    private final ConcurrentHashMap<String, Receipt> receipts = new ConcurrentHashMap<>();

    @Autowired
    public WalletService(WalletProperties props, SchemeClient schemeClient, RateClient rateClient) {
        this.props = props;
        this.schemeClient = schemeClient;
        this.rateClient = rateClient;
    }

    // ------------------------------------------------------------------
    // CPM: customer presents their QR to a merchant terminal
    // ------------------------------------------------------------------

    public String generateCpmToken(PartnerProfile partner, String customerId,
                                   BigDecimal amount, String currency) {
        String effectiveCurrency = currency != null ? currency
                : (partner == PartnerProfile.SENDMN ? "MNT" : "KRW");
        return schemeClient.generateCpmToken(customerId, amount, effectiveCurrency);
    }

    // ------------------------------------------------------------------
    // MPM: customer scans the merchant's QR
    // ------------------------------------------------------------------

    public MpmPreview scanMpm(PartnerProfile partner, String qrPayload) {
        MpmPreview preview = schemeClient.decodeQr(qrPayload);
        // For SENDMN we show the amount in MNT context; the scheme sim returns KRW amount.
        // The UI conversion happens server-side only on /pay — here we just surface the raw preview.
        return preview;
    }

    // ------------------------------------------------------------------
    // Pay
    // ------------------------------------------------------------------

    public Receipt pay(PartnerProfile partner, String mode,
                       String qrPayloadOrCpmToken, BigDecimal payAmountKrw) {

        BigDecimal serviceFee = props.getServiceFeeKrw();
        String receiptId = UUID.randomUUID().toString();

        if (partner == PartnerProfile.GMEREMIT) {
            // GMEREMIT: no FX, charge = payAmountKrw + serviceFee KRW
            BigDecimal totalKrw = payAmountKrw.add(serviceFee);

            String authCode = schemeClient.authorize(partner.name(), mode,
                    qrPayloadOrCpmToken, totalKrw);
            String txnRef = schemeClient.commit(authCode);

            Receipt receipt = new Receipt(
                    receiptId,
                    partner.name(),
                    mode,
                    payAmountKrw.toPlainString(),
                    serviceFee.toPlainString(),
                    false,
                    "KRW",
                    totalKrw.toPlainString(),
                    null,
                    txnRef,
                    ZonedDateTime.ofInstant(Instant.now(), KST)
            );
            receipts.put(receiptId, receipt);
            return receipt;

        } else {
            // SENDMN: FX + 2% margin, charge in MNT; KRW 500 service fee shown separately
            BigDecimal midRate = rateClient.getMidRate("KRW", "MNT");
            BigDecimal margin  = props.getSendmnFxMargin();

            // mntCharge = payAmountKrw * midRate * (1 + margin)
            BigDecimal mntCharge = payAmountKrw
                    .multiply(midRate)
                    .multiply(BigDecimal.ONE.add(margin))
                    .setScale(0, RoundingMode.HALF_UP);

            // effective FX rate = mntCharge / payAmountKrw (for display)
            BigDecimal effectiveRate = mntCharge.divide(payAmountKrw, 6, RoundingMode.HALF_UP);

            // We authorize the KRW amount + fee so the scheme sees the KRW side
            BigDecimal authKrw = payAmountKrw.add(serviceFee);
            String authCode = schemeClient.authorize(partner.name(), mode,
                    qrPayloadOrCpmToken, authKrw);
            String txnRef = schemeClient.commit(authCode);

            Receipt receipt = new Receipt(
                    receiptId,
                    partner.name(),
                    mode,
                    payAmountKrw.toPlainString(),
                    serviceFee.toPlainString(),
                    true,
                    "MNT",
                    mntCharge.toPlainString(),
                    effectiveRate.toPlainString(),
                    txnRef,
                    ZonedDateTime.ofInstant(Instant.now(), KST)
            );
            receipts.put(receiptId, receipt);
            return receipt;
        }
    }

    // ------------------------------------------------------------------
    // Receipt lookup
    // ------------------------------------------------------------------

    public Receipt getReceipt(String id) {
        return receipts.get(id);
    }

    // ------------------------------------------------------------------
    // Partner resolution helper (shared by controllers)
    // ------------------------------------------------------------------

    public PartnerProfile resolvePartner(String partnerParam) {
        if (partnerParam == null || partnerParam.isBlank()) {
            return props.getDefaultPartner();
        }
        try {
            return PartnerProfile.valueOf(partnerParam.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown partner '" + partnerParam
                    + "'; valid values: GMEREMIT, SENDMN");
        }
    }
}
