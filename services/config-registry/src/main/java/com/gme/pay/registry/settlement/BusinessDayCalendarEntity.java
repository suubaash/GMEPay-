package com.gme.pay.registry.settlement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * JPA-mapped row of the {@code business_day_calendar} reference table (V014)
 * — one (country, date) bank-closure fact feeding
 * {@link SettlementScheduleCalculator}.
 *
 * <p>Deliberately NOT bitemporal: a public holiday is reference data, not a
 * regulated partner fact — corrections land as ordinary follow-up migrations
 * (the V014 header records which 2027/2028 lunar dates are projections).
 * Read-only at runtime; the seed is owned by Flyway.
 */
@Entity
@Table(name = "business_day_calendar")
public class BusinessDayCalendarEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /** ISO-3166 alpha-2 country the closure applies to. */
    @Column(name = "country", nullable = false, length = 2, columnDefinition = "CHAR(2)")
    private String country;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate holidayDate;

    /** Surfaces verbatim in {@code SettlementPreview} explanation lines. */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    public BusinessDayCalendarEntity() {
        // JPA
    }

    public Long getId() {
        return id;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public LocalDate getHolidayDate() {
        return holidayDate;
    }

    public void setHolidayDate(LocalDate holidayDate) {
        this.holidayDate = holidayDate;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
