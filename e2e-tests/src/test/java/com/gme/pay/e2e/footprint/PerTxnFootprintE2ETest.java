package com.gme.pay.e2e.footprint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.e2e.SchemeFleet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Measures the <b>storage footprint of one successful payment</b> against the real money-path
 * fleet (gap <b>T3-5</b>: no capacity plan).
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The IT team sizing AWS infrastructure was handed an <em>estimate</em> — roughly 15 DB rows
 * and ~20 KB retained per transaction — arrived at by reading code and counting tables. An
 * estimate is a reasonable thing to open with and an unreasonable thing to buy hardware on. This
 * test replaces the countable half of it with a measurement: it drives N real payments through
 * the same fleet {@code WalletScanPayE2ETest} boots, and reads the services' own databases
 * before and after.
 *
 * <h2>What makes the numbers real</h2>
 *
 * <p>Nothing here is mocked or shaped for measurement. It is the genuine cascade —
 * merchant-qr-data resolve → scheme-adapter-zeropay → {@code sim-scheme} authorize+commit →
 * transaction-mgmt persist → revenue-ledger fee journal — writing through real JPA mappings into
 * schemas created by the real Flyway migrations. Row counts, payload widths and index
 * definitions are therefore facts, not assumptions.
 *
 * <p>The one thing this fleet cannot supply is PostgreSQL itself: the E2E harness runs on H2 so
 * that it needs no Docker, which means {@code pg_total_relation_size} and
 * {@code pg_current_wal_lsn} are unavailable. Physical bytes and WAL are therefore DERIVED from
 * the measured row shape by {@link PostgresSizeModel}, and the report says so on every line.
 * See {@code Documentation/CAPACITY_AND_SLA.md} for what it would take to measure that half too.
 *
 * <h2>Discipline</h2>
 *
 * <p>Tagged {@code e2e} like its siblings, so {@code gradlew build} and CI never reach it; run it
 * with {@code gradlew :e2e-tests:e2eTest --tests *PerTxnFootprintE2ETest*}. Teardown goes through
 * {@link SchemeFleet#shutdown()}, which force-kills every descendant and then <b>proves the ports
 * released</b>; ports are also asserted free before boot, so a zombie from a previous run fails
 * loudly instead of silently corrupting a measurement.
 */
@Tag("e2e")
@DisplayName("E2E: measured DB/WAL/log footprint of one successful payment (T3-5 capacity)")
class PerTxnFootprintE2ETest {

    /**
     * Payments measured. Override with {@code -Dgmepay.footprint.payments=200}.
     *
     * <p>50 is chosen so per-transaction averages are stable while the run stays inside a few
     * minutes on a laptop. The figure that actually matters — rows per transaction — is an
     * integer-valued quantity per table, so it converges almost immediately; the bytes averages
     * are what benefit from a larger N.
     */
    private static final int PAYMENTS =
            Integer.getInteger("gmepay.footprint.payments", 50);

    /**
     * Payments driven BEFORE the measurement snapshot is taken.
     *
     * <p>Load-bearing. The first payment through a cold fleet writes things a steady-state
     * payment does not: lazy-initialised beans log their startup, caches take their first miss,
     * and any once-per-day or once-per-partner row gets created. Counting those against
     * transaction #1 would inflate the per-transaction figure the projection multiplies by
     * 365 000.
     */
    private static final int WARMUP_PAYMENTS = 5;

    // Same fixtures WalletScanPayE2ETest pays, so the measured path is the proven one:
    // a full EMVCo static QR with a valid tag-63 CRC, which round-trips through sim-scheme.
    private static final String QR_ACTIVE =
            "00020101021129260011com.zeropay0107ZP-M0015204581253034105802KR5918Seoul Noodle House6005Seoul63040B08";
    private static final String AMOUNT_KRW = "50000";

    // Dedicated port band so this can never collide with a sibling E2E fleet in the same run.
    private static final int PORT_CONFIG_REGISTRY = 19181;
    private static final int PORT_TXN_MGMT = 19182;
    private static final int PORT_MERCHANT_QR = 19183;
    private static final int PORT_PAYMENT_EXECUTOR = 19184;
    private static final int PORT_REVENUE_LEDGER = 19185;
    private static final int PORT_SCHEME_ADAPTER = 19190;
    private static final int PORT_SIM_SCHEME = 19102;

    /**
     * scheme-adapter-zeropay is the one service in this fleet that runs its actuator on a
     * SEPARATE management port ({@code application.yml} pins it to 8091). Overriding only
     * {@code server.port} therefore leaves it binding a fixed, shared port, and it exits with
     * "Port 8091 was already in use" the moment anything else on the machine holds it — which
     * makes the harness fail for a reason that has nothing to do with the measurement. Moving it
     * into this test's own band makes the fleet self-contained.
     */
    private static final int PORT_SCHEME_ADAPTER_MGMT = 19191;

    /**
     * Tables that exist only to record that something went wrong. Growth here during a
     * supposedly-happy-path run is a finding, not a footprint.
     */
    private static final java.util.Set<String> FAILURE_SINK_TABLES = java.util.Set.of(
            "revenue_posting_failures", "webhook_dlq", "recon_exceptions", "unscreened_payments");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static SchemeFleet fleet;
    private static Path dbDir;
    private static final List<DatabaseProbe> PROBES = new ArrayList<>();
    private static final Map<String, Long> LOG_BYTES_AT_SNAPSHOT = new LinkedHashMap<>();

    /** Services whose datasource this run redirects to an inspectable file-backed H2. */
    private static final Map<String, String> DB_SERVICES = new LinkedHashMap<>(Map.of(
            "config-registry", "configreg",
            "transaction-mgmt", "txndb",
            "revenue-ledger", "ledger",
            "scheme-adapter-zeropay", "zpadapterdb",
            "payment-executor", "payment_executor"));

    @BeforeAll
    static void bootFleet() throws Exception {
        fleet = new SchemeFleet("footprint-logs");
        fleet.assertPortsFree(PORT_CONFIG_REGISTRY, PORT_TXN_MGMT, PORT_MERCHANT_QR,
                PORT_PAYMENT_EXECUTOR, PORT_REVENUE_LEDGER, PORT_SCHEME_ADAPTER,
                PORT_SCHEME_ADAPTER_MGMT, PORT_SIM_SCHEME);

        // A fresh directory per run: a leftover database would make "rows before" meaningless.
        dbDir = fleet.repoRoot().resolve("e2e-tests/build/footprint-db/run-" + System.currentTimeMillis());
        Files.createDirectories(dbDir);

        fleet.launchService("config-registry", PORT_CONFIG_REGISTRY, datasourceEnv("config-registry"));
        fleet.launchService("merchant-qr-data", PORT_MERCHANT_QR, Map.of());
        fleet.launchService("transaction-mgmt", PORT_TXN_MGMT, datasourceEnv("transaction-mgmt"));
        fleet.launchService("revenue-ledger", PORT_REVENUE_LEDGER, datasourceEnv("revenue-ledger"));
        fleet.launchService("scheme-adapter-zeropay", PORT_SCHEME_ADAPTER,
                datasourceEnv("scheme-adapter-zeropay"),
                "--gmepay.scheme.zeropay.base-url=http://localhost:" + PORT_SIM_SCHEME + "/v1/scheme",
                "--management.server.port=" + PORT_SCHEME_ADAPTER_MGMT);
        fleet.launchSim("sim-scheme", PORT_SIM_SCHEME, Map.of(),
                "--gmepay.sim.scheme.profile=ZEROPAY");
        fleet.launchService("payment-executor", PORT_PAYMENT_EXECUTOR,
                datasourceEnv("payment-executor"),
                "--gmepay.merchant-qr-data.base-url=http://localhost:" + PORT_MERCHANT_QR,
                "--gmepay.scheme-adapter-zeropay.base-url=http://localhost:" + PORT_SCHEME_ADAPTER,
                "--gmepay.transaction-mgmt.base-url=http://localhost:" + PORT_TXN_MGMT,
                "--gmepay.revenue-ledger.base-url=http://localhost:" + PORT_REVENUE_LEDGER,
                "--gmepay.config-registry.base-url=http://localhost:" + PORT_CONFIG_REGISTRY,
                "--gmepay.payment.merchant-validation=strict");

        fleet.awaitUp(Duration.ofSeconds(240));
    }

    @AfterAll
    static void stopFleet() {
        for (DatabaseProbe probe : PROBES) {
            probe.close();
        }
        if (fleet != null) {
            fleet.shutdown(); // force-kill + prove every port released
        }
    }

    /**
     * A service's datasource, redirected to a file-backed H2 this JVM can also open.
     *
     * <p>{@code AUTO_SERVER=TRUE} is what makes the database readable from the test process
     * without starting any database server: the owning JVM serves the second connection itself.
     * Everything else mirrors the service's own shipped default — same PostgreSQL compatibility
     * mode, same lower-casing — so Flyway and Hibernate behave exactly as they do in the
     * in-memory configuration the other E2E tests use.
     */
    private static Map<String, String> datasourceEnv(String service) {
        return Map.of("SPRING_DATASOURCE_URL", jdbcUrl(service));
    }

    private static String jdbcUrl(String service) {
        String file = dbDir.resolve(DB_SERVICES.get(service)).toString().replace('\\', '/');
        return "jdbc:h2:file:" + file
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("drive N payments and report the measured rows/bytes/WAL/logs per transaction")
    void measurePerTransactionFootprint() throws Exception {
        // --- warm-up: absorb cold-start writes so they are not billed to transaction #1 ---
        for (int i = 0; i < WARMUP_PAYMENTS; i++) {
            payOrFail("warmup-" + i);
        }
        settle();

        // --- snapshot every database and every log file ---
        for (String service : DB_SERVICES.keySet()) {
            DatabaseProbe probe = new DatabaseProbe(service, jdbcUrl(service));
            probe.snapshot();
            PROBES.add(probe);
        }
        for (String name : fleet.serviceNames()) {
            LOG_BYTES_AT_SNAPSHOT.put(name, logBytes(name));
        }

        // --- the measured window: N genuinely successful payments ---
        long startedAt = System.nanoTime();
        for (int i = 0; i < PAYMENTS; i++) {
            payOrFail("measure-" + i);
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        settle();

        // --- collect ---
        List<TableFootprint> tables = new ArrayList<>();
        for (DatabaseProbe probe : PROBES) {
            tables.addAll(probe.measure());
        }
        tables.sort((a, b) -> Long.compare(b.dbBytes(), a.dbBytes()));

        Map<String, Long> logDelta = new LinkedHashMap<>();
        for (String name : fleet.serviceNames()) {
            logDelta.put(name, Math.max(0, logBytes(name) - LOG_BYTES_AT_SNAPSHOT.getOrDefault(name, 0L)));
        }

        FootprintReport report = new FootprintReport(PAYMENTS, tables, logDelta);
        addCaveats(report, elapsedMs, tables);

        // SLI proof BEFORE rendering: this appends its own notes to the report, and a note added
        // after render()/writeTo() would be silently absent from the file on disk.
        assertPaymentSlisExposed(report);

        String rendered = report.render();
        System.out.println("\n" + rendered);
        Path out = fleet.repoRoot().resolve("e2e-tests/build/footprint");
        report.writeTo(out);
        System.out.println("[footprint] written to " + out);

        // --- assertions: the run must actually have measured something ---
        assertTrue(!tables.isEmpty(),
                "no table grew during " + PAYMENTS + " payments — the probe is not seeing the "
                        + "databases the services write to, so every number would be a false zero");
        assertTrue(report.rowsPerTxn() >= 1.0,
                "fewer than one row per payment was recorded (" + report.rowsPerTxn() + ") — a "
                        + "successful payment must at minimum persist its transaction");
        assertTrue(report.logBytesPerTxn() > 0,
                "no log bytes were emitted during the measured window, which cannot be true for a "
                        + "real payment cascade — the log capture is broken");
    }

    /**
     * Scrapes the fleet's own {@code /actuator/prometheus} and asserts the payment-path SLIs
     * added for T3-5 are really being emitted.
     *
     * <p>Worth doing here rather than only in a unit test: a Micrometer meter can be perfectly
     * constructed and still never reach a scrape — the registry may be absent, the actuator
     * endpoint unexposed, or the optional SLI bean simply not injected. This run has just driven
     * {@code PAYMENTS} real payments through the real controllers, so if the series are present
     * now, the instrumentation genuinely works end to end.
     *
     * <p>The outbox lag gauge is checked on transaction-mgmt, the one service in this fleet that
     * owns an outbox table.
     */
    private static void assertPaymentSlisExposed(FootprintReport report) throws Exception {
        String executorScrape = scrape(PORT_PAYMENT_EXECUTOR);
        assertTrue(executorScrape.contains("gmepay_payment_duration_seconds_bucket"),
                "payment-executor must expose the payment latency HISTOGRAM (T3-5) after "
                        + PAYMENTS + " payments — an SLO cannot be computed without buckets. "
                        + "Scrape head: " + head(executorScrape));
        assertTrue(executorScrape.contains("gmepay_payment_outcome_total"),
                "payment-executor must expose the payment outcome counter (T3-5). Scrape head: "
                        + head(executorScrape));
        assertTrue(executorScrape.contains("entry=\"wallet_pay\""),
                "the wallet-pay entry point must be tagged as such. Scrape head: "
                        + head(executorScrape));
        report.note("VERIFIED on the live fleet: payment-executor exposes "
                + "gmepay_payment_duration_seconds (histogram) and gmepay_payment_outcome_total "
                + "tagged entry/outcome/reason, after " + PAYMENTS + " real payments.");

        String txnScrape = scrape(PORT_TXN_MGMT);
        if (txnScrape.contains("gmepay_outbox_pending")) {
            report.note("VERIFIED on the live fleet: transaction-mgmt exposes "
                    + "gmepay_outbox_pending and gmepay_outbox_oldest_pending_age_seconds "
                    + "(the queue/lag SLI, registered automatically by lib-errors).");
        } else {
            report.note("NOT VERIFIED: the outbox lag gauge did not appear in transaction-mgmt's "
                    + "scrape. It registers only when an 'outbox' table is present on the "
                    + "datasource; check whether the actuator endpoint is exposed on this fleet.");
        }
    }

    /**
     * Scrapes {@code /actuator/prometheus}, with its own generous timeout and a retry.
     *
     * <p>The fleet is launched with {@code spring.main.lazy-initialization=true}, so the FIRST
     * touch of the actuator endpoint builds the endpoint infrastructure and renders several
     * hundred series — comfortably slower than the 10-second timeout the shared helper uses for
     * ordinary API calls, and a timeout here would read as "the SLI is missing" when it is merely
     * cold. Two attempts, 30 seconds each.
     */
    private static String scrape(int port) {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        String lastFailure = "no attempt made";
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpResponse<String> response = client.send(
                        java.net.http.HttpRequest
                                .newBuilder(java.net.URI.create(
                                        "http://localhost:" + port + "/actuator/prometheus"))
                                .timeout(Duration.ofSeconds(30))
                                .header(SchemeFleet.INTERNAL_HEADER, SchemeFleet.INTERNAL_SECRET)
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return response.body();
                }
                lastFailure = "HTTP " + response.statusCode() + " " + response.body();
            } catch (Exception e) {
                lastFailure = "scrape failed: " + e;
            }
        }
        return lastFailure;
    }

    private static String head(String scrapeBody) {
        return scrapeBody.length() <= 400 ? scrapeBody : scrapeBody.substring(0, 400) + "...";
    }

    /** Records what this configuration could NOT measure, so the report never over-claims. */
    private static void addCaveats(FootprintReport report, long elapsedMs, List<TableFootprint> tables) {
        // A footprint run is also a functional observation: if a durable FAILURE sink grew once
        // per payment, the measured footprint contains error-path rows that a healthy deployment
        // would not write, and the reader must be told before they size anything on it.
        for (TableFootprint table : tables) {
            if (FAILURE_SINK_TABLES.contains(table.table()) && table.rowsAdded() > 0) {
                report.note("FINDING — `" + table.database() + "." + table.table() + "` grew by "
                        + String.format("%.2f", table.rowsAdded() / (double) PAYMENTS)
                        + " rows per payment. That is a durable FAILURE sink: something on the "
                        + "money path failed and was persisted for replay on every transaction. "
                        + "The footprint below therefore includes error-path rows. See the "
                        + "fleet logs in " + fleet.logDir() + " for the cause.");
            }
        }
        report.note("MEASURED: row counts, column payload widths and index definitions, read from "
                + "the services' own databases before and after " + PAYMENTS + " real payments "
                + "(wall clock " + elapsedMs + " ms).");
        report.note("DERIVED: heap/index/WAL bytes. The E2E fleet runs on H2 so that it needs no "
                + "Docker, so pg_total_relation_size and pg_current_wal_lsn do not exist here. "
                + "PostgresSizeModel converts the measured row shape into PostgreSQL 16 layout.");
        report.note("NOT MEASURED — Kafka: no broker runs in this fleet, so no topic bytes were "
                + "produced. The outbox rows below are the durable record of the same events and "
                + "ARE measured; Kafka's own copy is additional.");
        report.note("NOT MEASURED — prefunding (ledger_entry, cumulative_usage_ledger): not booted. "
                + "The domestic wallet path in this configuration does not reach it; the "
                + "authorize/confirm and cross-border paths do, and would add rows per payment.");
        report.note("NOT MEASURED — notification-webhook (webhook_delivery_log) and "
                + "settlement-reconciliation (settlement_lines): both are driven by Kafka "
                + "consumption or by a batch window, neither of which exists in this fleet.");
        report.note("NOT MEASURED — the hash-chained audit log (lib-audit) and the KYB vault: "
                + "outside the payment path measured here, and both carry retention constraints "
                + "that make them a separate sizing question (see CAPACITY_AND_SLA.md).");
        report.note("Log bytes are the fleet's real stdout at its DEFAULT log level under the E2E "
                + "profile. A production profile at a different level, or with structured JSON "
                + "output, will differ — this is a measurement of this configuration.");
    }

    // -------------------------------------------------------------------------
    // Driving a payment
    // -------------------------------------------------------------------------

    private static void payOrFail(String userRef) throws Exception {
        String body = """
                { "qrPayload": "%s", "amountKrw": "%s", "partner": "GMEREMIT", "userRef": "%s" }
                """.formatted(QR_ACTIVE, AMOUNT_KRW, userRef);
        HttpResponse<String> pay =
                SchemeFleet.post("http://localhost:" + PORT_PAYMENT_EXECUTOR + "/v1/pay", body);
        if (pay.statusCode() != 201) {
            fleet.dumpLogs();
            fail("payment '" + userRef + "' did not succeed (HTTP " + pay.statusCode()
                    + "). A footprint measured from declined payments would be meaningless. Body: "
                    + pay.body());
        }
        JsonNode receipt = JSON.readTree(pay.body());
        assertEquals("APPROVED", receipt.path("status").asText(),
                "only APPROVED payments may be counted. Body: " + pay.body());
    }

    /**
     * Lets the asynchronous tail of a payment land before measuring.
     *
     * <p>Several writes on this path are deliberately not in the request's transaction — the fee
     * journal is fire-and-forget, and transaction-mgmt's outbox publisher ticks on a 1-second
     * schedule. Measuring immediately after the last HTTP response would undercount them, which
     * would understate the footprint in exactly the direction that causes under-provisioning.
     */
    private static void settle() {
        SchemeFleet.sleep(8000);
    }

    private static long logBytes(String service) {
        Path log = fleet.logDir().resolve(service + ".log");
        try {
            return Files.exists(log) ? Files.size(log) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }
}
