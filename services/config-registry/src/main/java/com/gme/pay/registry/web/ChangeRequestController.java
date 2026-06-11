package com.gme.pay.registry.web;

import com.gme.pay.changerequest.ChangeRequest;
import com.gme.pay.changerequest.ChangeRequestState;
import com.gme.pay.contracts.ChangeRequestView;
import com.gme.pay.registry.changerequest.ChangeRequestRepository;
import com.gme.pay.registry.changerequest.ChangeRequestService;
import jakarta.persistence.PersistenceException;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

/**
 * REST surface for the 4-eyes change-request approval queue per ADR-008.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET  /v1/change-requests?state=PROPOSED&page=0&size=20}
 *       — paginated list, filtered by state. Omitting {@code state} returns
 *       all rows newest-first. When {@code state} is present the result is
 *       sorted oldest-first (FIFO review order).</li>
 *   <li>{@code GET  /v1/change-requests/{id}} — single row by id (404 if unknown).</li>
 *   <li>{@code POST /v1/change-requests/{id}/approve} — checker approves a
 *       PROPOSED row. Body: {@code {"approvedBy": "bob"}}. After a successful
 *       approve the service also calls {@code apply} in the same request so the
 *       aggregate is mutated atomically; the returned view reflects state=APPLIED.
 *       Self-approval (same user as proposedBy) triggers the DB CHECK constraint
 *       and is surfaced as a 409 with the constraint message.</li>
 *   <li>{@code POST /v1/change-requests/{id}/reject} — reject from any non-terminal
 *       state. Body: {@code {"rejectedBy": "bob", "reason": "..."}}. Returns 400
 *       when {@code reason} is blank; 409 when already in a terminal state.</li>
 * </ul>
 *
 * <h2>Self-approval 409</h2>
 *
 * <p>Two layers enforce the 4-eyes rule (see {@link ChangeRequestService} javadoc):
 * <ol>
 *   <li>The FSM (procedural): no DRAFT→APPLIED edge.</li>
 *   <li>The DB CHECK ({@code ck_change_request_four_eyes}): flush inside
 *       {@link ChangeRequestService#approve} surfaces a
 *       {@link DataIntegrityViolationException} immediately. This controller
 *       catches that exception and re-throws as a 409 with the underlying
 *       message so the Admin UI can render "you cannot approve your own
 *       change request" rather than a raw 500.</li>
 * </ol>
 */
@RestController
@RequestMapping("/v1/change-requests")
public class ChangeRequestController {

    private final ChangeRequestService service;
    private final ChangeRequestRepository repository;

    public ChangeRequestController(ChangeRequestService service,
                                   ChangeRequestRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    /**
     * Paginated list of change requests, optionally filtered by state.
     *
     * <p>Query params:
     * <ul>
     *   <li>{@code state} (optional) — one of DRAFT, PROPOSED, APPROVED, APPLIED,
     *       REJECTED. When present results are sorted oldest-first (FIFO queue).
     *       Omitting state returns all rows, sorted newest-first.</li>
     *   <li>{@code page} (default 0) — zero-based page index.</li>
     *   <li>{@code size} (default 20) — page size, capped at 200.</li>
     * </ul>
     */
    @GetMapping
    public ChangeRequestPageView list(
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 200);

        if (state != null && !state.isBlank()) {
            ChangeRequestState stateEnum = parseState(state);
            // FIFO review order: oldest PROPOSED change first
            PageRequest pageable = PageRequest.of(safePage, safeSize,
                    Sort.by(Sort.Direction.ASC, "proposedAt"));
            Page<ChangeRequestView> p = repository
                    .findByState(stateEnum, pageable)
                    .map(e -> toView(e.toDomain()));
            return new ChangeRequestPageView(p.getContent(), p.getNumber(),
                    p.getSize(), p.getTotalElements());
        } else {
            PageRequest pageable = PageRequest.of(safePage, safeSize,
                    Sort.by(Sort.Direction.DESC, "proposedAt"));
            Page<ChangeRequestView> p = repository
                    .findAll(pageable)
                    .map(e -> toView(e.toDomain()));
            return new ChangeRequestPageView(p.getContent(), p.getNumber(),
                    p.getSize(), p.getTotalElements());
        }
    }

