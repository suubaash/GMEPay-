package com.gme.pay.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end test: <b>a wallet scans a merchant QR and the payment succeeds</b>.
 *
 * <p>This is the test that did not exist before. Unlike {@code WalletPayControllerTest}
 * (which mocks every downstream service), this harness boots the <b>real</b> money-path
 * fleet as separate JVM processes and exercises the genuine cross-service cascade:
 *
 * <pre>
 *   wallet ──POST /v1/pay──▶ payment-executor
 *      → GET /v1/merchants/{qr}            → merchant-qr-data   (resolve + ACTIVE check)
 *      → POST /internal/scheme/zeropay/... → scheme-adapter-zeropay → sim-scheme (jeonmun)
 *      → POST /v1/transactions (+PATCH)    → transaction-mgmt   (persist APPROVED)
 *      → POST /v1/journals/rounding-...    → revenue-ledger     (book ₩500 fee)
 * </pre>
 *
 * <p><b>Why it boots subprocesses.</b> Each microservice is its own Spring Boot
 * application; they cannot share one JVM context. So the harness launches the boot jars
 * with distinct ports and points payment-executor's downstream base-URLs at them. Every
 * service runs on its H2 / in-memory fallback — <b>no Docker, no Kafka, no Mongo</b>.
 *
 * <p><b>Why it asserts side effects independently.</b> The production GME-Remit path
 * treats transaction-mgmt and revenue-ledger as non-blocking (it logs and continues on
 * failure). A green {@code APPROVED} receipt therefore does NOT by itself prove those
 * downstream writes happened. So after the pay returns APPROVED this test independently
 * queries transaction-mgmt and asserts the transaction was actually persisted as
 * APPROVED — if the hub had silently swallowed that write, this test fails.
 *
 * <p><b>Negative control.</b> A second test pays against a DEACTIVATED merchant and
 * asserts a 422 DECLINED / MERCHANT_INACTIVE, proving the merchant-validation leg is
 * genuinely wired (not a stub that approves everything).
 *
 * <p>Tagged {@code @Tag("e2e")} so it is excluded from {@code ./gradlew build}. Run it
 * with {@code ./gradlew :e2e-tests:e2eTest}. Requires ~2 GB free RAM for the 6 JVMs.
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("E2E: wallet scans QR -> domestic GME-Remit payment succeeds")
class WalletScanPayE2ETest {

    // ---- seed data (merchant-qr-data InMemoryMerchantRepository) ----
    // A *full EMVCo static QR* (valid tag-63 CRC) for M0000000001. The short codes in the
    // seed resolve in merchant-qr-data but fail sim-scheme's CRC check at /qr/decode, so the
    // end-to-end MPM path (which round-trips through sim-scheme) needs this real payload.
    private static final String QR_ACTIVE =
            "00020101021129260011com.zeropay0107ZP-M0015204581253034105802KR5918Seoul Noodle House6005Seoul63040B08";
    private static final String MERCHANT_ID = "M0000000001";
    private static final String MERCHANT_NAME = "Seoul Noodle House";
    private static final String QR_DEACTIVATED = "QR00000000000000004D"; // -> "Closed Corner Store" DEACTIVATED

    private static final BigDecimal AMOUNT_KRW = new BigDecimal("50000");
    private static final BigDecimal FEE_KRW = new BigDecimal("500");

    // ---- fleet ports (merchant-qr-data + transaction-mgmt both default to 8083, so we
    //      override them to distinct ports; payment-executor is pointed at these) ----
    private static final int PORT_PAYMENT_EXECUTOR = 8084;   // jar default
    private static final int PORT_SCHEME_ADAPTER   = 8090;   // jar default
    private static final int PORT_SIM_SCHEME       = 9102;   // sim default (scheme-adapter targets this)
    private static final int PORT_REVENUE_LEDGER   = 8085;   // jar default
    private static final int PORT_MERCHANT_QR      = 18083;  // overridden (default 8083 clashes)
    private static final int PORT_TXN_MGMT         = 18082;  // overridden (default 8083 clashes)
    private static final int PORT_CONFIG_REGISTRY  = 18081;  // overridden (avoid default clashes)
    private static final int PORT_SCHEME_NEPAL     = 8092;   // jar default
    private static final int PORT_SIM_NEPAL        = 9103;   // sim default (Nepal adapter targets this)

