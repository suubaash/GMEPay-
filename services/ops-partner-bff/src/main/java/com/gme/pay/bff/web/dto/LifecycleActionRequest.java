package com.gme.pay.bff.web.dto;

/**
 * BFF wire shape for lifecycle-transition POST bodies. The Admin UI sends this
 * unified envelope for all four actions; the BFF routes to the action-specific
 * client method rather than duplicating four near-identical DTO classes.
 *
 * <ul>
 *   <li>{@code action} — {@code ACTIVATE | SUSPEND | REACTIVATE | TERMINATE}.
 *       Used as a routing hint from the Admin UI when the same form issues
 *       different verb calls; the URL path already carries the action so this
 *       field is optional.</li>
 *   <li>{@code reason} — required by {@code SUSPEND} (SuspensionReason name)
 *       and {@code TERMINATE} (free text). Ignored by ACTIVATE + REACTIVATE.</li>
 *   <li>{@code notes} — optional operator free text (SUSPEND only). ≤500
 *       chars, server-enforced.</li>
 * </ul>
 */
public record LifecycleActionRequest(String action, String reason, String notes) {
}
