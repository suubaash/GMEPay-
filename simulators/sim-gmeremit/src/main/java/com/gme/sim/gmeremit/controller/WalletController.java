package com.gme.sim.gmeremit.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gme.sim.gmeremit.model.WalletStore;
import com.gme.sim.gmeremit.model.WalletTransaction;
import com.gme.sim.gmeremit.model.WalletUser;
import com.gme.sim.gmeremit.service.HubClient;
import com.gme.sim.gmeremit.service.WalletService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller exposing the GMERemit wallet simulator API at {@code /v1/gmeremit}.
 */
@RestController
@RequestMapping("/v1/gmeremit")
public class WalletController {

    private final WalletStore   store;
    private final WalletService walletService;
    private final HubClient     hub;

    public WalletController(WalletStore store, WalletService walletService, HubClient hub) {
        this.store         = store;
        this.walletService = walletService;
        this.hub           = hub;
    }

    // -------------------------------------------------------------------------
    // GET /users
    // -------------------------------------------------------------------------

    @GetMapping("/users")
    public List<UserView> listUsers() {
        return store.allUsers().stream()
                .map(u -> new UserView(u.getUserId(), u.getName(), u.getBalanceKrw().toPlainString()))
                .toList();
    }

    // -------------------------------------------------------------------------
    // POST /scan
    // -------------------------------------------------------------------------

    @PostMapping("/scan")
    public ResponseEntity<Object> scan(@RequestBody ScanRequest req) {
        if (req.qrPayload() == null || req.qrPayload().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "qrPayload is required");
        }

        HubClient.QrPreview preview = hub.decodeQr(req.qrPayload());

        if (preview == null) {
            // Hub is down — return a minimal echo so the wallet can still show something
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("merchantId",   "UNKNOWN");
            resp.put("merchantName", "Unknown Merchant");
            resp.put("mode",         "static");
            resp.put("amount",       null);
            resp.put("currency",     "KRW");
            resp.put("hubAvailable", false);
            return ResponseEntity.ok(resp);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("merchantId",   preview.merchantId());
        resp.put("merchantName", preview.merchantName());
        resp.put("mode",         preview.mode());
        resp.put("amount",       preview.amount());
        resp.put("currency",     preview.currency());
        resp.put("hubAvailable", true);
        return ResponseEntity.ok(resp);
    }

    // -------------------------------------------------------------------------
    // POST /users/{userId}/pay
    // -------------------------------------------------------------------------

    @PostMapping("/users/{userId}/pay")
    public ResponseEntity<Object> pay(@PathVariable String userId,
                                       @RequestBody PayRequest req) {
        if (req.qrPayload() == null || req.qrPayload().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "qrPayload is required");
        }
        if (req.amountKrw() == null || req.amountKrw().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "amountKrw is required");
        }

        WalletService.PayResult result = walletService.pay(userId, req.qrPayload(), req.amountKrw());

        if (result.approved()) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", "APPROVED");
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("merchantName",  result.merchantName());
            receipt.put("payAmountKrw",  result.payAmountKrw());
            receipt.put("feeKrw",        result.feeKrw());
            receipt.put("chargedKrw",    result.chargedKrw());
            receipt.put("schemeTxnRef",  result.schemeTxnRef());
            receipt.put("committedAt",   result.committedAt());
            resp.put("receipt",          receipt);
            resp.put("newBalanceKrw",    result.newBalanceKrw());
            return ResponseEntity.status(HttpStatus.CREATED).body(resp);
        }

        // DECLINED / INSUFFICIENT_FUNDS / HUB_UNAVAILABLE
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status",        "DECLINED");
        resp.put("declineReason", result.declineReason());
        if (result.merchantName() != null) resp.put("merchantName", result.merchantName());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(resp);
    }

    // -------------------------------------------------------------------------
    // GET /users/{userId}/transactions
    // -------------------------------------------------------------------------

    @GetMapping("/users/{userId}/transactions")
    public ResponseEntity<Object> transactions(@PathVariable String userId) {
        WalletUser user = store.findUser(userId).orElse(null);
        if (user == null) {
            return error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "No user with id " + userId);
        }
        List<WalletTransaction> txns = user.getTransactions();
        return ResponseEntity.ok(txns);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ResponseEntity<Object> error(HttpStatus status, String code, String message) {
        Map<String, String> body = Map.of("error", code, "message", message);
        return ResponseEntity.status(status).body(body);
    }

    // -------------------------------------------------------------------------
    // Inner DTOs
    // -------------------------------------------------------------------------

    public record UserView(
            @JsonProperty("userId")     String userId,
            @JsonProperty("name")       String name,
            @JsonProperty("balanceKrw") String balanceKrw
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScanRequest(@JsonProperty("qrPayload") String qrPayload) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PayRequest(
            @JsonProperty("qrPayload")  String qrPayload,
            @JsonProperty("amountKrw") String amountKrw
    ) {}
}