    // Canonical Fonepay MPM QR (same payload the unit suites classify with): network
    // fonepay.com, country NP. Classifies NEPAL → FixtureSmartRouterClient resolves the
    // NEPAL adapter — the corridor whose 3 stacked defects shipped silently on 2-3 Jul.
    private static final String QR_FONEPAY =
            "00020101021126150011fonepay.com5802NP5910KINAUN PVT6304ABCD";
    private static final BigDecimal AMOUNT_NPR = new BigDecimal("1300");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Path repoRoot;
    private static Path logDir;
    private static final List<Service> FLEET = new ArrayList<>();

    private record Service(String name, Process process, int port) {}

    // -------------------------------------------------------------------------
    // Fleet lifecycle
    // -------------------------------------------------------------------------

    @BeforeAll
    static void bootFleet() throws Exception {
        repoRoot = findRepoRoot();
        logDir = repoRoot.resolve("e2e-tests/build/e2e-logs");
        Files.createDirectories(logDir);

        ensureSimJar("sim-scheme");
        ensureSimJar("sim-nepal-qr");

        // (name, port, extra Spring args). Downstream services first; payment-executor last.
        // config-registry is REQUIRED since the fail-CLOSED operational kill-switch (defect #4
        // fix): payment-executor declines every payment with SYSTEM_PAUSED when it cannot read
        // /v1/ops/operational-status. Booting the real registry keeps prod semantics in the
        // golden path instead of weakening the gate with fail-open=true.
        launchService("config-registry", PORT_CONFIG_REGISTRY);
        launchService("merchant-qr-data", PORT_MERCHANT_QR);
        launchService("transaction-mgmt", PORT_TXN_MGMT);
        launchService("revenue-ledger", PORT_REVENUE_LEDGER);
        launchService("scheme-adapter-zeropay", PORT_SCHEME_ADAPTER);
        launchSim("sim-scheme", PORT_SIM_SCHEME, "--gmepay.sim.scheme.profile=ZEROPAY");
        // Nepal corridor (cross-border leg): sim first, then the adapter pointed at it.
        launchSim("sim-nepal-qr", PORT_SIM_NEPAL);
        launchService("scheme-adapter-nepal", PORT_SCHEME_NEPAL,
                "--gmepay.scheme.nepal.base-url=http://localhost:" + PORT_SIM_NEPAL);
        launchService("payment-executor", PORT_PAYMENT_EXECUTOR,
                "--gmepay.merchant-qr-data.base-url=http://localhost:" + PORT_MERCHANT_QR,
                "--gmepay.scheme-adapter-zeropay.base-url=http://localhost:" + PORT_SCHEME_ADAPTER,
                "--gmepay.scheme-adapters.NEPAL.base-url=http://localhost:" + PORT_SCHEME_NEPAL,
                "--gmepay.transaction-mgmt.base-url=http://localhost:" + PORT_TXN_MGMT,
                "--gmepay.revenue-ledger.base-url=http://localhost:" + PORT_REVENUE_LEDGER,
                "--gmepay.config-registry.base-url=http://localhost:" + PORT_CONFIG_REGISTRY,
                "--gmepay.payment.merchant-validation=strict");

        waitForFleet(Duration.ofSeconds(180));
    }

    @AfterAll
    static void stopFleet() {
        for (Service s : FLEET) {
            if (s.process() != null && s.process().isAlive()) {
                s.process().descendants().forEach(ProcessHandle::destroyForcibly);
                s.process().destroyForcibly();
            }
        }
    }

