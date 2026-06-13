package com.gme.pay.registry.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.partner.PartnerSeeder;
import com.gme.pay.registry.partner.PartnerStore;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Slice 1 acceptance test for the bitemporal {@code partners} table (V004 / ADR-010).
 *
 * <p>What this test pins down:
 * <ul>
 *   <li><b>SCD-6 paired writes</b> — a second {@code save} for the same partner_code
 *       leaves the prior row in place with its {@code superseded_at} populated, and
 *       inserts a fresh current row carrying the new payload. After N edits the table
 *       holds N+1 rows for that code (1 original + N supersessions).</li>
 *   <li><b>Current view</b> — {@code findCurrentByPartnerCode} returns the latest row,
 *       not any historical row. {@code findAllCurrent} (used by the Admin UI list)
 *       returns exactly one row per code.</li>
 *   <li><b>As-of view</b> — {@code findAsOf} at the original {@code recorded_at}
 *       returns the original row; at the latest {@code recorded_at} it returns the
 *       latest row. Operators can reconstruct what the system believed at any prior
 *       moment, defending regulator-time audits.</li>
 *   <li><b>Acceptance-criterion counts</b> — after the GMEREMIT row is updated three
 *       times the table holds {@code total=4, current=1}. This matches the SQL probe
 *       called out in the agent brief:
 *       {@code SELECT count(*), count(*) FILTER (WHERE superseded_at IS NULL) FROM partners}.</li>
 * </ul>
 *
 * <p>Runs against H2 in PostgreSQL compatibility mode (no Docker on this workstation).
 * The same behaviours are pinned against real PostgreSQL 16 by
 * {@link PartnerPostgresMigrationIT} on CI.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PartnerStore.class, CacheConfig.class})
class BitemporalPartnerTest {

    @Autowired
    private PartnerRepository repository;

    @Autowired
    private PartnerStore store;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Suppress the production {@link PartnerSeeder} for this test so we control
     * the timeline ourselves — we want to know exactly which save() calls happened
     * when so the as-of assertions can target known {@code recorded_at} instants.
     * The mock has its no-op {@code run} signature only — it never inserts seeds.
     */
    @MockBean
    private PartnerSeeder partnerSeeder;

    @BeforeEach
    void truncatePartners() {
        // @DataJpaTest gives us a fresh schema per class by default; but to guard
        // against any leakage from a sibling test ordering, wipe the table here.
        // We touch partners directly via JDBC because the bitemporal store API
        // intentionally has no "delete" — under SCD-6 a row is never deleted, only
        // superseded.
        jdbc.update("delete from partners");
    }

    @Test
    void firstSaveCreatesSingleCurrentRow() {
        store.save(Partner.of("ACME", PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));

        Integer total = jdbc.queryForObject(
                "select count(*) from partners where partner_code = 'ACME'", Integer.class);
        Integer current = jdbc.queryForObject(
                "select count(*) from partners where partner_code = 'ACME'"
                        + " and superseded_at is null", Integer.class);

        assertThat(total).as("one INSERT produces one row").isEqualTo(1);
        assertThat(current).as("the one row is the current row (superseded_at IS NULL)").isEqualTo(1);
    }

