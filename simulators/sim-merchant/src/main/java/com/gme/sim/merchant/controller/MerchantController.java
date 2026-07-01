package com.gme.sim.merchant.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gme.sim.merchant.dto.ChargeRequest;
import com.gme.sim.merchant.dto.ChargeResponse;
import com.gme.sim.merchant.dto.RegisterShopRequest;
import com.gme.sim.merchant.model.ShopRecord;
import com.gme.sim.merchant.model.ShopStore;
import com.gme.sim.merchant.service.MerchantQrDataClient;
import com.gme.sim.merchant.service.SchemeClient;
import com.gme.sim.merchant.service.SchemeUnavailableException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merchant POS simulator backend.
 *
 * All scheme interactions are proxied through SchemeClient; this controller
 * owns only in-memory shop registration and 503 error handling.
 */
@RestController
@RequestMapping("/v1/merchant")
public class MerchantController {

    private final SchemeClient        schemeClient;
    private final ShopStore           shopStore;
    private final MerchantQrDataClient merchantQrDataClient;

    public MerchantController(SchemeClient schemeClient, ShopStore shopStore,
                              MerchantQrDataClient merchantQrDataClient) {
        this.schemeClient = schemeClient;
        this.shopStore    = shopStore;
        this.merchantQrDataClient = merchantQrDataClient;
    }

    // -------------------------------------------------------------------------
    // POST /v1/merchant/shops — register a new shop
    // -------------------------------------------------------------------------

    @PostMapping("/shops")
    public ResponseEntity<?> registerShop(@Valid @RequestBody RegisterShopRequest req) {
        try {
            ShopRecord shop = schemeClient.registerMerchant(req);
            shopStore.save(shop);
            return ResponseEntity.status(HttpStatus.CREATED).body(shopToMap(shop));
        } catch (SchemeUnavailableException e) {
            return serviceUnavailable(e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // GET /v1/merchant/shops — list all registered shops
    // -------------------------------------------------------------------------

    @GetMapping("/shops")
    public ResponseEntity<List<Map<String, Object>>> listShops() {
        List<Map<String, Object>> shops = shopStore.findAll().stream()
                .map(this::shopToMap)
                .toList();
        return ResponseEntity.ok(shops);
    }

    // -------------------------------------------------------------------------
    // GET /v1/merchant/shops/{merchantId}/store-qr — proxy to scheme
    // -------------------------------------------------------------------------

    @GetMapping("/shops/{merchantId}/store-qr")
    public ResponseEntity<?> getStoreQr(@PathVariable String merchantId) {
        // Validate locally first
        ShopRecord shop = shopStore.find(merchantId).orElse(null);
        if (shop == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("code", "SHOP_NOT_FOUND",
                                 "message", "Shop " + merchantId + " not registered in this terminal"));
        }
        try {
            JsonNode resp = schemeClient.getStoreQr(merchantId);
            if (resp == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            // Write-through: mirror this QR into merchant-qr-data so the wallet's scan resolves.
            merchantQrDataClient.mirror(resp.path("qrPayload").asText(null), shop);
            return ResponseEntity.ok(resp);
        } catch (SchemeUnavailableException e) {
            return serviceUnavailable(e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // POST /v1/merchant/shops/{merchantId}/charge — POS amount entry
    // -------------------------------------------------------------------------

    @PostMapping("/shops/{merchantId}/charge")
    public ResponseEntity<?> charge(
            @PathVariable String merchantId,
            @Valid @RequestBody ChargeRequest req) {

        ShopRecord shop = shopStore.find(merchantId).orElse(null);
        if (shop == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("code", "SHOP_NOT_FOUND",
                                 "message", "Shop " + merchantId + " not registered in this terminal"));
        }
        try {
            ChargeResponse charge = schemeClient.mintDynamicQr(
                    merchantId, req.amount(),
                    req.currency() != null ? req.currency() : "KRW");
            // Write-through: mirror this dynamic QR into merchant-qr-data so the wallet's scan resolves.
            merchantQrDataClient.mirror(charge.qrPayload(), shop);
            return ResponseEntity.ok(charge);
        } catch (SchemeUnavailableException e) {
            return serviceUnavailable(e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // GET /v1/merchant/shops/{merchantId}/payments?since={seq} — feed proxy
    // -------------------------------------------------------------------------

    @GetMapping("/shops/{merchantId}/payments")
    public ResponseEntity<?> getPayments(
            @PathVariable String merchantId,
            @RequestParam(name = "since", defaultValue = "0") long since) {
        try {
            JsonNode resp = schemeClient.getPaymentFeed(merchantId, since);
            return ResponseEntity.ok(resp);
        } catch (SchemeUnavailableException e) {
            return serviceUnavailable(e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Map<String, Object> shopToMap(ShopRecord shop) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("merchantId",          shop.merchantId());
        m.put("name",                shop.name());
        m.put("city",                shop.city());
        m.put("mcc",                 shop.mcc());
        if (shop.businessRegNo()       != null) m.put("businessRegNo",       shop.businessRegNo());
        if (shop.subMerchantId()       != null) m.put("subMerchantId",       shop.subMerchantId());
        if (shop.kftcInstitutionCode() != null) m.put("kftcInstitutionCode", shop.kftcInstitutionCode());
        if (shop.merchantType()        != null) m.put("merchantType",        shop.merchantType());
        if (shop.feeRate()             != null) m.put("feeRate",             shop.feeRate());
        return m;
    }

    private ResponseEntity<Map<String, Object>> serviceUnavailable(String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code",    "SCHEME_UNAVAILABLE");
        body.put("message", "sim-scheme is currently not reachable. Please start it on :9102.");
        body.put("detail",  detail);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
