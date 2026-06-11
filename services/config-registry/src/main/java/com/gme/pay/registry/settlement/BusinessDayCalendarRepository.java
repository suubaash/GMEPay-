package com.gme.pay.registry.settlement;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link BusinessDayCalendarEntity}
 * ({@code business_day_calendar}, V014). Read-only reference data — the seed
 * is owned by Flyway, nothing writes at runtime.
 */
@Repository
public interface BusinessDayCalendarRepository
        extends JpaRepository<BusinessDayCalendarEntity, Long> {

    /**
     * Every holiday of the given countries inside {@code [from, to]}
     * (inclusive bounds), ordered by date then country so explanation labels
     * come out deterministic. Served by the V014 UNIQUE(country, holiday_date)
     * index. This is the calculator's projection window query — the service
     * fetches once per preview, never per-day.
     */
    List<BusinessDayCalendarEntity>
    findByCountryInAndHolidayDateBetweenOrderByHolidayDateAscCountryAsc(
            Collection<String> countries, LocalDate from, LocalDate to);
}
