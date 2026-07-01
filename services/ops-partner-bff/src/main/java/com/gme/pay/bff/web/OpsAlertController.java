package com.gme.pay.bff.web;

import com.gme.pay.bff.alert.OpsAlertStore;
import com.gme.pay.bff.alert.OpsAlertView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only view of the recent ops alerts consumed from {@code gmepay.ops.alert} (alert loop #5).
 *
 * <p>{@code GET /v1/admin/ops/alerts?severity=&type=&limit=} — newest-first, optionally filtered by
 * severity ({@code INFO|WARN|CRITICAL}) and/or alertType ({@code STUCK_TXN}, {@code FLOAT_LOW}, …).
 * Backed by the in-memory {@link OpsAlertStore}; with no broker the list is simply empty.
 */
@RestController
@RequestMapping("/v1/admin/ops")
public class OpsAlertController {

    private static final int DEFAULT_LIMIT = 100;

    private final OpsAlertStore store;

    public OpsAlertController(OpsAlertStore store) {
        this.store = store;
    }

    @GetMapping("/alerts")
    public List<OpsAlertView> alerts(
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "100") int limit) {
        int safeLimit = limit <= 0 ? DEFAULT_LIMIT : limit;
        return store.recent(severity, type, safeLimit);
    }
}
