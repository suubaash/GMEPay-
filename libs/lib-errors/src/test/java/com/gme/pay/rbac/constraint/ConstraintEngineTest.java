package com.gme.pay.rbac.constraint;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exhaustive tests for the {@link ConstraintEngine}, anchored on the GMEPay+ report
 * scenarios (Japan / Korea / CIS) plus amount thresholds, cascading AND, weekend exclusion,
 * CFO override, and approval gating.
 */
class ConstraintEngineTest {

    private final ConstraintEngine engine = new ConstraintEngine();

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    /** Japan report: Japan location + 9–5 Tokyo weekday + JPY only. */
    private static final List<Constraint> JAPAN_REPORT = List.of(
            new Constraint(ConstraintType.LOCATION, Map.of("countries", "JP")),
            new Constraint(ConstraintType.TIME, Map.of(
                    "timezone", "Asia/Tokyo", "startHour", "9", "endHour", "17",
                    "days", "MON,TUE,WED,THU,FRI")),
            new Constraint(ConstraintType.DATA_FILTER, Map.of("currencies", "JPY")));

    /** A Tuesday at the given Tokyo local hour, as a UTC instant. */
    private static Instant tokyo(int hour, DayOfWeek day) {
        ZonedDateTime z = ZonedDateTime.of(2026, 6, 15, hour, 0, 0, 0, TOKYO)
                .with(TemporalAdjusters.nextOrSame(day));
        return z.toInstant();
    }

    // ---------------------------------------------------------------- Japan

    @Test
    @DisplayName("Japan report: Japan staff, Tokyo 10:00 Tue, JPY → ALLOWED")
    void japan_allHold_allows() {
        var ctx = ConstraintContext.builder(tokyo(10, DayOfWeek.TUESDAY))
                .country("JP").currency("JPY").build();
        assertThat(engine.evaluate(JAPAN_REPORT, ctx).allowed()).isTrue();
    }

    @Test
    @DisplayName("Japan report: outside business hours (20:00 Tokyo) → DENIED on TIME")
    void japan_afterHours_deniesOnTime() {
        var ctx = ConstraintContext.builder(tokyo(20, DayOfWeek.TUESDAY))
                .country("JP").currency("JPY").build();
        var d = engine.evaluate(JAPAN_REPORT, ctx);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("business hours");
    }

    @Test
    @DisplayName("Japan report: weekend (Saturday) → DENIED on TIME days")
    void japan_weekend_deniesOnDays() {
        var ctx = ConstraintContext.builder(tokyo(10, DayOfWeek.SATURDAY))
                .country("JP").currency("JPY").build();
        var d = engine.evaluate(JAPAN_REPORT, ctx);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("allowed days");
    }

    @Test
    @DisplayName("Japan report: wrong country (KR) → DENIED on LOCATION")
    void japan_wrongCountry_deniesOnLocation() {
        var ctx = ConstraintContext.builder(tokyo(10, DayOfWeek.TUESDAY))
                .country("KR").currency("JPY").build();
        var d = engine.evaluate(JAPAN_REPORT, ctx);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("country");
    }

    @Test
    @DisplayName("Japan report: wrong currency (USD) → DENIED on DATA_FILTER")
    void japan_wrongCurrency_deniesOnDataFilter() {
        var ctx = ConstraintContext.builder(tokyo(10, DayOfWeek.TUESDAY))
                .country("JP").currency("USD").build();
        var d = engine.evaluate(JAPAN_REPORT, ctx);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("currency");
    }

    @Test
    @DisplayName("Cascading AND: country + time + currency all wrong → 3 violations")
    void cascade_collectsAllViolations() {
        var ctx = ConstraintContext.builder(tokyo(20, DayOfWeek.SATURDAY))
                .country("KR").currency("USD").build();
        var d = engine.evaluate(JAPAN_REPORT, ctx);
        assertThat(d.allowed()).isFalse();
        assertThat(d.violations()).hasSize(3);
    }

    @Test
    @DisplayName("CFO super-user bypasses every constraint")
    void superuser_bypassesAll() {
        var ctx = ConstraintContext.builder(tokyo(20, DayOfWeek.SATURDAY))
                .country("KR").currency("USD").superuser(true).build();
        assertThat(engine.evaluate(JAPAN_REPORT, ctx).allowed()).isTrue();
    }

    // ---------------------------------------------------------- Korea / CIS

    @Test
    @DisplayName("Korea report: KRW allowed, JPY denied")
    void korea() {
        var korea = List.of(
                new Constraint(ConstraintType.LOCATION, Map.of("countries", "KR")),
                new Constraint(ConstraintType.DATA_FILTER, Map.of("currencies", "KRW")));
        Instant t = tokyo(10, DayOfWeek.TUESDAY);
        assertThat(engine.evaluate(korea,
                ConstraintContext.builder(t).country("KR").currency("KRW").build()).allowed()).isTrue();
        assertThat(engine.evaluate(korea,
                ConstraintContext.builder(t).country("KR").currency("JPY").build()).allowed()).isFalse();
    }

    @Test
    @DisplayName("CIS report: RUB only")
    void cis() {
        var cis = List.of(new Constraint(ConstraintType.DATA_FILTER, Map.of("currencies", "RUB")));
        Instant t = tokyo(10, DayOfWeek.TUESDAY);
        assertThat(engine.evaluate(cis,
                ConstraintContext.builder(t).currency("RUB").build()).allowed()).isTrue();
        assertThat(engine.evaluate(cis,
                ConstraintContext.builder(t).currency("USD").build()).allowed()).isFalse();
    }

    // ---------------------------------------------------------- Amount / Approval

    @Test
    @DisplayName("Amount threshold: self-service refund ≤ 1000 allowed, > 1000 denied")
    void amountThreshold() {
        var refund = List.of(new Constraint(ConstraintType.AMOUNT, Map.of("maxAmount", "1000")));
        Instant t = tokyo(10, DayOfWeek.TUESDAY);
        assertThat(engine.evaluate(refund,
                ConstraintContext.builder(t).amount(new BigDecimal("1000")).currency("USD").build()).allowed()).isTrue();
        var over = engine.evaluate(refund,
                ConstraintContext.builder(t).amount(new BigDecimal("1500")).currency("USD").build());
        assertThat(over.allowed()).isFalse();
        assertThat(over.reason()).contains("exceeds threshold");
    }

    @Test
    @DisplayName("APPROVAL constraint denies without approval, allows with")
    void approvalGating() {
        var needsApproval = List.of(
                new Constraint(ConstraintType.APPROVAL, Map.of("workflow", "refund-l2")));
        Instant t = tokyo(10, DayOfWeek.TUESDAY);
        assertThat(engine.evaluate(needsApproval,
                ConstraintContext.builder(t).approvalGranted(false).build()).allowed()).isFalse();
        assertThat(engine.evaluate(needsApproval,
                ConstraintContext.builder(t).approvalGranted(true).build()).allowed()).isTrue();
    }

    @Test
    @DisplayName("No constraints → allowed")
    void noConstraints_allows() {
        assertThat(engine.evaluate(List.of(),
                ConstraintContext.builder(Instant.now()).build()).allowed()).isTrue();
    }
}
