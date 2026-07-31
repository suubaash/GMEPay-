package com.gme.pay.auth.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Build-time guards on the audit vocabulary and on the DDL copy.
 *
 * <h2>Why these are worth a test</h2>
 *
 * <p>Both columns are {@code VARCHAR(64)}. A constant that outgrows the column does not fail at
 * compile time and does not fail obviously at runtime either — on PostgreSQL the INSERT errors,
 * which {@code DbAuditPublisher} logs and swallows per the {@code AuditPublisher} contract, so the
 * observable symptom is <b>an event that silently has no audit row</b>. That is the precise
 * failure this whole gap is about, so it is checked mechanically rather than by review.
 *
 * <p>The second guard is on {@code V007__audit_log.sql}, which is a deliberate copy of lib-audit's
 * DDL (see that file's header for why it is copied rather than mounted as a second Flyway
 * location). A copy drifts. {@code DbAuditPublisher}'s INSERT and SELECT statements are shared
 * code, so a column this service failed to copy would break every audit write in it — and a
 * regulator sweeping {@code audit_log} across services must see one shape.
 */
class AuthAuditEventsSelfCheck {

    /** Both {@code aggregate_type} and {@code event_type} are VARCHAR(64). */
    private static final int COLUMN_WIDTH = 64;

    @Test
    @DisplayName("every aggregate_type / event_type constant fits VARCHAR(64)")
    void everyConstantFitsItsColumn() throws Exception {
        for (Field f : constants()) {
            String value = (String) f.get(null);
            assertThat(value.length())
                    .as("AuthAuditEvents.%s (%s) must fit audit_log's VARCHAR(64) — an over-long "
                            + "value makes the INSERT fail, and DbAuditPublisher swallows that, so "
                            + "the event would silently have NO audit row", f.getName(), value)
                    .isLessThanOrEqualTo(COLUMN_WIDTH);
        }
    }

    @Test
    @DisplayName("no two constants share a value (a collision merges two distinct events)")
    void constantsAreDistinct() throws Exception {
        Set<String> seen = new LinkedHashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (Field f : constants()) {
            String value = (String) f.get(null);
            if (!seen.add(value)) {
                duplicates.add(f.getName() + "=" + value);
            }
        }
        // Two names for one wire value means a WHERE event_type = ? returns rows of both kinds,
        // and neither query can be trusted.
        assertThat(duplicates).isEmpty();
    }

    @Test
    @DisplayName("aggregate types are lower_snake_case, event types UPPER_SNAKE_CASE")
    void constantsFollowTheNamingConvention() throws Exception {
        for (Field f : constants()) {
            String value = (String) f.get(null);
            if (isAggregateType(f.getName())) {
                assertThat(value)
                        .as("aggregate type %s must be lower_snake_case", f.getName())
                        .matches("[a-z][a-z0-9_]*");
            } else if (isEventType(f.getName())) {
                assertThat(value)
                        .as("event type %s must be UPPER_SNAKE_CASE", f.getName())
                        .matches("[A-Z][A-Z0-9_]*");
            }
        }
    }

    @Test
    @DisplayName("aggregate-id helpers clamp to VARCHAR(64) and keep the namespace prefix")
    void aggregateIdHelpersClamp() {
        String longSubject = "svc:" + "x".repeat(200);
        String id = AuthAuditEvents.subjectAggregate(longSubject);

        assertThat(id.length()).isEqualTo(AuthAuditEvents.MAX_AGGREGATE_ID_LEN);
        // Truncation keeps the PREFIX: an over-long value must not lose the namespace and start
        // looking like an id from a different namespace.
        assertThat(id).startsWith("sub:");

        // A blank/absent subject becomes the explicit bucket, never an empty chain key (which
        // would violate the NOT NULL/NOT-blank intent and group unrelated events under "").
        assertThat(AuthAuditEvents.subjectAggregate(null))
                .isEqualTo(AuthAuditEvents.UNKNOWN_SUBJECT);
        assertThat(AuthAuditEvents.subjectAggregate("  "))
                .isEqualTo(AuthAuditEvents.UNKNOWN_SUBJECT);
        assertThat(AuthAuditEvents.clamp(null)).isEqualTo(AuthAuditEvents.UNKNOWN_SUBJECT);

        assertThat(AuthAuditEvents.unknownApiKeyAggregate(null)).isEqualTo("apikey:none");
        assertThat(AuthAuditEvents.unknownApiKeyAggregate("pk_live_abcdefghijklmnop"))
                .isEqualTo("apikey:pk_live_abcdefghijkl");
    }

