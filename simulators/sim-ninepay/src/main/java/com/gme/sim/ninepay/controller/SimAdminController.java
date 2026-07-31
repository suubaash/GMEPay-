package com.gme.sim.ninepay.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.gme.sim.ninepay.config.NinepaySimConfig;
import com.gme.sim.ninepay.model.NinepayStore;
import com.gme.sim.ninepay.model.Scenario;
import com.gme.sim.ninepay.model.TransferRecord;
import com.gme.sim.ninepay.sign.SimSigner;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sim-side (NOT 9Pay API) control surface under {@code /sim}:
 *
 * <ul>
 *   <li>{@code GET /sim/public-key} — the sim's RSA public key (PEM) + algorithm; feed it
 *       to the adapter's {@code ninepay-public-key-pem} so response/IPN verification works.</li>
 *   <li>{@code POST /sim/partner-key} — provision the partner's public key at runtime;
 *       from then on request signatures are STRICTLY verified (1007 on mismatch).</li>
 *   <li>{@code GET/POST /sim/scenario} — inspect / partially update the scenario toggles
 *       (transferMode NORMAL|FAIL_SYNC|TIMEOUT, syncErrorCode, ipnOutcome
 *       SUCCESS|FAIL|HELD|REVERSAL, ipnFailCode).</li>
 *   <li>{@code GET /sim/transfers}, {@code GET /sim/ipns}, {@code GET /sim/balance} —
 *       inspection; {@code POST /sim/reset} — wipe ledger/outbox, re-seed balance,
 *       reset scenario.</li>
 * </ul>
 */
@RestController
@RequestMapping("/sim")
public class SimAdminController {

    private final NinepaySimConfig config;
    private final NinepayStore store;
    private final SimSigner signer;
    private final Scenario scenario;

    public SimAdminController(NinepaySimConfig config, NinepayStore store, SimSigner signer,
                              Scenario scenario) {
        this.config = config;
        this.store = store;
        this.signer = signer;
        this.scenario = scenario;
    }

    @GetMapping("/public-key")
    public ResponseEntity<Map<String, Object>> publicKey() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("algorithm", signer.algorithm());
        body.put("public_key_pem", signer.publicKeyPem());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/partner-key")
    public ResponseEntity<Map<String, Object>> partnerKey(@RequestBody JsonNode body) {
        signer.setPartnerPublicKeyPem(body.path("public_key_pem").asText(null));
        return ResponseEntity.ok(Map.of("verifying_requests", signer.isVerifyingRequests()));
    }

    @GetMapping("/scenario")
    public ResponseEntity<Map<String, Object>> scenario() {
        return ResponseEntity.ok(scenarioView());
    }

    /** Partial update: only the fields present in the body change. */
    @PostMapping("/scenario")
    public ResponseEntity<Map<String, Object>> updateScenario(@RequestBody JsonNode body) {
        if (body.hasNonNull("transferMode")) {
            scenario.setTransferMode(Scenario.TransferMode.valueOf(
                    body.path("transferMode").asText().toUpperCase(Locale.ROOT)));
        }
        if (body.hasNonNull("syncErrorCode")) {
            scenario.setSyncErrorCode(body.path("syncErrorCode").asText());
        }
        if (body.hasNonNull("ipnOutcome")) {
            scenario.setIpnOutcome(Scenario.IpnOutcome.valueOf(
                    body.path("ipnOutcome").asText().toUpperCase(Locale.ROOT)));
        }
        if (body.hasNonNull("ipnFailCode")) {
            scenario.setIpnFailCode(body.path("ipnFailCode").asText());
        }
        return ResponseEntity.ok(scenarioView());
    }

    @GetMapping("/transfers")
    public ResponseEntity<List<Map<String, Object>>> transfers() {
        return ResponseEntity.ok(store.allTransfers().stream().map(this::transferView).toList());
    }

    @GetMapping("/ipns")
    public ResponseEntity<List<NinepayStore.IpnOutboxEntry>> ipns() {
        return ResponseEntity.ok(store.ipns());
    }

    @GetMapping("/balance")
    public ResponseEntity<Map<String, Object>> balance() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("balance_vnd", store.balance());
        body.put("initial_balance_vnd", config.getInitialBalanceVnd());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset() {
        store.reset();
        scenario.reset();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reset", true);
        body.put("balance_vnd", store.balance());
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> scenarioView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("transferMode", scenario.getTransferMode().name());
        view.put("syncErrorCode", scenario.getSyncErrorCode());
        view.put("ipnOutcome", scenario.getIpnOutcome().name());
        view.put("ipnFailCode", scenario.getIpnFailCode());
        view.put("ipnUrl", config.getIpnUrl());
        view.put("lifecycleStepMs", config.getLifecycleStepMs());
        view.put("reversalDelayMs", config.getReversalDelayMs());
        return view;
    }

    private Map<String, Object> transferView(TransferRecord r) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("request_id", r.getRequestId());
        view.put("transaction_id", r.getTransactionId());
        view.put("bank_no", r.getBankNo());
        view.put("account_no", r.getAccountNo());
        view.put("account_name", r.getAccountName());
        view.put("amount_vnd", r.getAmountVnd());
        view.put("fee_vnd", r.getFeeVnd());
        view.put("status", r.getStatus());
        view.put("held", r.isHeld());
        view.put("reversed", r.isReversed());
        view.put("planned_outcome", r.getPlannedOutcome().name());
        view.put("created_at", r.getCreatedAt());
        return view;
    }
}
