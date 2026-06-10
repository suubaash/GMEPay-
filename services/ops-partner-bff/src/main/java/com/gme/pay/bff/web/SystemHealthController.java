package com.gme.pay.bff.web;

import com.gme.pay.bff.client.SystemHealthClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin UI System Health endpoint. Returns a snapshot of all 17 backend
 * services' health for the operations dashboard.
 *
 * <p>Phase-C4 endpoints:
 * <ul>
 *   <li>{@code GET /v1/admin/system/health} — full health snapshot
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin/system")
public class SystemHealthController {

    private final SystemHealthClient health;

    public SystemHealthController(SystemHealthClient health) {
        this.health = health;
    }

    @GetMapping("/health")
    public SystemHealthClient.SystemHealth health() {
        return health.check();
    }
}