    /** Single change request by id. Returns 404 if not found. */
    @GetMapping("/{id}")
    public ChangeRequestView get(@PathVariable Long id) {
        return toView(service.get(id));
    }

    /**
     * Approve a PROPOSED change request, then immediately apply it to the
     * underlying aggregate. Returns the view in state=APPLIED on success.
     *
     * <p>Self-approval (approvedBy == proposedBy) is caught by the DB CHECK
     * constraint and re-thrown as 409 so the Admin UI can surface a clear message.
     *
     * @param body must contain {@code approvedBy} (the checker's user-id)
     * @throws ResponseStatusException 400 if {@code approvedBy} is missing,
     *         404 if the change request is unknown, 409 if the state machine
     *         refuses the transition or the self-approval constraint fires
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<ChangeRequestView> approve(
            @PathVariable Long id,
            @RequestBody ApproveRequest body) {

        if (body == null || body.approvedBy() == null || body.approvedBy().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "approvedBy is required");
        }

        try {
            // Step 1: PROPOSED → APPROVED (flushes; CHECK constraint fires here
            //         if proposed_by == approved_by)
            service.approve(id, body.approvedBy());
        } catch (DataIntegrityViolationException | PersistenceException dive) {
            // The DB CHECK ck_change_request_four_eyes fired — self-approval attempt.
            // Surface the underlying message so the UI can show a meaningful error.
            String msg = extractConstraintMessage(dive);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Self-approval not permitted: " + msg);
        }

        // Step 2: APPROVED → APPLIED (mutates the aggregate in the same request)
        ChangeRequest applied = service.apply(id);
        return ResponseEntity.ok(toView(applied));
    }

    /**
     * Reject a change request with a mandatory reason. Available from any
     * non-terminal state (DRAFT, PROPOSED, APPROVED).
     *
     * @param body must contain {@code rejectedBy} and a non-blank {@code reason}
     * @throws ResponseStatusException 400 if reason is blank, 404 if unknown,
     *         409 if already in a terminal state
     */
    @PostMapping("/{id}/reject")
    public ChangeRequestView reject(
            @PathVariable Long id,
            @RequestBody RejectRequest body) {

        if (body == null || body.rejectedBy() == null || body.rejectedBy().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "rejectedBy is required");
        }
        if (body.reason() == null || body.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "reason is required");
        }
        ChangeRequest rejected = service.reject(id, body.rejectedBy(), body.reason());
        return toView(rejected);
    }

    // -------- helpers --------------------------------------------------------

    /** Map a domain record to the canonical wire DTO. */
    static ChangeRequestView toView(ChangeRequest cr) {
        if (cr == null) return null;
        return new ChangeRequestView(
                cr.id(),
                cr.aggregateType(),
                cr.aggregateId(),
                cr.state() == null ? null : cr.state().name(),
                cr.proposedBy(),
                cr.proposedAt(),
                cr.approvedBy(),
                cr.approvedAt(),
                cr.rejectedReason(),
                cr.payloadJsonb(),
                cr.appliesToFieldSet() != null ? cr.appliesToFieldSet().clone() : null);
    }

    private static ChangeRequestState parseState(String raw) {
        try {
            return ChangeRequestState.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown state '" + raw + "'. Valid values: "
                            + List.of(ChangeRequestState.values()));
        }
    }

    /** Walk the exception chain to find the most informative message. */
    private static String extractConstraintMessage(Throwable t) {
        Throwable cursor = t;
        while (cursor != null) {
            String msg = cursor.getMessage();
            if (msg != null && !msg.isBlank()) {
                return msg;
            }
            cursor = cursor.getCause();
        }
        return "constraint violation";
    }

    // -------- request / response bodies -------------------------------------

    /** Body for {@link #approve}. */
    public record ApproveRequest(String approvedBy) {}

    /** Body for {@link #reject}. */
    public record RejectRequest(String rejectedBy, String reason) {}

    /**
     * Paginated list envelope. Mirrors the shape the BFF's
     * {@code ChangeRequestClient} deserialises so the wire format stays stable
     * even when Spring's {@link Page} implementation changes. Fields use the
     * same names as the BFF's existing {@link com.gme.pay.bff.web.dto.Page}
     * record so Admin UI bindings don't need to change.
     */
    public record ChangeRequestPageView(
            List<ChangeRequestView> content,
            int page,
            int size,
            long total) {}
}
