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
        // ZEROPAY and NEPAL both have live adapters (ACTIVE); everything else is
        // honestly PLANNED.
        assertThat(schemes.stream().filter(s -> "ACTIVE".equals(s.status()))
                        .map(SchemeCatalogResponse::schemeId))
                .containsExactlyInAnyOrder("ZEROPAY", "NEPAL");
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
     * The catalog roster equals the authoritative V022 {@code ck_partner_scheme_scheme}
     * DB CHECK roster. Parsed straight from the migration so a future edit to either
     * side without the other fails this test.
     */
    @Test
    void catalogRoster_equalsV022DbCheckRoster() {
        Set<String> dbCheck = parseV022SchemeCheckRoster();
        assertThat(new TreeSet<>(SchemeCatalogService.schemeIds()))
                .as("GET /v1/schemes roster must equal the V022 partner_scheme DB CHECK")
                .isEqualTo(new TreeSet<>(dbCheck));
    }

    /** Read the {@code scheme_id IN (...)} roster out of the V022 migration (classpath). */
    private static Set<String> parseV022SchemeCheckRoster() {
        String resource = "/db/migration/V022__partner_scheme.sql";
        String sql;
        try (InputStream in = SchemeCatalogServiceTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("V022 migration on the test classpath at " + resource).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read V022 migration at " + resource, e);
        }
        // Grab the ck_partner_scheme_scheme CHECK body: scheme_id IN ( '...', '...' )
        Matcher check = Pattern.compile(
                        "ck_partner_scheme_scheme\\s+CHECK\\s*\\(\\s*scheme_id\\s+IN\\s*\\(([^)]*)\\)",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(sql);
        assertThat(check.find())
                .as("V022 must declare ck_partner_scheme_scheme with a scheme_id IN (...) list")
                .isTrue();
        Set<String> roster = new TreeSet<>();
        Matcher token = Pattern.compile("'([A-Z0-9_]+)'").matcher(check.group(1));
        while (token.find()) {
            roster.add(token.group(1));
        }
        assertThat(roster).as("parsed a non-empty V022 roster").isNotEmpty();
        return roster;
    }
}
