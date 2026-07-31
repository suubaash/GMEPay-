package com.gme.sim.sendmn.api;

import com.gme.sim.sendmn.fx.FxPushClient;
import com.gme.sim.sendmn.fx.FxState;
import com.gme.sim.sendmn.model.Merchant;
import com.gme.sim.sendmn.model.MerchantRegistry;
import com.gme.sim.sendmn.model.PaymentRecord;
import com.gme.sim.sendmn.model.PaymentStore;
import com.gme.sim.sendmn.scenario.ScenarioState;
import com.gme.sim.sendmn.token.TokenStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Same-origin sim console endpoints (NOT part of the SendMN contract) — merchant/QR
 * lookup for E2E tests, FX-rate registration, scenario toggles, token force-expiry,
 * and payment inspection. All plain JSON, no auth, no envelope.
 */
@RestController
public class SimAdminController {

    private final MerchantRegistry merchants;
    private final PaymentStore payments;
    private final FxState fxState;
    private final FxPushClient fxPush;
    private final ScenarioState scenario;
    private final TokenStore tokenStore;

    public SimAdminController(MerchantRegistry merchants, PaymentStore payments, FxState fxState,
                              FxPushClient fxPush, ScenarioState scenario, TokenStore tokenStore) {
        this.merchants = merchants;
        this.payments = payments;
        this.fxState = fxState;
        this.fxPush = fxPush;
        this.scenario = scenario;
        this.tokenStore = tokenStore;
    }

    // ------------------------------------------------------------- merchants / QR

    /** Seeded Mongolian merchants incl. their scannable static QR payloads. */
    @GetMapping("/sim/merchants")
    public List<Map<String, Object>> merchants() {
        return merchants.all().stream().map(SimAdminController::merchantJson).toList();
    }

    /** The raw QR string for one merchant — what an E2E test feeds into the wallet scan. */
    @GetMapping("/sim/qr/{merchantId}")
    public ResponseEntity<String> qr(@PathVariable String merchantId) {
        return merchants.byMerchantId(merchantId)
                .map(m -> ResponseEntity.ok(m.qrCode()))
                .orElse(ResponseEntity.notFound().build());
    }

    // ------------------------------------------------------------------ FX rate

    @GetMapping("/sim/fx-rate")
    public Map<String, Object> fxRate() {
        return fxJson();
    }

    /** Registers a new MNT/USD buy rate (accepts {"rate": ...} or the doc's {"RATE": ...}). */
    @PostMapping("/sim/fx-rate")
    public ResponseEntity<Map<String, Object>> setFxRate(@RequestBody Map<String, Object> body) {
        Object raw = body.getOrDefault("rate", body.get("RATE"));
        if (raw == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "field 'rate' is required"));
        }
        try {
            fxState.set(new BigDecimal(String.valueOf(raw)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid rate: " + raw));
        }
        return ResponseEntity.ok(fxJson());
    }

    /** Pushes the current rate to the partner's /partner-hosted/fx-rate (doc §9 direction). */
    @PostMapping("/sim/fx-rate/push")
    public ResponseEntity<Map<String, Object>> pushFxRate() {
        try {
            String partnerResponse = fxPush.push();
            Map<String, Object> out = fxJson();
            out.put("partnerResponse", partnerResponse);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of(
                    "error", "fx push failed (is the partner/adapter running?)",
                    "detail", String.valueOf(e.getMessage())));
        }
    }

    // ------------------------------------------------------------------ scenario

    @GetMapping("/sim/scenario")
    public Map<String, Object> scenario() {
        return scenarioJson();
    }

    /** Partial update of the failure toggles; omitted fields keep their value. */
    @PostMapping("/sim/scenario")
    public Map<String, Object> setScenario(@RequestBody Map<String, Object> body) {
        if (body.containsKey("forceError304")) scenario.setForceError304(bool(body.get("forceError304")));
        if (body.containsKey("forceError307")) scenario.setForceError307(bool(body.get("forceError307")));
        if (body.containsKey("neverApprove")) scenario.setNeverApprove(bool(body.get("neverApprove")));
        if (body.containsKey("delayMillis")) scenario.setDelayMillis(Long.parseLong(String.valueOf(body.get("delayMillis"))));
        if (body.containsKey("approveAfterPolls")) scenario.setApproveAfterPolls(Integer.parseInt(String.valueOf(body.get("approveAfterPolls"))));
        return scenarioJson();
    }

    @PostMapping("/sim/scenario/reset")
    public Map<String, Object> resetScenario() {
        scenario.reset();
        return scenarioJson();
    }

    // ------------------------------------------------------------------ tokens / payments

    /** Force-expires every issued token — next business call answers S104. */
    @PostMapping("/sim/expire-tokens")
    public Map<String, Object> expireTokens() {
        tokenStore.expireAll();
        return Map.of("expired", true);
    }

    /** Inspect one payment record by TX_TOKEN_NO. */
    @GetMapping("/sim/payments/{txTokenNo}")
    public ResponseEntity<Map<String, Object>> payment(@PathVariable String txTokenNo) {
        return payments.find(txTokenNo)
                .map(r -> ResponseEntity.ok(paymentJson(r)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> merchantJson(Merchant m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("merchantId", m.merchantId());
        out.put("numericId", m.numericId());
        out.put("name", m.name());
        out.put("address", m.address());
        out.put("terminalId", m.terminalId());
        out.put("fixedAmount", m.fixedAmount() == null ? null : m.fixedAmount().toPlainString());
        out.put("qrCode", m.qrCode());
        return out;
    }

    private Map<String, Object> fxJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fxTickerNo", fxState.fxTickerNo());
        out.put("noticeDate", fxState.noticeDate());
        out.put("rate", fxState.rate().toPlainString());
        out.put("localCurCode", "MNT");
        out.put("settlementCurCode", "USD");
        return out;
    }

    private Map<String, Object> scenarioJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("forceError304", scenario.isForceError304());
        out.put("forceError307", scenario.isForceError307());
        out.put("neverApprove", scenario.isNeverApprove());
        out.put("delayMillis", scenario.getDelayMillis());
        out.put("approveAfterPolls", scenario.getApproveAfterPolls());
        return out;
    }

    private static Map<String, Object> paymentJson(PaymentRecord r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("txTokenNo", r.getTxTokenNo());
        out.put("status", r.statusLabel());
        out.put("paymentNo", r.getPaymentNo());
        out.put("paymentReceiptNo", r.getPaymentReceiptNo());
        out.put("localAmount", r.getLocalAmount() == null ? null : r.getLocalAmount().toPlainString());
        out.put("settlementAmount", r.getSettlementAmount() == null ? null : r.getSettlementAmount().toPlainString());
        out.put("settlementDate", r.getSettlementDate());
        out.put("reconcileDate", r.getReconcileDate());
        out.put("pollCount", r.getPollCount());
        out.put("merchantId", r.getMerchant() == null ? null : r.getMerchant().merchantId());
        return out;
    }

    private static boolean bool(Object v) {
        return Boolean.parseBoolean(String.valueOf(v));
    }
}