    // -------------------------------------------------------------------------
    // The tests
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("happy path: scan ACTIVE merchant QR -> APPROVED + transaction persisted")
    void walletScansQrAndPaysSuccessfully() throws Exception {
        // --- 1) SCAN: the wallet resolves the QR it scanned (what the hub does internally). ---
        // The EMVCo payload contains spaces, so URL-encode it into the path segment.
        String encodedQr = URLEncoder.encode(QR_ACTIVE, StandardCharsets.UTF_8).replace("+", "%20");
        HttpResponse<String> scan = get("http://localhost:" + PORT_MERCHANT_QR + "/v1/merchants/" + encodedQr);
        assertEquals(200, scan.statusCode(), "merchant-qr-data should resolve the scanned QR. Body: " + scan.body());
        JsonNode merchant = JSON.readTree(scan.body());
        boolean active = merchant.path("active").asBoolean(false)
                || "ACTIVE".equalsIgnoreCase(merchant.path("status").asText(""));
        assertTrue(active, "scanned merchant must be ACTIVE before pay. Body: " + scan.body());
        assertEquals(MERCHANT_NAME, merchant.path("merchantName").asText(), "merchant name from scan");

        // --- 2) PAY: the wallet posts the scanned payload to the hub (POST /v1/pay). ---
        String payBody = """
                { "qrPayload": "%s", "amountKrw": "%s", "partner": "GMEREMIT", "userRef": "e2e-user-001" }
                """.formatted(QR_ACTIVE, AMOUNT_KRW.toPlainString());
        HttpResponse<String> pay = post("http://localhost:" + PORT_PAYMENT_EXECUTOR + "/v1/pay", payBody);

        assertEquals(201, pay.statusCode(),
                "POST /v1/pay should return 201 CREATED for an approved payment. Body: " + pay.body());
        JsonNode receipt = JSON.readTree(pay.body());
        assertEquals("APPROVED", receipt.path("status").asText(), "payment status. Body: " + pay.body());
        assertTrue(receipt.path("schemeTxnRef").asText("").startsWith("TXN"),
                "scheme must return a committed TXN ref (proves scheme-adapter -> sim-scheme round-trip). Body: " + pay.body());
        assertEquals(MERCHANT_NAME, receipt.path("merchantName").asText());
        assertEquals(0, AMOUNT_KRW.compareTo(bd(receipt, "payAmountKrw")), "pay amount");
        assertEquals(0, FEE_KRW.compareTo(bd(receipt, "feeKrw")), "fixed ₩500 service fee");
        assertEquals(0, AMOUNT_KRW.add(FEE_KRW).compareTo(bd(receipt, "chargedKrw")), "charged = amount + fee");

        // --- 3) INDEPENDENT SIDE-EFFECT ASSERTION: the transaction was really persisted. ---
        // The hub swallows transaction-mgmt failures, so we verify the write actually landed.
        assertApprovedTransactionPersisted();

        // --- 4) LEDGER TIE-OUT: the ₩500 fee journal really posted (also fire-and-forget). ---
        // This closes the harness's original known limitation — revenue-ledger now has a
        // read API (GET /v1/journals?reference=), so "code exists" becomes "tied to the won".
        assertFeeJournalPosted(receipt.path("txnRef").asText());
    }

