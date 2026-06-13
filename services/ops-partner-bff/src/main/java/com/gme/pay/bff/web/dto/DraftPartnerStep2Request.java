package com.gme.pay.bff.web.dto;

import com.gme.pay.contracts.ContactCommand;
import com.gme.pay.contracts.PartnerCommand;

import java.util.List;

/**
 * BFF wire shape for {@code PATCH /v1/admin/partners/draft/{partnerCode}/step-2}
 * (Slice 2 — Contacts). The URL identifies the partner being mutated; the body
 * carries the FULL desired contact set (bulk-replace semantics — an empty list
 * clears all contacts, see {@link PartnerCommand.UpdateStep2}).
 *
 * <p>Mirrors {@link PartnerCommand.UpdateStep2}; adapter {@link #toUpdateStep2()}
 * converts to the canonical write payload before the BFF calls config-registry —
 * the same seam discipline as {@link DraftPartnerStep1Request}. Elements bind
 * directly to the canonical {@link ContactCommand} (role, name, email,
 * phoneE164, authorizedSignatory, notes), just as step-1 binds nested
 * {@code AddressCommand}s.
 */
public record DraftPartnerStep2Request(List<ContactCommand> contacts) {

    /** Adapt to the canonical write surface {@code lib-api-contracts} exposes. */
    public PartnerCommand.UpdateStep2 toUpdateStep2() {
        return new PartnerCommand.UpdateStep2(contacts);
    }
}
