package com.gme.pay.contracts;

/**
 * Canonical write payloads for the Slice 8 partner-lifecycle endpoints
 * ({@code POST /v1/admin/partners/{partnerCode}/lifecycle/*}). One nested
 * record per {@link PartnerLifecycleAction}, sealed so the controller can
 * switch exhaustively.
 *
 * <p>All four actions are 4-eyes gated (ADR-008): the SAME endpoint is called
 * twice — the maker's call proposes a {@code change_request}, the checker's
 * call (a different operator, enforced by the V005 CHECK) approves and applies
 * it. The body is identical on both calls; the server matches the pending
 * change_request by partner + action.
 */
public sealed interface LifecycleCommand
        permits LifecycleCommand.Activate, LifecycleCommand.Suspend,
                LifecycleCommand.Reactivate, LifecycleCommand.Terminate {

    /**
     * Propose/approve {@code UAT → LIVE}. Carries no fields — the activation
     * gate derives everything from the partner aggregate. Kept as a record (not
     * a bare marker) so an optional go-live note can be added without changing
     * the wire shape's nature.
     */
    record Activate() implements LifecycleCommand {
    }

    /**
     * Propose/approve {@code LIVE → SUSPENDED}.
     *
     * @param reason required; must be a {@link SuspensionReason} name — the
     *               V025 CHECK roster.
     * @param notes  optional operator free text, ≤500 chars.
     */
    record Suspend(String reason, String notes) implements LifecycleCommand {
    }

    /** Propose/approve {@code SUSPENDED → LIVE}; clears the suspension fields. */
    record Reactivate() implements LifecycleCommand {
    }

    /**
     * Propose/approve {@code LIVE|SUSPENDED → TERMINATED} (non-reversible).
     *
     * @param reason required free text, ≤500 chars.
     */
    record Terminate(String reason) implements LifecycleCommand {
    }
}