    @Test
    @DisplayName("V007__audit_log.sql matches lib-audit's audit_log DDL column-for-column")
    void serviceDdlMatchesLibAuditDdl() throws Exception {
        Set<String> service = columnsOf(read("db/migration/V007__audit_log.sql"));
        Set<String> library = columnsOf(read("db/audit/V1__audit_log.sql"));

        assertThat(service)
                .as("V007's audit_log columns must equal lib-audit's — DbAuditPublisher's INSERT "
                        + "and SELECT are shared code, so any divergence breaks every audit write "
                        + "in this service. Re-sync with a NEW migration, never by editing V007.")
                .isEqualTo(library);
        // Spot-check the two that carry the tamper-evidence, so a future refactor of the parser
        // cannot make this test vacuously pass.
        assertThat(service).contains("row_hash", "prev_hash", "chain_version", "actor_id");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private static List<Field> constants() {
        List<Field> out = new ArrayList<>();
        for (Field f : AuthAuditEvents.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())
                    && Modifier.isPublic(f.getModifiers())
                    && f.getType() == String.class) {
                out.add(f);
            }
        }
        assertThat(out).as("expected the AuthAuditEvents vocabulary to be non-empty").isNotEmpty();
        return out;
    }

    private static boolean isAggregateType(String fieldName) {
        return Set.of("SESSION", "TOKEN", "API_KEY", "API_KEY_PRINCIPAL", "PERMISSION", "ROLE",
                "PRINCIPAL", "CONSTRAINT").contains(fieldName);
    }

    private static boolean isEventType(String fieldName) {
        return fieldName.equals(fieldName.toUpperCase(Locale.ROOT))
                && !isAggregateType(fieldName)
                && !fieldName.equals("UNKNOWN_SUBJECT")
                && !fieldName.equals("MAX_AGGREGATE_ID_LEN");
    }

    private static String read(String classpathResource) throws IOException {
        try (InputStream in = AuthAuditEventsSelfCheck.class.getClassLoader()
                .getResourceAsStream(classpathResource)) {
            assertThat(in).as("classpath resource %s must exist", classpathResource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Extract the column names of the {@code CREATE TABLE ... audit_log (...)} body. Deliberately a
     * tiny parser rather than a schema-metadata query: this must compare the two SQL FILES, so a
     * divergence is caught even when only one of them has been applied to a database.
     */
    private static Set<String> columnsOf(String sql) {
        Matcher table = Pattern.compile(
                        "CREATE TABLE (?:IF NOT EXISTS )?audit_log\\s*\\((.*?)\\n\\);",
                        Pattern.DOTALL | Pattern.CASE_INSENSITIVE)
                .matcher(sql);
        assertThat(table.find()).as("audit_log CREATE TABLE not found in the migration").isTrue();

        Set<String> columns = new TreeSet<>();
        for (String rawLine : table.group(1).split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("--") || line.startsWith("CONSTRAINT")) {
                continue;
            }
            Matcher column = Pattern.compile("^([a-z_][a-z0-9_]*)\\s+[A-Za-z]").matcher(line);
            if (column.find()) {
                columns.add(column.group(1));
            }
        }
        return columns;
    }
}