    @Test
    void secondSaveSupersedesFirstAndInsertsNewCurrentRow() {
        // First save: ACME with HALF_UP rounding.
        store.save(Partner.of("ACME", PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
        Instant afterFirstSave = Instant.now();

        // Tiny pause so the second save's recorded_at is strictly greater than the
        // first save's recorded_at — without this the as-of read may be ambiguous on
        // very-fast machines. The SCD-6 semantics tolerate equal timestamps but the
        // assertion targets the historical view at a recorded_at strictly between
        // the two writes.
        sleepMillis(10);

        // Second save: same partner, switch rounding to DOWN.
        store.save(Partner.of("ACME", PartnerType.OVERSEAS, "USD", RoundingMode.DOWN));

        // SQL probe: total=2 rows, exactly one current.
        Integer total = jdbc.queryForObject(
                "select count(*) from partners where partner_code = 'ACME'", Integer.class);
        Integer current = jdbc.queryForObject(
                "select count(*) from partners where partner_code = 'ACME'"
                        + " and superseded_at is null", Integer.class);
        assertThat(total).as("after one supersession total = 2 rows").isEqualTo(2);
        assertThat(current).as("at most one current row per code (enforced by partners_current index)").isEqualTo(1);

        // The prior row has a populated superseded_at and the OLD rounding mode.
        String priorMode = jdbc.queryForObject(
                "select settlement_rounding_mode from partners where partner_code = 'ACME'"
                        + " and superseded_at is not null", String.class);
        assertThat(priorMode).as("prior row carries the old rounding mode").isEqualTo("HALF_UP");

        // The current view (PartnerStore.get) returns the NEW rounding mode.
        Partner currentView = store.get("ACME");
        assertThat(currentView.settlementRoundingMode())
                .as("current view returns the latest write").isEqualTo(RoundingMode.DOWN);

        // As-of view at an instant BEFORE the second save returns the prior row.
        Partner asOfBeforeSecondWrite = store.getAsOf("ACME", Instant.now(), afterFirstSave);
        assertThat(asOfBeforeSecondWrite.settlementRoundingMode())
                .as("as-of read at the earlier recorded_at returns the original row")
                .isEqualTo(RoundingMode.HALF_UP);
    }

    @Test
    void threeUpdatesProduceFourRowsOneCurrent() {
        // Acceptance criterion from the agent brief, verbatim: "a psql query
        // 'SELECT count(*), count(*) FILTER (WHERE superseded_at IS NULL) FROM partners'
        // after updating GMEREMIT three times shows total=4, current=1".
        store.save(Partner.of("GMEREMIT", PartnerType.LOCAL, "KRW", RoundingMode.HALF_UP));
        sleepMillis(5);
        store.save(Partner.of("GMEREMIT", PartnerType.LOCAL, "KRW", RoundingMode.DOWN));
        sleepMillis(5);
        store.save(Partner.of("GMEREMIT", PartnerType.LOCAL, "KRW", RoundingMode.FLOOR));
        sleepMillis(5);
        store.save(Partner.of("GMEREMIT", PartnerType.LOCAL, "KRW", RoundingMode.CEILING));

        Integer total = jdbc.queryForObject(
                "select count(*) from partners where partner_code = 'GMEREMIT'", Integer.class);
        Integer currentCount = jdbc.queryForObject(
                "select count(*) from partners where partner_code = 'GMEREMIT'"
                        + " and superseded_at is null", Integer.class);

        assertThat(total)
                .as("original + 3 supersessions = 4 rows in the partners table for GMEREMIT")
                .isEqualTo(4);
        assertThat(currentCount)
                .as("only ONE row is current for GMEREMIT (the partial unique index enforces this)")
                .isEqualTo(1);

        // Belt-and-braces: the current row carries the latest (CEILING) rounding mode.
        Partner current = store.get("GMEREMIT");
        assertThat(current.settlementRoundingMode()).isEqualTo(RoundingMode.CEILING);

        // findAllCurrent returns the deduped one-row-per-code view used by Admin UI.
        List<Partner> currentView = store.listAll();
        assertThat(currentView).extracting(Partner::partnerCode).containsExactly("GMEREMIT");
    }

    @Test
    void findCurrentReturnsOnlyTheLatestRowEvenAfterManyEdits() {
        store.save(Partner.of("ROTATE", PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
        sleepMillis(5);
        store.save(Partner.of("ROTATE", PartnerType.OVERSEAS, "USD", RoundingMode.DOWN));
        sleepMillis(5);
        store.save(Partner.of("ROTATE", PartnerType.OVERSEAS, "USD", RoundingMode.UP));

        // Two ways to ask "what is the current row for ROTATE?" — both must agree.
        PartnerEntity viaIndex = repository.findCurrentByPartnerCode("ROTATE").orElseThrow();
        Partner viaStore = store.get("ROTATE");

        assertThat(viaIndex.getSupersededAt())
                .as("the row returned by findCurrentByPartnerCode has superseded_at IS NULL")
                .isNull();
        assertThat(viaIndex.getSettlementRoundingMode()).isEqualTo(RoundingMode.UP);
        assertThat(viaStore.settlementRoundingMode()).isEqualTo(RoundingMode.UP);

        // And the surrogate id round-trips through the store path.
        assertThat(viaStore.partnerId())
                .as("the current row carries a freshly-allocated BIGINT surrogate").isNotNull();
    }

    /**
     * Sleep helper for between-save gaps. Centralised so a future refactor can move
     * from wall-clock to a synthetic clock without touching every test case.
     */
    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while sleeping between bitemporal saves", e);
        }
    }
}
