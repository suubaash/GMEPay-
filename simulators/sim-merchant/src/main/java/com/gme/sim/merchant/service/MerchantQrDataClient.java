package com.gme.sim.merchant.service;

import com.gme.sim.merchant.model.ShopRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mirrors a terminal-registered shop's QR into merchant-qr-data ({@code POST /v1/merchants}),
 * keyed by the FULL EMVCo QR payload the wallet scans.
 *
 * <p>Why: the Merchant Terminal registers shops in sim-scheme, but the payment path
 * (payment-executor → merchant-qr-data) resolves the scanned QR against merchant-qr-data's own
 * store. Without this mirror a freshly-registered shop 404s at pay time. We write through at the
 * moment a QR is minted (store-QR / dynamic charge), so the same payload resolves end-to-end.
 *
 * <p>Best-effort: a failure here never breaks QR display — the terminal still returns the QR,
 * it just logs that the payment side won't resolve it (e.g. merchant-qr-data down).
 */
@Service
public class MerchantQrDataClient {

    private static final Logger log = LoggerFactory.getLogger(MerchantQrDataClient.class);

    private final RestClient restClient;

    public MerchantQrDataClient(RestClient merchantQrDataRestClient) {
        this.restClient = merchantQrDataRestClient;
    }

    /**
     * Upserts the merchant behind {@code qrPayload} so payment-executor can resolve it.
     *
     * @param qrPayload the full EMVCo QR string (the merchant-qr-data lookup key)
     * @param shop      the registered shop the QR belongs to
     */
    public void mirror(String qrPayload, ShopRecord shop) {
        if (qrPayload == null || qrPayload.isBlank()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("qrCodeId",       qrPayload);
        body.put("merchantId",     shop.merchantId());
        body.put("merchantName",   shop.name());
        body.put("merchantType",   shop.merchantType());
        body.put("schemeId",       "ZEROPAY");
        body.put("payoutCurrency", "KRW");
        body.put("city",           shop.city());
        body.put("mcc",            shop.mcc());
        body.put("active",         true);
        try {
            restClient.post()
                    .uri("/v1/merchants")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Mirrored merchant {} into merchant-qr-data for QR resolution", shop.merchantId());
        } catch (Exception e) {
            // Non-fatal: QR still displays; payment resolution just won't find it until mirrored.
            log.warn("Could not mirror merchant {} into merchant-qr-data ({}): payment-side lookup "
                    + "will 404 for this QR until merchant-qr-data is reachable",
                    shop.merchantId(), e.getMessage());
        }
    }
}
