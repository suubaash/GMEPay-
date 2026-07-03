package com.gme.pay.bff.web.dto;

import java.time.Instant;

/**
 * BFF wire shape for one platform setting, passed through unchanged from
 * config-registry's {@code /v1/admin/settings} store to the admin UI. Mirrors
 * config-registry's PlatformSettingView (key, value, valueType, description,
 * updatedAt, updatedBy).
 */
public record PlatformSettingView(
        String key,
        String value,
        String valueType,
        String description,
        Instant updatedAt,
        String updatedBy) {
}
