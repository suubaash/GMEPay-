package com.gme.pay.rbac.constraint;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One constraint instance: its {@link ConstraintType} plus a flat string-keyed config
 * (e.g. {@code timezone=Asia/Tokyo, startHour=9, endHour=17, days=MON,TUE,WED,THU,FRI}).
 *
 * <p>Config is intentionally {@code Map<String,String>} (CSV for sets, plain text for
 * numbers) so the engine stays dependency-free — JSON↔Map parsing happens at the storage /
 * header boundary where Jackson is available, never inside the engine.
 */
public record Constraint(ConstraintType type, Map<String, String> config) {

    public Constraint {
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    public String get(String key) {
        return config.get(key);
    }

    public int getInt(String key, int dflt) {
        String v = config.get(key);
        if (v == null || v.isBlank()) return dflt;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    public BigDecimal getDecimal(String key) {
        String v = config.get(key);
        if (v == null || v.isBlank()) return null;
        try {
            return new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** CSV value → upper-cased, insertion-ordered set; empty set if absent. */
    public Set<String> getSet(String key) {
        String v = config.get(key);
        if (v == null || v.isBlank()) return Set.of();
        return Arrays.stream(v.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
