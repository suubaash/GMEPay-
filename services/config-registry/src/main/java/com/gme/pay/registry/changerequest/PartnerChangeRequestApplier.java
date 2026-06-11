package com.gme.pay.registry.changerequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.changerequest.ChangeRequest;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.partner.PartnerStore;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * {@link ChangeRequestApplier} for the {@code "partner"} aggregate (Slice 1 wires
 * this; later slices register more appliers for bank_account / rule / etc.).
 *
 * <p>Reads the change_request's {@code payload_jsonb} and applies it through
 * {@link PartnerStore#save} — the canonical mutation path for the partner row,
 * the only place where the cache is evicted and the DB write is issued. This
 * means every partner write that goes through the FSM goes through the same
 * code path as direct {@code PartnerStore.save} calls; the audit_log
 * publication (ADR-007) is uniform across both.
 *
 * <h2>Payload contract</h2>
 *
 * <p>Slice 1 carries the four-field aggregate (partnerCode / type /
 * settlementCurrency / settlementRoundingMode) plus a {@code partnerCode}
 * identity field. Later slices that add new fields will extend
 * {@code payload_jsonb} without forcing this applier to grow new methods — JSON
 * absence means "no change to that field".
 */
@Component
public class PartnerChangeRequestApplier implements ChangeRequestApplier {

    /** Aggregate type discriminator stored on the change_request row. */
    public static final String AGGREGATE_TYPE = "partner";

    private final PartnerStore partnerStore;
    private final ObjectMapper objectMapper;

    public PartnerChangeRequestApplier(PartnerStore partnerStore, ObjectMapper objectMapper) {
        this.partnerStore = partnerStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    @Override
    public void apply(ChangeRequest request) {
        if (request.payloadJsonb() == null || request.payloadJsonb().isBlank()) {
            throw new IllegalArgumentException(
                    "partner change_request " + request.id() + " has no payload");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(request.payloadJsonb());
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "partner change_request " + request.id() + " has malformed payload", e);
        }

        // partnerCode comes from the aggregate_id (the natural key on the row);
        // the payload carries the rest of the four-field shape. Fields absent
        // from the JSON keep their existing value because PartnerStore.save
        // merges into the existing row.
        String partnerCode = request.aggregateId();

        Partner existing = partnerStore.get(partnerCode);
        PartnerType type = node.hasNonNull("type")
                ? PartnerType.valueOf(node.get("type").asText())
                : existing.type();
        String settlementCurrency = node.hasNonNull("settlementCurrency")
                ? node.get("settlementCurrency").asText()
                : existing.settlementCurrency();
        RoundingMode roundingMode = node.hasNonNull("settlementRoundingMode")
                ? RoundingMode.valueOf(node.get("settlementRoundingMode").asText())
                : existing.settlementRoundingMode();

        partnerStore.save(new Partner(
                existing.partnerId(),
                partnerCode,
                type,
                settlementCurrency,
                roundingMode));
    }
}
