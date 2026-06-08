package com.gme.pay.settlement.model;

/**
 * Lightweight partner descriptor owned by this service's domain.
 * Full partner data lives in config-registry; this record holds only the fields
 * required by settlement classification.
 */
public record Partner(Long id, String name, PartnerType type) {

    public Partner {
        if (id == null) throw new IllegalArgumentException("Partner id required");
        if (type == null) throw new IllegalArgumentException("Partner type required");
    }
}
