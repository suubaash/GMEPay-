package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.web.dto.Page;
import com.gme.pay.contracts.ChangeRequestView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Admin UI endpoints for the 4-eyes change-request approval queue per ADR-008.
 * Pure pass-through to config-registry via {@link ConfigRegistryClient}; the BFF
 * adds no orchestration logic here — it is purely an edge-layer translation so
 * the Admin UI talks a single host regardless of which backend holds the data.
 *
 * <h2>URL contract</h2>
 * <ul>
 *   <li>{@code GET  /v1/admin/change-requests?state=PROPOSED&page=0&size=20}</li>
 *   <li>{@code GET  /v1/admin/change-requests/{id}}</li>
 *   <li>{@code POST /v1/admin/change-requests/{id}/approve} body: {@code {"approvedBy":"bob"}}</li>
 *   <li>{@code POST /v1/admin/change-requests/{id}/reject}
 *       body: {@code {"rejectedBy":"bob","reason":"..."}}</li>
 * </ul>
 *
 * <h2>Self-approval 409</h2>
 * <p>Config-registry's controller handles the DB CHECK and surfaces a 409.
 * The BFF re-propagates the upstream 4xx unchanged so the Admin UI sees the
 * same status code and message that config-registry produced.
 */
@RestController
@RequestMapping("/v1/admin/change-requests")
public class ChangeRequestAdminController {

    private final ConfigRegistryClient configRegistry;

    public ChangeRequestAdminController(ConfigRegistryClient configRegistry) {
        this.configRegistry = configRegistry;
    }

    /**
     * Paginated change-request list, optionally filtered by state.
     * Delegates to {@link ConfigRegistryClient#listChangeRequests}.
     */
    @GetMapping
    public Page<ChangeRequestView> list(
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        ConfigRegistryClient.ChangeRequestPage upstream =
                configRegistry.listChangeRequests(state, page, size);
        return new Page<>(upstream.content(), upstream.page(), upstream.size(),
                upstream.total());
    }

    /**
     * Single change request by id. Returns 404 if config-registry says unknown.
     */
    @GetMapping("/{id}")
    public ChangeRequestView get(@PathVariable Long id) {
        ChangeRequestView view = configRegistry.getChangeRequest(id);
        if (view == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "change-request " + id + " not found");
        }
        return view;
    }

    /**
     * Approve a PROPOSED change request and apply it. Returns 200 with state=APPLIED.
     * Returns 409 for self-approval (propagated from config-registry).
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<ChangeRequestView> approve(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {

        String approvedBy = body == null ? null : body.get("approvedBy");
        if (approvedBy == null || approvedBy.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "approvedBy is required");
        }
        // approveChangeRequest propagates upstream 4xx (including 409) via
        // ResponseStatusException — the BFF does not swallow them.
        ChangeRequestView result = configRegistry.approveChangeRequest(id, approvedBy);
        return ResponseEntity.ok(result);
    }

    /**
     * Reject a change request with a mandatory reason.
     */
    @PostMapping("/{id}/reject")
    public ChangeRequestView reject(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {

        String rejectedBy = body == null ? null : body.get("rejectedBy");
        String reason    = body == null ? null : body.get("reason");
        if (rejectedBy == null || rejectedBy.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "rejectedBy is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "reason is required");
        }
        return configRegistry.rejectChangeRequest(id, rejectedBy, reason);
    }
}
