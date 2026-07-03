package com.gme.pay.registry.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One row of the generic platform-settings store (V039 {@code platform_settings}).
 * A setting is a {@code key} → {@code value} pair with a coarse {@link #valueType}
 * (used only for PUT input validation, e.g. {@code NUMBER} must parse) plus a human
 * {@code description} and audit metadata ({@code updatedAt}/{@code updatedBy}).
 *
 * <p>Keys are dotted namespaces (e.g. {@code prefunding.alert.tier1.pct}). The store
 * is intentionally schema-light: consumers read the string value and coerce it
 * themselves, always with a code-side fallback default when the key is absent or the
 * store is unreachable.
 */
@Entity
@Table(name = "platform_settings")
public class PlatformSettingEntity {

    // `key` is quoted (Hibernate backticks → dialect-specific quoting) because it
    // is a reserved word in both PostgreSQL and H2; the DDL quotes it identically.
    @Id
    @Column(name = "`key`", nullable = false, updatable = false, length = 96)
    private String key;

    // `value` is quoted for the same reserved-word reason as `key`.
    @Column(name = "`value`", nullable = false, length = 512)
    private String value;

    @Column(name = "value_type", nullable = false, length = 16)
    private String valueType;

    @Column(name = "description", length = 256)
    private String description;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 96)
    private String updatedBy;

    public PlatformSettingEntity() {
        // JPA
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getValueType() {
        return valueType;
    }

    public void setValueType(String valueType) {
        this.valueType = valueType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
