package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.PlatformSettingsClient;
import com.gme.pay.bff.web.dto.PlatformSettingView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase-1 in-memory stub of {@link PlatformSettingsClient}. Lets the BFF boot and be
 * exercised without config-registry running. Seeds the same real tunables config-registry
 * V039 seeds so the Admin UI settings editor shows consistent content in local dev. The
 * production {@link com.gme.pay.bff.client.rest.RestPlatformSettingsClient} (marked
 * {@code @Primary}, {@code gmepay.config-registry.client=rest}) takes over without removing
 * this bean.
 */
@Component
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
