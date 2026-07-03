package com.gme.pay.registry.settings;

import com.gme.pay.registry.audit.AuditLogService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The generic platform-settings store (owner Goal #3). Owns the {@code platform_settings}
 * table and projects it onto {@link PlatformSettingView}. A setting is a namespaced
 * {@code key} → string {@code value} pair with a coarse {@link #TYPE_NUMBER}/STRING type
 * used only for PUT input validation.
 *
 * <h2>Audit</h2>
 *
 * <p>Every mutating {@link #upsert} writes one hash-chained audit row (ADR-007) via the
 * shared {@link AuditLogService}, chained under aggregate {@code platform-setting} / id =
 * the setting key, so each setting has its own self-contained change history — the same
 * mechanism the ops kill-switch uses.
 *
 * <h2>Upsert semantics</h2>
 *
 * <p>{@link #upsert} on a known key overwrites the value (validating against the row's
 * existing {@code value_type}); on an unknown key it creates a STRING-typed row. The
 * seeded platform tunables (V039) are all NUMBER, so a NUMBER value must parse or the PUT
 * is rejected 400 before any write or audit happens.
 */
@Service
public class PlatformSettingService {

    static final String AGGREGATE = "platform-setting";
    static final String DEFAULT_ACTOR = "admin";
    static final String TYPE_NUMBER = "NUMBER";
    static final String TYPE_STRING = "STRING";

    private final PlatformSettingRepository repository;
    private final AuditLogService auditLog;

    public PlatformSettingService(PlatformSettingRepository repository, AuditLogService auditLog) {
        this.repository = repository;
        this.auditLog = auditLog;
    }

    /** All settings, key-sorted. */
    @Transactional(readOnly = true)
    public List<PlatformSettingView> list() {
        return repository.findAllByOrderByKeyAsc().stream().map(PlatformSettingView::from).toList();
    }

    /** One setting or 404 if absent. */
    @Transactional(readOnly = true)
    public PlatformSettingView get(String key) {
        return repository.findById(key)
                .map(PlatformSettingView::from)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "unknown setting: " + key));
    }

    /**
     * Upsert the value for {@code key}, validating against the value type, writing one
     * hash-chained audit row, and returning the updated row.
     *
     * @param key       setting key (path variable)
     * @param newValue  the new value (required, non-blank)
     * @param updatedBy operator id; falls back to {@value #DEFAULT_ACTOR} when absent
     * @param actorIp   client IP for the audit row (may be null)
     */
    @Transactional
    public PlatformSettingView upsert(String key, String newValue, String updatedBy, String actorIp) {
        if (newValue == null) {
            throw badRequest("value is required");
        }

        Optional<PlatformSettingEntity> existing = repository.findById(key);
        // A known key keeps its declared type; an unknown key defaults to STRING.
        String valueType = existing.map(PlatformSettingEntity::getValueType).orElse(TYPE_STRING);
        validate(valueType, newValue);

        String actor = actor(updatedBy);
        byte[] before = existing.map(e -> snapshot(e.getKey(), e.getValue(), e.getValueType()))
                .orElse(null);

        PlatformSettingEntity row = existing.orElseGet(() -> {
            PlatformSettingEntity e = new PlatformSettingEntity();
            e.setKey(key);
            e.setValueType(TYPE_STRING);
            return e;
        });
        row.setValue(newValue);
        row.setUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        row.setUpdatedBy(actor);
        PlatformSettingEntity saved = repository.saveAndFlush(row);

        auditLog.publish(AGGREGATE, key, actor, actorIp, "PLATFORM_SETTING_UPDATED",
                before, snapshot(saved.getKey(), saved.getValue(), saved.getValueType()));

        return PlatformSettingView.from(saved);
    }

    /** NUMBER values must parse as a decimal; STRING accepts anything non-null. */
    private static void validate(String valueType, String value) {
        if (TYPE_NUMBER.equalsIgnoreCase(valueType)) {
            try {
                new java.math.BigDecimal(value.trim());
            } catch (NumberFormatException ex) {
                throw badRequest("value must be a number for a " + TYPE_NUMBER + " setting: " + value);
            }
        }
    }

    private static String actor(String updatedBy) {
        return updatedBy == null || updatedBy.isBlank() ? DEFAULT_ACTOR : updatedBy;
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    /** Canonical after/before-snapshot bytes for the audit row (fixed key order). */
    private static byte[] snapshot(String key, String value, String valueType) {
        return ("{\"key\":" + jsonString(key)
                + ",\"value\":" + jsonString(value)
                + ",\"valueType\":" + jsonString(valueType) + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String jsonString(String s) {
        if (s == null) {
            return "null";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
