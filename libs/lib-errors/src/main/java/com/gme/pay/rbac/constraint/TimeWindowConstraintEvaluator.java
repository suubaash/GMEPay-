package com.gme.pay.rbac.constraint;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * TIME constraint: the request instant, viewed in the constraint's timezone, must fall on an
 * allowed day-of-week and within {@code [startHour, endHour)}. Config:
 * {@code timezone} (IANA, default UTC), {@code startHour} (0–24, default 0),
 * {@code endHour} (0–24, default 24), {@code days} (CSV of MON..SUN, default = all days).
 *
 * <p>Example (Japan report): {@code timezone=Asia/Tokyo, startHour=9, endHour=17,
 * days=MON,TUE,WED,THU,FRI}.
 */
public class TimeWindowConstraintEvaluator implements ConstraintEvaluator {

    @Override
    public ConstraintType type() {
        return ConstraintType.TIME;
    }

    @Override
    public Optional<String> violation(Constraint c, ConstraintContext ctx) {
        if (ctx.now() == null) {
            return Optional.empty();
        }
        ZoneId zone;
        try {
            String tz = c.get("timezone");
            zone = (tz == null || tz.isBlank()) ? ZoneOffset.UTC : ZoneId.of(tz.trim());
        } catch (DateTimeException e) {
            zone = ZoneOffset.UTC;
        }
        ZonedDateTime local = ctx.now().atZone(zone);

        Set<String> days = c.getSet("days");
        String dow = local.getDayOfWeek().name().substring(0, 3); // MONDAY -> MON
        if (!days.isEmpty() && !days.contains(dow)) {
            return Optional.of("outside allowed days: " + local.getDayOfWeek() + " (" + zone + ")");
        }

        int start = c.getInt("startHour", 0);
        int end = c.getInt("endHour", 24);
        int hour = local.getHour();
        if (hour < start || hour >= end) {
            return Optional.of("outside business hours " + start + ":00–" + end + ":00 " + zone
                    + " (local " + String.format("%02d:%02d", hour, local.getMinute()) + ")");
        }
        return Optional.empty();
    }
}
