package com.gme.pay.rbac.constraint;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;

/**
 * DATA_FILTER constraint: scopes the data a caller may touch — currency / merchant
 * allowlists and a date-range bound. A dimension is checked only when both the constraint
 * specifies it AND the request context carries it. Config: {@code currencies} (CSV),
 * {@code merchants} (CSV), {@code dateFrom} / {@code dateTo} (ISO dates). ({@code rowLimit}
 * is a query-shaping hint applied at the data layer, not enforced here.)
 *
 * <p>Example (Japan report): {@code currencies=JPY}.
 */
public class DataFilterConstraintEvaluator implements ConstraintEvaluator {

    @Override
    public ConstraintType type() {
        return ConstraintType.DATA_FILTER;
    }

    @Override
    public Optional<String> violation(Constraint c, ConstraintContext ctx) {
        Set<String> currencies = c.getSet("currencies");
        if (!currencies.isEmpty() && ctx.currency() != null
                && !currencies.contains(ctx.currency().toUpperCase())) {
            return Optional.of("currency '" + ctx.currency() + "' not in allowed " + currencies);
        }

        Set<String> merchants = c.getSet("merchants");
        if (!merchants.isEmpty() && ctx.merchantId() != null
                && !merchants.contains(ctx.merchantId().toUpperCase())) {
            return Optional.of("merchant '" + ctx.merchantId() + "' not in allowed filter");
        }

        if (ctx.requestedDate() != null) {
            LocalDate from = parse(c.get("dateFrom"));
            LocalDate to = parse(c.get("dateTo"));
            if (from != null && ctx.requestedDate().isBefore(from)) {
                return Optional.of("date " + ctx.requestedDate() + " before allowed " + from);
            }
            if (to != null && ctx.requestedDate().isAfter(to)) {
                return Optional.of("date " + ctx.requestedDate() + " after allowed " + to);
            }
        }
        return Optional.empty();
    }

    private static LocalDate parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
