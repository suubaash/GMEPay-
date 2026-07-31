package com.gme.pay.registry.scheme;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.registry.web.SchemeCatalogResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins the platform-wide scheme roster to ONE source of truth and guards against the
 * drift this test was written to fix.
 *
 * <h2>The bug this prevents</h2>
 *
 * <p>{@code GET /v1/schemes} (the catalog feeding the Admin UI scheme picker) had
 * drifted to advertise {@code QPAY} / {@code SBP} / {@code PROMPTPAY} — none of which
 * the V022 {@code partner_scheme} DB CHECK ({@code ck_partner_scheme_scheme}) or the
 * Slice-7 enablement endpoint ({@link PartnerSchemeService#replaceDraftSchemes})
 * accept. An operator picking one of those from the populated picker would have hit a
 * 400 on save. These assertions lock the catalog, the enablement roster, and the DB
 * CHECK to the same set so they can never diverge again.
 */
class SchemeCatalogServiceTest {

    private static final SchemeCatalogService CATALOG = new SchemeCatalogService();

    /** The catalog leads with ZEROPAY, and the live-adapter schemes are ACTIVE. */
    @Test
    void catalog_leadsWithZeropayActive() {
        List<SchemeCatalogResponse> schemes = CATALOG.listSchemes();
        assertThat(schemes).isNotEmpty();
        assertThat(schemes.get(0).schemeId()).isEqualTo("ZEROPAY");
        assertThat(schemes.get(0).status()).isEqualTo("ACTIVE");
        // ZEROPAY, NEPAL and SENDMN have live adapters (ACTIVE); everything else
        // (incl. NINEPAY, whose hub wiring is deferred) is honestly PLANNED.
        assertThat(schemes.stream().filter(s -> "ACTIVE".equals(s.status()))
                        .map(SchemeCatalogResponse::schemeId))
                .containsExactlyInAnyOrder("ZEROPAY", "NEPAL", "SENDMN");
    }

    /** Catalog rows carry the field names the BFF {@code SchemeSummary} binds. */
    @Test
    void catalog_rowsAreFullyPopulated() {
        for (SchemeCatalogResponse s : CATALOG.listSchemes()) {
            assertThat(s.schemeId()).isNotBlank();
            assertThat(s.name()).isNotBlank();
            assertThat(s.country()).hasSize(2);
            assertThat(s.currency()).hasSize(3);
            assertThat(s.mode()).isIn("LIVE", "SANDBOX");
            assertThat(s.status()).isIn("ACTIVE", "PLANNED");
        }
    }

    /**
     * The enablement endpoint's accepted roster IS the catalog roster — they are
     * literally the same set object now, so a picker entry is always enableable.
     */
    @Test
    void enablementRoster_equalsCatalogRoster() {
        assertThat(PartnerSchemeService.SCHEMES)
                .isEqualTo(SchemeCatalogService.schemeIds());
    }

    /**
     * The catalog roster equals the authoritative {@code ck_partner_scheme_scheme}
     * DB CHECK roster as of the LATEST migration that (re-)declares it — V022 created
     * it, V041 re-created it with SENDMN + NINEPAY. Parsed straight from the migration
     * so a future edit to either side without the other fails this test.
     */
    @Test
    void catalogRoster_equalsDbCheckRoster() {
        Set<String> dbCheck = parseSchemeCheckRoster(
                "/db/migration/V041__partner_scheme_sendmn_ninepay.sql");
        assertThat(new TreeSet<>(SchemeCatalogService.schemeIds()))
                .as("GET /v1/schemes roster must equal the current partner_scheme DB CHECK (V041)")
                .isEqualTo(new TreeSet<>(dbCheck));
    }

    /** Read the {@code scheme_id IN (...)} roster out of a migration on the classpath. */
    private static Set<String> parseSchemeCheckRoster(String resource) {
        String sql;
        try (InputStream in = SchemeCatalogServiceTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("migration on the test classpath at " + resource).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read migration at " + resource, e);
        }
        // Grab the LAST ck_partner_scheme_scheme CHECK body (a re-declaring migration
        // DROPs then ADDs; the ADD is the authoritative roster): scheme_id IN ('...', ...)
        Matcher check = Pattern.compile(
                        "ck_partner_scheme_scheme\\s+CHECK\\s*\\(\\s*scheme_id\\s+IN\\s*\\(([^)]*)\\)",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(sql);
        String group = null;
        while (check.find()) {
            group = check.group(1);
        }
        assertThat(group)
                .as(resource + " must declare ck_partner_scheme_scheme with a scheme_id IN (...) list")
                .isNotNull();
        Set<String> roster = new TreeSet<>();
        Matcher token = Pattern.compile("'([A-Z0-9_]+)'").matcher(group);
        while (token.find()) {
            roster.add(token.group(1));
        }
        assertThat(roster).as("parsed a non-empty roster from " + resource).isNotEmpty();
        return roster;
    }
}
