package com.gme.pay.registry.settings;

import java.time.Instant;

/**
 * Wire/read shape for a single platform setting, returned by the
 * {@code /v1/admin/settings} endpoints and consumed by the admin UI (via the BFF).
 */
public record PlatformSettingView(
        String key,
        String value,
        String valueType,
        String description,
        Instant updatedAt,
        String updatedBy) {

    static PlatformSettingView from(PlatformSettingEntity e) {
        return new PlatformSettingView(
                e.getKey(), e.getValue(), e.getValueType(), e.getDescription(),
                e.getUpdatedAt(), e.getUpdatedBy());
    }
}
