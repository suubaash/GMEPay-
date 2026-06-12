package com.gme.pay.registry.scheme;

import com.gme.pay.contracts.SchemeOperatingHoursView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalTime;
import java.util.Objects;
import org.hibernate.annotations.Immutable;

/**
 * JPA-mapped row of the {@code scheme_operating_hours} reference table (V024)
 * — one row per (scheme × weekday), Slice 7 (Scheme Enablement).
 *
 * <p>{@link Immutable @Immutable}: this is migration-seeded REFERENCE data
 * (same class of table as {@code business_day_calendar}, V014) — no API writes
 * it, no SCD-6 columns, changes arrive as new migrations. Hibernate skips
 * dirty-checking entirely and silently ignores in-memory mutation attempts.
 *
 * <p>Times are wall-clock {@link LocalTime}s evaluated in {@code timezone}
 * (an IANA zone id) — the V013 {@code cutoff_time}/{@code cutoff_timezone}
 * portability choice. {@code weekday} is {@code 0} = Monday .. {@code 6} =
 * Sunday.
 */
@Entity
@Immutable
@Table(name = "scheme_operating_hours")
@IdClass(SchemeOperatingHoursEntity.Key.class)
public class SchemeOperatingHoursEntity {

    @Id
    @Column(name = "scheme_id", nullable = false, length = 20)
    private String schemeId;

    @Id
    @Column(name = "weekday", nullable = false)
    private int weekday;

    @Column(name = "open_time_local", nullable = false)
    private LocalTime openTimeLocal;

    @Column(name = "close_time_local", nullable = false)
    private LocalTime closeTimeLocal;

    /** NULL = the scheme has no intra-day settlement cutoff. */
    @Column(name = "cutoff_time_local")
    private LocalTime cutoffTimeLocal;

    @Column(name = "timezone", nullable = false, length = 40)
    private String timezone;

    public SchemeOperatingHoursEntity() {
        // JPA
    }

    /** Adapt this row to the canonical {@link SchemeOperatingHoursView} wire DTO. */
    public SchemeOperatingHoursView toView() {
        return new SchemeOperatingHoursView(
                schemeId,
                weekday,
                openTimeLocal,
                closeTimeLocal,
                cutoffTimeLocal,
                timezone);
    }

    public String getSchemeId() {
        return schemeId;
    }

    public int getWeekday() {
        return weekday;
    }

    public LocalTime getOpenTimeLocal() {
        return openTimeLocal;
    }

    public LocalTime getCloseTimeLocal() {
        return closeTimeLocal;
    }

    public LocalTime getCutoffTimeLocal() {
        return cutoffTimeLocal;
    }

    public String getTimezone() {
        return timezone;
    }

    /** Composite PK (scheme_id, weekday) — the V024 PRIMARY KEY. */
    public static class Key implements Serializable {

        private String schemeId;
        private int weekday;

        public Key() {
            // JPA
        }

        public Key(String schemeId, int weekday) {
            this.schemeId = schemeId;
            this.weekday = weekday;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key other)) {
                return false;
            }
            return weekday == other.weekday && Objects.equals(schemeId, other.schemeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(schemeId, weekday);
        }
    }
}
