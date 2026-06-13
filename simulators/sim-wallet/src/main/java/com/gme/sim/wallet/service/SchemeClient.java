package com.gme.sim.wallet.service;

import com.gme.sim.wallet.config.WalletProperties;
import com.gme.sim.wallet.model.MpmPreview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * REST client for sim-scheme. All endpoints live under {@code /v1/scheme}.
 *
 * <p>The wallet speaks a simplified vocabulary ("mpm"/"cpm"); this client
 * translates it to the scheme's real contract (MPM_STATIC / MPM_DYNAMIC / CPM)
 * by decoding the QR to discover static-vs-dynamic, the embedded amount, and
 * the scheme currency. For a DYNAMIC QR the authorised amount MUST equal the
 * amount embedded in the QR (the scheme enforces AMOUNT_MISMATCH otherwise), so
 * we authorise the decoded merchant amount — the customer-side fee/FX is the
 * wallet's concern, not the scheme's.
 *
 * <p>Public method signatures are intentionally stable so WalletService (and
 * its unit tests, which mock this class) are unaffected.
 */
@Service
public class SchemeClient {

    private static final Logger log = LoggerFactory.getLogger(SchemeClient.class);

    private final String api;          // {schemeBaseUrl}/v1/scheme
    private final RestTemplate rest;

    @Autowired
    public SchemeClient(WalletProperties props, RestTemplate rest) {
        this.api  = props.getSchemeBaseUrl() + "/v1/scheme";
        this.rest = rest;
    }

    /** POST /v1/scheme/cpm/token → returns the CPM token string (field {@code cpmToken}). */
    @SuppressWarnings("unchecked")
    public String generateCpmToken(String customerId, BigDecimal amount, String currency) {
        try {
            var body = Map.of(
                    "customerId", customerId,
                    "fundingRef", "wallet-" + customerId
            );
            var resp = rest.postForObject(api + "/cpm/token", body, Map.class);
            if (resp == null || resp.get("cpmToken") == null) {
                throw new SimDownException("scheme sim returned empty CPM token response");
            }
            return (String) resp.get("cpmToken");
        } catch (RestClientException ex) {
            throw down(ex);
        }
    }

    /** POST /v1/scheme/qr/decode → returns a decoded QR preview. */
    @SuppressWarnings("unchecked")
    public MpmPreview decodeQr(String qrPayload) {
        try {
            var resp = rest.postForObject(api + "/qr/decode", Map.of("qrPayload", qrPayload), Map.class);
            if (resp == null) {
                throw new SimDownException("scheme sim returned null for /qr/decode");
            }
            String merchantName = (String) resp.getOrDefault("merchantName", "Unknown");
            String mode         = (String) resp.getOrDefault("mode", "static");
            String amount       = resp.get("amount") != null ? resp.get("amount").toString() : null;
            String currency     = (String) resp.getOrDefault("currency", "KRW");
            return new MpmPreview(merchantName, mode, amount, currency);
        } catch (RestClientException ex) {
            throw down(ex);
        }
    }

    /**
     * POST /v1/scheme/payments/authorize → returns the authId.
     *
     * @param partner payer reference (partner code)
     * @param mode    wallet-level mode: "mpm" or "cpm" (case-insensitive)
     * @param ref     the merchant QR payload (MPM) or the CPM token (CPM)
     * @param amount  the customer pay amount; for a DYNAMIC QR the scheme amount
     *                is taken from the QR instead (the two must match), for STATIC
     *                this amount is used.
     */
    @SuppressWarnings("unchecked")
    public String authorize(String partner, String mode, String ref, BigDecimal amount) {
        try {
            Map<String, Object> body = new HashMap<>();
            String schemeMode;
            String currency = "KRW";
            BigDecimal authAmount = amount;

            if (mode != null && mode.toLowerCase().startsWith("cpm")) {
                schemeMode = "CPM";
                body.put("cpmToken", ref);
            } else {
                // MPM — decode to learn static-vs-dynamic + embedded amount + currency
                MpmPreview p = decodeQr(ref);
                if (p.amount() != null && !p.amount().isBlank()) {
                    schemeMode = "MPM_DYNAMIC";
                    authAmount = new BigDecimal(p.amount());   // must equal the QR amount
                } else {
                    schemeMode = "MPM_STATIC";
                }
                if (p.currency() != null && !p.currency().isBlank()) currency = p.currency();
                body.put("qrPayload", ref);
            }

            body.put("mode", schemeMode);
            body.put("amount", authAmount.toPlainString());
            body.put("currency", currency);
            body.put("payerRef", partner);

            var resp = rest.postForObject(api + "/payments/authorize", body, Map.class);
            if (resp == null || resp.get("authId") == null) {
                throw new SimDownException("scheme authorize returned no authId");
            }
            return (String) resp.get("authId");
        } catch (RestClientException ex) {
            throw down(ex);
        }
    }

    /** POST /v1/scheme/payments/{authId}/commit → returns the schemeTxnRef. */
    @SuppressWarnings("unchecked")
    public String commit(String authId) {
        try {
            var resp = rest.postForObject(api + "/payments/" + authId + "/commit", null, Map.class);
            if (resp == null || resp.get("schemeTxnRef") == null) {
                throw new SimDownException("scheme commit returned no schemeTxnRef");
            }
            return (String) resp.get("schemeTxnRef");
        } catch (RestClientException ex) {
            throw down(ex);
        }
    }

    private SimDownException down(RestClientException ex) {
        log.warn("scheme sim unreachable at {}: {}", api, ex.getMessage());
        return new SimDownException("scheme sim is not reachable (" + api + "): " + ex.getMessage());
    }
}
