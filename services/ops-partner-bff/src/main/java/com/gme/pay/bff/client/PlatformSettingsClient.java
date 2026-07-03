package com.gme.pay.bff.client;

import com.gme.pay.bff.web.dto.PlatformSettingView;
import java.util.List;

/**
 * BFF view of config-registry's generic platform-settings store (owner Goal #3 — operators
 * change platform tunables from the admin UI without a redeploy). The admin UI only ever
 * talks to this BFF ({@code /api/*}); config-registry is not directly reachable, so this
 * client is the pass-through.
 *
 * <p>Endpoint mapping (config-registry PlatformSettingController):
 * <ul>
 *   <li>{@code GET /v1/admin/settings}        -> {@link #list()}</li>
 *   <li>{@code GET /v1/admin/settings/{key}}  -> {@link #get(String)}</li>
 *   <li>{@code PUT /v1/admin/settings/{key}}  -> {@link #update(String, String, String)}</li>
 * </ul>
 *
 * <p>Production {@code RestPlatformSettingsClient} activates on
 * {@code gmepay.config-registry.client=rest}; otherwise the in-memory
 * {@link com.gme.pay.bff.client.stub.StubPlatformSettingsClient} wins so the BFF boots
 * standalone for tests / local dev.
 */
public interface PlatformSettingsClient {

    /** All settings, key-sorted. */
    List<PlatformSettingView> list();

    /** One setting; propagates a config-registry 404 as a {@code ResponseStatusException}. */
    PlatformSettingView get(String key);

    /**
     * Upsert a setting value; propagates config-registry's 400 (NUMBER validation) / 404 as a
     * {@code ResponseStatusException}. Returns the updated row.
     *
     * @param key       setting key
     * @param value     new value
     * @param updatedBy operator id recorded on the audit row (may be null)
     */
    PlatformSettingView update(String key, String value, String updatedBy);
}