    /**
     * Asserts revenue-ledger holds a BALANCED journal for the payment's fee booking: the hub
     * posts the ₩500 service fee under the payment's {@code txnRef} (fire-and-forget, so a
     * green receipt alone proves nothing) — this queries the ledger's read API independently.
     */
    private void assertFeeJournalPosted(String txnRef) throws Exception {
        assertTrue(txnRef != null && !txnRef.isBlank(), "receipt must carry the hub txnRef");
        Instant deadline = Instant.now().plusSeconds(15);
        String lastBody = "";
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> resp = get("http://localhost:" + PORT_REVENUE_LEDGER
                    + "/v1/journals?reference=" + URLEncoder.encode(txnRef, StandardCharsets.UTF_8));
            if (resp.statusCode() == 200) {
                lastBody = resp.body();
                for (JsonNode journal : JSON.readTree(resp.body()).path("items")) {
                    BigDecimal debits = BigDecimal.ZERO;
                    BigDecimal credits = BigDecimal.ZERO;
                    boolean feeAmountSeen = false;
                    for (JsonNode line : journal.path("lines")) {
                        BigDecimal amount = new BigDecimal(line.path("amount").asText("0"));
                        String side = line.path("side").asText("");
                        if (side.startsWith("D")) debits = debits.add(amount); else credits = credits.add(amount);
                        feeAmountSeen |= FEE_KRW.compareTo(amount) == 0;
                    }
                    if (feeAmountSeen && debits.compareTo(credits) == 0 && debits.signum() > 0) {
                        return; // a balanced ₩500 journal exists for this payment
                    }
                }
            } else {
                lastBody = "HTTP " + resp.statusCode() + " " + resp.body();
            }
            Thread.sleep(1000);
        }
        fail("No balanced ₩" + FEE_KRW + " journal found in revenue-ledger for reference " + txnRef
                + ". The fee posting is fire-and-forget in the hub, so the receipt cannot prove it "
                + "— and it didn't land. Last /v1/journals response: " + lastBody);
    }

    @Test
    @Order(2)
    @DisplayName("negative control: scan DEACTIVATED merchant QR -> 422 DECLINED / MERCHANT_INACTIVE")
    void walletPayDeclinedForDeactivatedMerchant() throws Exception {
        String payBody = """
                { "qrPayload": "%s", "amountKrw": "10000", "partner": "GMEREMIT", "userRef": "e2e-user-002" }
                """.formatted(QR_DEACTIVATED);
        HttpResponse<String> pay = post("http://localhost:" + PORT_PAYMENT_EXECUTOR + "/v1/pay", payBody);

        assertEquals(422, pay.statusCode(),
                "a DEACTIVATED merchant must be declined with 422. Body: " + pay.body());
        JsonNode body = JSON.readTree(pay.body());
        assertEquals("DECLINED", body.path("status").asText(), "Body: " + pay.body());
        assertEquals("MERCHANT_INACTIVE", body.path("declineReason").asText(), "Body: " + pay.body());
    }

    /** Polls transaction-mgmt for an APPROVED transaction for our merchant + amount. */
    @Test
    @Order(3)
    @DisplayName("Nepal corridor: hub classifies Fonepay QR as NPR, pay executes via NEPAL adapter -> APPROVED")
    void walletScansFonepayQrAndPaysInNpr() throws Exception {
        // --- 1) CLASSIFY: GMEPay+ (not the wallet) decides what the scanned QR is. This is the
        // hub-authoritative boundary fixed on 2 Jul — the wallet previously guessed the currency.
        String classifyBody = """
                { "qrPayload": "%s" }
                """.formatted(QR_FONEPAY);
        HttpResponse<String> classify =
                post("http://localhost:" + PORT_PAYMENT_EXECUTOR + "/v1/pay/classify", classifyBody);
        assertEquals(200, classify.statusCode(),
                "POST /v1/pay/classify must classify a Fonepay QR. Body: " + classify.body());
        JsonNode c = JSON.readTree(classify.body());
        assertTrue(c.path("supported").asBoolean(false), "Fonepay QR must be supported. Body: " + classify.body());
        assertEquals("NPR", c.path("currency").asText(), "hub-authoritative currency. Body: " + classify.body());
        assertEquals("NP", c.path("country").asText(), "classified country. Body: " + classify.body());

        // --- 2) PAY in the classified currency through the real NEPAL adapter -> sim-nepal-qr. ---
        String payBody = """
                { "qrPayload": "%s", "amountKrw": "%s", "partner": "GMEREMIT",
                  "userRef": "e2e-user-np-1", "currency": "NPR" }
                """.formatted(QR_FONEPAY, AMOUNT_NPR.toPlainString());
        HttpResponse<String> pay = post("http://localhost:" + PORT_PAYMENT_EXECUTOR + "/v1/pay", payBody);

        assertEquals(201, pay.statusCode(),
                "POST /v1/pay must APPROVE a Fonepay QR payment through the NEPAL adapter "
                        + "(this exact journey silently declined 100% of payments until 3 Jul). Body: "
                        + pay.body());
        JsonNode receipt = JSON.readTree(pay.body());
        assertEquals("APPROVED", receipt.path("status").asText(), "Nepal payment status. Body: " + pay.body());
        assertEquals("NPR", receipt.path("payCurrency").asText(), "receipt currency. Body: " + pay.body());
        assertEquals(0, AMOUNT_NPR.compareTo(bd(receipt, "payAmount")), "NPR amount echoed on receipt");
        assertTrue(!receipt.path("schemeTxnRef").asText("").isBlank(),
                "scheme txn ref proves the adapter -> sim-nepal-qr round-trip. Body: " + pay.body());
    }

    private void assertApprovedTransactionPersisted() throws Exception {
        Instant deadline = Instant.now().plusSeconds(15);
        String lastBody = "";
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> resp = get(
                    "http://localhost:" + PORT_TXN_MGMT + "/v1/transactions?status=APPROVED&size=200");
            if (resp.statusCode() == 200) {
                lastBody = resp.body();
                for (JsonNode txn : JSON.readTree(resp.body()).path("content")) {
                    boolean merchantMatches = MERCHANT_ID.equals(txn.path("merchantId").asText());
                    boolean amountMatches = txn.hasNonNull("krwAmount")
                            && AMOUNT_KRW.compareTo(new BigDecimal(txn.get("krwAmount").asText())) == 0;
                    if (merchantMatches && amountMatches
                            && "APPROVED".equals(txn.path("status").asText())) {
                        return; // success — the cross-service write genuinely happened
                    }
                }
            } else {
                lastBody = "HTTP " + resp.statusCode() + " " + resp.body();
            }
            Thread.sleep(1000);
        }
        fail("No APPROVED transaction for merchant " + MERCHANT_ID + " / ₩" + AMOUNT_KRW
                + " found in transaction-mgmt. The hub returned APPROVED but the transaction write "
                + "did not land (it is swallowed in production code — this is the leak this test guards). "
                + "Last transaction-mgmt response: " + lastBody);
    }

    // -------------------------------------------------------------------------
    // Process / readiness helpers
    // -------------------------------------------------------------------------

    private static void launchService(String name, int port, String... extraArgs) throws IOException {
        Path jar = resolveJar(repoRoot.resolve("services").resolve(name).resolve("build/libs"), name);
        launch(name, jar, port, false, extraArgs);
    }

    private static void launchSim(String name, int port, String... extraArgs) throws IOException {
        Path jar = resolveJar(repoRoot.resolve("simulators").resolve(name).resolve("build/libs"), name);
        launch(name, jar, port, true, extraArgs);
    }

    private static void launch(String name, Path jar, int port, boolean sim, String... extraArgs) throws IOException {
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        List<String> cmd = new ArrayList<>(List.of(
                javaBin,
                "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xss512k",
                "-Xmx" + (sim ? "160m" : "256m"), "-XX:MaxMetaspaceSize=160m",
                "-Dspring.main.lazy-initialization=true",
                "-jar", jar.toString(),
                "--server.port=" + port,
                "--spring.application.name=" + name));
        cmd.addAll(List.of(extraArgs));

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(logDir.resolve(name + ".log").toFile());
        // T0-2 / T0-5: scheme-adapter-zeropay in this fleet refuses to START without the
        // internal-auth secret, and payment-executor's RestSchemeClient must present the SAME value
        // on every /internal/scheme/zeropay/** authorize+commit or the golden path 401s.
        pb.environment().putAll(SchemeFleet.INTERNAL_AUTH_ENV);
        Process p = pb.start();
        FLEET.add(new Service(name, p, port));
    }

    private static void waitForFleet(Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        List<Service> pending = new ArrayList<>(FLEET);
        while (!pending.isEmpty() && Instant.now().isBefore(deadline)) {
            pending.removeIf(s -> isUp(s.port()));
            for (Service s : pending) {
                if (s.process() != null && !s.process().isAlive()) {
                    dumpAllLogs();
                    fail("Service '" + s.name() + "' exited during startup (exit="
                            + s.process().exitValue() + "). See logs in " + logDir);
                }
            }
            if (pending.isEmpty()) break;
            sleep(3000);
        }
        if (!pending.isEmpty()) {
            dumpAllLogs();
            fail("Fleet did not come up within " + timeout.toSeconds() + "s. Still down: "
                    + pending.stream().map(s -> s.name() + ":" + s.port()).toList()
                    + ". Logs in " + logDir);
        }
    }

    /** "Up" = the port answers HTTP with any status (matches run-fleet.ps1's heuristic). */
    private static boolean isUp(int port) {
        try {
            HttpResponse<Void> r = HTTP.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/_probe"))
                            .timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return r.statusCode() > 0; // any HTTP response means the server is listening
        } catch (Exception e) {
            return false; // connection refused / not yet listening
        }
    }

    // -------------------------------------------------------------------------
    // Jar resolution + sim build
    // -------------------------------------------------------------------------

    private static Path resolveJar(Path libsDir, String name) {
        if (!Files.isDirectory(libsDir)) {
            fail("Boot jar dir not found: " + libsDir + " — build it first (./gradlew :e2e-tests:e2eTest "
                    + "builds the service jars; sim-scheme is built automatically).");
        }
        try (Stream<Path> files = Files.list(libsDir)) {
            return files
                    .filter(p -> {
                        String f = p.getFileName().toString();
                        return f.startsWith(name + "-") && f.endsWith(".jar") && !f.contains("plain");
                    })
                    .findFirst()
                    .orElseGet(() -> {
                        fail("No boot jar matching '" + name + "-*.jar' in " + libsDir);
                        return null;
                    });
        } catch (IOException e) {
            fail("Could not list " + libsDir + ": " + e.getMessage());
            return null;
        }
    }

    /** Simulators live in the separate `simulators` Gradle build; build a sim's jar on demand. */
    private static void ensureSimJar(String name) throws Exception {
        Path libs = repoRoot.resolve("simulators/" + name + "/build/libs");
        if (Files.isDirectory(libs)) {
            try (Stream<Path> f = Files.list(libs)) {
                if (f.anyMatch(p -> p.getFileName().toString().matches(name + "-.*\\.jar")
                        && !p.getFileName().toString().contains("plain"))) {
                    return; // already built
                }
            }
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String wrapper = windows ? "gradlew.bat" : "./gradlew";
        List<String> cmd = List.of(wrapper, "-p", "simulators/" + name, "bootJar", "--no-daemon", "-q");
        System.out.println("[e2e] building " + name + " jar: " + String.join(" ", cmd));
        Process build = new ProcessBuilder(cmd)
                .directory(repoRoot.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logDir.resolve(name + "-build.log").toFile())
                .start();
        if (!build.waitFor(8, java.util.concurrent.TimeUnit.MINUTES) || build.exitValue() != 0) {
            build.destroyForcibly();
            fail("Failed to build " + name + " jar — see " + logDir.resolve(name + "-build.log"));
        }
    }

    private static Path findRepoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("settings.gradle"))) return p;
        }
        fail("Could not locate repo root (no settings.gradle above " + dir + ")");
        return null;
    }

    // -------------------------------------------------------------------------
    // Tiny HTTP + misc helpers
    // -------------------------------------------------------------------------

    // Both helpers always present X-Gme-Internal (T0-2 / T0-5) so any gated route this suite touches
    // directly (scheme-adapter-zeropay's /internal/**, transaction-mgmt introspection) is reachable.
    // Ignored on the ungated payment/merchant routes, so it is safe to send unconditionally.
    private static HttpResponse<String> get(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header(SchemeFleet.INTERNAL_HEADER, SchemeFleet.INTERNAL_SECRET)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String url, String json) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .header(SchemeFleet.INTERNAL_HEADER, SchemeFleet.INTERNAL_SECRET)
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Reads a JSON field that may be encoded as a number or a quoted string into a BigDecimal. */
    private static BigDecimal bd(JsonNode node, String field) {
        return new BigDecimal(node.path(field).asText());
    }

    private static void dumpAllLogs() {
        for (Service s : FLEET) {
            Path log = logDir.resolve(s.name() + ".log");
            if (!Files.exists(log)) continue;
            try {
                List<String> lines = Files.readAllLines(log);
                int from = Math.max(0, lines.size() - 40);
                System.out.println("\n===== last " + (lines.size() - from) + " lines of " + s.name() + ".log =====");
                lines.subList(from, lines.size()).forEach(System.out::println);
            } catch (IOException ignored) {
                // best-effort diagnostics only
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
