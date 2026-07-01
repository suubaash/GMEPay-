package com.gme.pay.notify.api;

import com.gme.pay.notify.replay.WebhookReplayService;
import com.gme.pay.notify.replay.WebhookReplayService.ReplayResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator-facing manual webhook replay surface (Ops wave).
 *
 * <pre>
 * POST /v1/webhooks/deliveries/{id}/replay      — replay by delivery-log id
 * POST /v1/webhooks/deliveries/replay?reference= — replay by logical webhook id
 * </pre>
 *
 * <p>Re-enqueues a parked ({@code DLQ}/{@code FAILED}) delivery back to
 * {@code PENDING}; the existing dispatcher drain re-sends it. Idempotent-safe: a
 * still-live ({@code PENDING}/{@code DELIVERED}) row is a 200 no-op, not a duplicate
 * dispatch. The requesting operator ({@code X-Operator} header or {@code operator}
 * body field) and an optional reason are recorded to the replay audit ledger.
 */
@RestController
@RequestMapping("/v1/webhooks/deliveries")
public class WebhookReplayController {

    private final WebhookReplayService replayService;

    public WebhookReplayController(WebhookReplayService replayService) {
        this.replayService = replayService;
    }

    /** Body for the replay request; both fields optional (operator may ride the header). */
    public record ReplayRequest(String operator, String reason) {
    }

    /** Response echoing the resolved delivery id and the replay outcome. */
    public record ReplayResponse(Long deliveryId, String outcome, boolean reenqueued) {
    }

    @PostMapping("/{id}/replay")
    public ResponseEntity<ReplayResponse> replayById(
            @PathVariable("id") Long id,
            @RequestHeader(name = "X-Operator", required = false) String operatorHeader,
            @RequestBody(required = false) ReplayRequest body) {
        ReplayResult result = replayService.replayById(id, operator(operatorHeader, body), reason(body));
        return toResponse(result);
    }

    @PostMapping("/replay")
    public ResponseEntity<ReplayResponse> replayByReference(
            @RequestParam("reference") String reference,
            @RequestHeader(name = "X-Operator", required = false) String operatorHeader,
            @RequestBody(required = false) ReplayRequest body) {
        ReplayResult result = replayService.replayByReference(reference, operator(operatorHeader, body), reason(body));
        return toResponse(result);
    }

    private static String operator(String header, ReplayRequest body) {
        if (header != null && !header.isBlank()) {
            return header;
        }
        return body == null ? null : body.operator();
    }

    private static String reason(ReplayRequest body) {
        return body == null ? null : body.reason();
    }

    private static ResponseEntity<ReplayResponse> toResponse(ReplayResult result) {
        ReplayResponse dto = new ReplayResponse(
                result.deliveryId(), result.outcome().name(), result.reenqueued());
        HttpStatus status = switch (result.outcome()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.OK; // REENQUEUED + idempotent no-ops
        };
        return ResponseEntity.status(status).body(dto);
    }
}
