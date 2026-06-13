package com.gme.pay.contracts;

/**
 * Write shape for a partner address — the JSON body the UI / BFF sends when
 * setting or updating either {@code registered_address} or {@code operating_address}
 * on a partner draft. Fields mirror {@link AddressView}; see that record for the
 * field-level contract.
 *
 * <p>Slice 1 accepts the same nullable shape on both write and read so the
 * wizard can save partial progress. Strictness (which fields are mandatory for
 * activation) is enforced at the activation gate, not here.
 */
public record AddressCommand(
        String street1,
        String street2,
        String city,
        String state,
        String postcode,
        String country) {

    /** Adapt this command to the read view (identical fields). */
    public AddressView toView() {
        return new AddressView(street1, street2, city, state, postcode, country);
    }
}
