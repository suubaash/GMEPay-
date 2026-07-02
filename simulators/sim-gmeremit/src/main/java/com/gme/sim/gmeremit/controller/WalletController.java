package com.gme.sim.gmeremit.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gme.sim.gmeremit.model.WalletStore;
import com.gme.sim.gmeremit.model.WalletTransaction;
import com.gme.sim.gmeremit.model.WalletUser;
import com.gme.sim.gmeremit.service.FxRates;
import com.gme.sim.gmeremit.service.HubClient;
import com.gme.sim.gmeremit.service.NepalQrClient;
import com.gme.sim.gmeremit.service.QrNetwork;
import com.gme.sim.gmeremit.service.WalletService;

import java.math.BigDecimal;
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
    private final NepalQrClient nepalQr;
    private final FxRates       fx;

    public WalletController(WalletStore store, WalletService walletService, HubClient hub,
                            NepalQrClient nepalQr, FxRates fx) {
        this.store         = store;
        this.walletService = walletService;
        this.hub           = hub;
        this.nepalQr       = nepalQr;
        this.fx            = fx;
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

        QrNetwork network = QrNetwork.detect(req.qrPayload());
        if (network == QrNetwork.NEPAL) {
            return ResponseEntity.ok(scanNepal(req.qrPayload()));
        }
        return ResponseEntity.ok(scanDomestic(req.qrPayload()));
    }

    /** Domestic ZeroPay decode via the scheme sim (KRW) — unchanged behaviour. */
    private Map<String, Object> scanDomestic(String qrPayload) {
        HubClient.QrPreview preview = hub.decodeQr(qrPayload);
        Map<String, Object> resp = new LinkedHashMap<>();
        if (preview == null) {
            // Scheme sim is down — return a minimal echo so the wallet can still show something.
            resp.put("merchantId",   "UNKNOWN");
            resp.put("merchantName", "Unknown Merchant");
            resp.put("mode",         "static");
            resp.put("amount",       null);
            resp.put("currency",     "KRW");
            resp.put("network",      "ZEROPAY");
            resp.put("hubAvailable", false);
            return resp;
        }
        resp.put("merchantId",   preview.merchantId());
        resp.put("merchantName", preview.merchantName());
        resp.put("mode",         preview.mode());
        resp.put("amount",       preview.amount());
        resp.put("currency",     preview.currency());
        resp.put("network",      "ZEROPAY");
        resp.put("hubAvailable", true);
        return resp;
    }

    /** Cross-border Nepal decode via sim-nepal-qr — resolves the REAL merchant + NPR amount. */
    private Map<String, Object> scanNepal(String qrPayload) {
        NepalQrClient.NepalParse parse = nepalQr.decode(qrPayload);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("network",  "FONEPAY");
        resp.put("currency", "NPR");
        if (parse == null) {
            // Nepal sim down — do NOT masquerade as a known merchant.
            resp.put("merchantId",   "UNKNOWN");
            resp.put("merchantName", "Unknown Nepal Merchant");
            resp.put("merchantCity", null);
            resp.put("mode",         "static");
            resp.put("amount",       null);
            resp.put("krwPerNpr",    fx.effectiveKrwPerNpr().toPlainString());
            resp.put("hubAvailable", false);
            return resp;
        }
        String mode = "dynamic".equalsIgnoreCase(parse.initMethod()) ? "dynamic" : "static";
        resp.put("merchantId",   parse.merchantName());   // Nepal parse has no separate id; use name
        resp.put("merchantName", parse.merchantName());
        resp.put("merchantCity", parse.merchantCity());
        resp.put("mode",         mode);
        resp.put("amount",       parse.trxAmount());       // NPR rupees, null for static
        resp.put("krwPerNpr",    fx.effectiveKrwPerNpr().toPlainString());
        // KRW-debit estimate for a dynamic (fixed-amount) NPR QR.
        if (parse.trxAmount() != null) {
            resp.put("estKrwDebit", fx.nprToKrw(new BigDecimal(parse.trxAmount())).toPlainString());
        }
        resp.put("hubAvailable", true);
        return resp;
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
        // Amount is in the merchant currency (KRW domestic / NPR Nepal). Accept the new `amount`
        // field, falling back to the legacy `amountKrw` for backward compatibility.
        String amount = req.amount() != null && !req.amount().isBlank() ? req.amount() : req.amountKrw();
        if (amount == null || amount.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "amount is required");
        }

        WalletService.PayResult result = walletService.pay(userId, req.qrPayload(), amount);

        if (result.approved()) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", "APPROVED");
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("merchantName",  result.merchantName());
            receipt.put("currency",      result.currency());
            receipt.put("payAmount",     result.payAmount());     // merchant-currency amount
            receipt.put("payAmountKrw",  result.payAmountKrw());  // KRW value of the payment leg
            receipt.put("feeKrw",        result.feeKrw());
            receipt.put("chargedKrw",    result.chargedKrw());    // KRW debited from wallet
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
            @JsonProperty("qrPayload") String qrPayload,
            @JsonProperty("amount")    String amount,     // amount in merchant currency (KRW/NPR)
            @JsonProperty("amountKrw") String amountKrw    // legacy alias for domestic KRW
    ) {}
}
