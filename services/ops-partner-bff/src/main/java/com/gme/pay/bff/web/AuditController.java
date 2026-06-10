package com.gme.pay.bff.web;

import com.gme.pay.bff.client.AuditClient;
import com.gme.pay.bff.web.dto.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin UI Audit Log endpoint. Returns the operator audit trail paginated.
 *
 * <p>Phase-C4 endpoints:
 * <ul>
 *   <li>{@code GET /v1/admin/audit?page=&size=} — list audit entries newest-first
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin/audit")
public class AuditController {

    /** Default page size when the request omits {@code size}. */
    static final int DEFAULT_PAGE_SIZE = 20;

    /** Hard cap on page size to bound the in-memory slice. */
    static final int MAX_PAGE_SIZE = 200;

    private final AuditClient audit;

    public AuditController(AuditClient audit) {
        this.audit = audit;
    }

    @GetMapping
    public Page<AuditClient.AuditEntry> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size <= 0 ? DEFAULT_PAGE_SIZE : size), MAX_PAGE_SIZE);
        return audit.list(safePage, safeSize);
    }
}
