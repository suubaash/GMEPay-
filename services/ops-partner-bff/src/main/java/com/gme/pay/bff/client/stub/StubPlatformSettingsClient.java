package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.PlatformSettingsClient;
import com.gme.pay.bff.web.dto.PlatformSettingView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase-1 in-memory stub of {@link PlatformSettingsClient}. Lets the BFF boot and be
 * exercised without config-registry running. Seeds the same real tunables config-registry
 * V039 seeds so the Admin UI settings editor shows consistent content in local dev.
 *
 * <p><b>OPT-IN ONLY.</b> This class used to be a bare {@code @Component} with no condition at all:
 * it was constructed in every environment and merely <em>displaced</em> at injection time by
 * {@link com.gme.pay.bff.client.rest.RestPlatformSettingsClient}'s {@code @Primary}. That is one
 * removed annotation away from being live, on a bean that serves money-affecting settings, so it is
 * now gated on {@code gmepay.config-registry.client=stub} and is not created otherwise.
 *
 * <p><b>Per-JVM state — INCORRECT above one replica.</b> {@link #update} writes into this object's
 * own map. At N&gt;1 an operator edit to {@code fx.quote.ttl.seconds},
 * {@code prefunding.alert.tier{1,2,3}.pct} or {@code wallet.fee.krw} lands on whichever replica
 * served the request: reads become non-deterministic and writes are silently lost. Not fixed by
 * sharing the map — a settings store that persists is exactly what config-registry already is, and
 * selecting the real client is the whole answer.
 */
@Component
@ConditionalOnProperty(name = "gmepay.config-registry.client", havingValue = "stub")
public class StubPlatformSettingsClient implements PlatformSettingsClient {

    private final Map<String, PlatformSettingView> store = new LinkedHashMap<>();

    public StubPlatformSettingsClient() {
        Instant now = Instant.now();
        seed("fx.quote.ttl.seconds", "900", "FX quote lock validity (seconds).", now);
        seed("prefunding.alert.tier1.pct", "95", "Float low-balance alert: first warning tier (%)", now);
        seed("prefunding.alert.tier2.pct", "85", "Float low-balance alert: second warning tier (%)", now);
        seed("prefunding.alert.tier3.pct", "70", "Float low-balance alert: third warning tier (%)", now);
        seed("wallet.fee.krw", "500",
                "Domestic wallet transaction fee (KRW). NOTE: consumed by payment-executor (pending wiring).", now);
    }

    private void seed(String key, String value, String description, Instant now) {
        store.put(key, new PlatformSettingView(key, value, "NUMBER", description, now, "system"));
    }

    @Override
    public List<PlatformSettingView> list() {
        List<PlatformSettingView> all = new ArrayList<>(store.values());
        all.sort((a, b) -> a.key().compareTo(b.key()));
        return all;
    }

    @Override
    public PlatformSettingView get(String key) {
        PlatformSettingView v = store.get(key);
        if (v == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown setting: " + key);
        }
        return v;
    }

    @Override
    public PlatformSettingView update(String key, String value, String updatedBy) {
        PlatformSettingView existing = store.get(key);
        String type = existing == null ? "STRING" : existing.valueType();
        if ("NUMBER".equalsIgnoreCase(type)) {
            try {
                new java.math.BigDecimal(value == null ? "" : value.trim());
            } catch (NumberFormatException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "value must be a number: " + value);
            }
        }
        String description = existing == null ? null : existing.description();
        PlatformSettingView updated = new PlatformSettingView(
                key, value, type, description, Instant.now(),
                updatedBy == null || updatedBy.isBlank() ? "admin" : updatedBy);
        store.put(key, updated);
        return updated;
    }
}
