package com.gme.pay.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end test: <b>a sending-partner wallet self-serves from sign-up to
 * ready-to-transact</b> — the loop-B onboarding funnel from
 * {@code docs/QR_HUB_GROWTH_FLYWHEEL.md} (tracker item #5).
 *
 * <p>Boots the real onboarding fleet as separate JVM processes (same harness style
 * as {@link WalletScanPayE2ETest}: H2 / in-memory, no Docker, no Kafka) and drives
 * the genuine funnel over HTTP:
 *
 * <pre>
 *   ① sign up   POST /v1/partners/draft            → config-registry  (status=ONBOARDING)
 *   ② KYB       POST /v1/partners/{code}/kyb/verify → config-registry (stub kyb-adapter seam)
 *   ③ keys      POST /internal/auth/keys            → auth-identity   (one-time SANDBOX secret)
 *   ④ prefund   POST /v1/prefunding/provision + credit → prefunding   (opening balance + top-up)
 * </pre>
 *
 * <p>The "first transaction" leg of the funnel is covered by
 * {@link WalletScanPayE2ETest}; together the two tests prove the whole
 * onboarding→transacting path a new wallet partner walks. Every step asserts its
 * own side effect independently (fresh GET after each write) — a green POST alone
 * proves nothing if the write didn't land.
 *
 * <p>KYB runs through config-registry's stub kyb-adapter client
 * ({@code gmepay.kyb-adapter.client} defaults to {@code stub},
 * {@code matchIfMissing=true}), so no kyb-adapter process is needed — the decision
 * rules are identical to the adapter's default wiring.
 *
 * <p>Tagged {@code @Tag("e2e")}: excluded from {@code ./gradlew build}; run with
 * {@code ./gradlew :e2e-tests:e2eTest}.
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("E2E: partner self-service onboarding funnel (sign up -> KYB -> keys -> prefund)")
class PartnerOnboardingE2ETest {

    private static final String PARTNER_CODE = "E2E_WALLET_01";
    private static final BigDecimal OPENING_USD = new BigDecimal("10000.00");
    private static final BigDecimal TOPUP_USD = new BigDecimal("5000.00");

    // Distinct ports so this fleet can never clash with the wallet-scan fleet's.
    private static final int PORT_CONFIG_REGISTRY = 18086;
    private static final int PORT_AUTH_IDENTITY = 18085;
    private static final int PORT_PREFUNDING = 18087;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Path repoRoot;
    private static Path logDir;
    private static final List<Service> FLEET = new ArrayList<>();

    /** The partner's BIGINT surrogate id, captured at sign-up and used for key issue. */
    private static long partnerId;

    private record Service(String name, Process process, int port) {}

    // -------------------------------------------------------------------------
    // Fleet lifecycle
    // -------------------------------------------------------------------------

    @BeforeAll
    static void bootFleet() throws Exception {
        repoRoot = findRepoRoot();
        logDir = repoRoot.resolve("e2e-tests/build/e2e-logs");
        Files.createDirectories(logDir);

        // Gap T1-1: config-registry's auth-identity client now defaults to `rest` (the stub
        // that fabricated unverifiable credentials is opt-in only), so this fleet must point
        // it at the local auth-identity instead of the compose hostname auth-identity:8080.
        // notification-webhook is NOT in this fleet (it needs Kafka) and this funnel saves no
        // step-8 webhook draft, so its client is explicitly parked on the stub.
        launchService("config-registry", PORT_CONFIG_REGISTRY,
                "--gmepay.auth-identity.client=rest",
                "--gmepay.auth-identity.base-url=http://localhost:" + PORT_AUTH_IDENTITY,
                "--gmepay.notification-webhook.client=stub");
        launchService("auth-identity", PORT_AUTH_IDENTITY);
        launchService("prefunding", PORT_PREFUNDING);

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
    // The funnel
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("① sign up: create partner draft -> ONBOARDING, read-back confirms persistence")
    void signUpCreatesDraft() throws Exception {
        String body = """
                { "partnerCode": "%s", "type": "OVERSEAS",
                  "settlementCurrency": "USD", "settlementRoundingMode": "HALF_UP",
                  "legalNameRomanized": "E2E Wallet Co., Ltd.",
                  "countryOfIncorporation": "NP" }
                """.formatted(PARTNER_CODE);
        HttpResponse<String> created = post(
                configRegistry("/v1/partners/draft"), body, "X-Actor", "e2e-onboarding");
        assertEquals(201, created.statusCode(),
                "draft create should return 201. Body: " + created.body());
        JsonNode view = JSON.readTree(created.body());
        assertEquals(PARTNER_CODE, view.path("partnerCode").asText());
        assertEquals("ONBOARDING", view.path("status").asText(),
                "a fresh draft must land in ONBOARDING. Body: " + created.body());
        assertTrue(view.path("id").canConvertToLong() && view.path("id").asLong() > 0,
                "draft must carry the surrogate id. Body: " + created.body());
        partnerId = view.path("id").asLong();

        // Independent side-effect assertion: the draft really persisted.
        HttpResponse<String> readBack = get(configRegistry("/v1/partners/draft/" + PARTNER_CODE));
        assertEquals(200, readBack.statusCode(),
                "draft read-back must find the row. Body: " + readBack.body());
        assertEquals("ONBOARDING", JSON.readTree(readBack.body()).path("status").asText());
    }

    @Test
    @Order(2)
    @DisplayName("② KYB: full verification via the stub adapter seam stores a decision")
    void kybVerificationStoresDecision() throws Exception {
        String body = """
                { "suppliedDocuments": ["BUSINESS_REGISTRATION", "LICENSE", "UBO_DECLARATION"],
                  "force": true }
                """;
        HttpResponse<String> verify = post(
                configRegistry("/v1/partners/" + PARTNER_CODE + "/kyb/verify"),
                body, "X-Actor", "e2e-compliance");
        assertEquals(200, verify.statusCode(),
                "KYB verify should succeed via the stub seam. Body: " + verify.body());

        // Independent side-effect assertion: the decision landed on the KYB row.
        HttpResponse<String> kyb = get(configRegistry("/v1/partners/" + PARTNER_CODE + "/kyb"));
        assertEquals(200, kyb.statusCode(), "KYB read-back. Body: " + kyb.body());
        JsonNode kybView = JSON.readTree(kyb.body());
        String screening = kybView.path("screeningStatus").asText("");
        String decision = kybView.path("verificationDecision").asText("");
        assertTrue(!screening.isBlank() || !decision.isBlank(),
                "verification must store a screening status or decision. Body: " + kyb.body());
    }

    @Test
    @Order(3)
    @DisplayName("③ keys: issue a SANDBOX API key -> one-time secret; list shows metadata only")
    void sandboxKeyIssued() throws Exception {
        String body = """
                { "partnerId": %d, "partnerCode": "%s",
                  "environment": "SANDBOX", "purpose": "API",
                  "keyPrefix": "pk_test_", "secretPrefix": "sk_test_" }
                """.formatted(partnerId, PARTNER_CODE);
        HttpResponse<String> issued = post(authIdentity("/internal/auth/keys"), body);
        assertEquals(200, issued.statusCode(),
                "key issue should return 200. Body: " + issued.body());
        JsonNode key = JSON.readTree(issued.body());
        assertFalse(key.path("keyId").asText("").isBlank(), "keyId. Body: " + issued.body());
        assertFalse(key.path("secretPlaintext").asText("").isBlank(),
                "the one-time plaintext secret must be returned exactly once. Body: " + issued.body());
        assertEquals("SANDBOX", key.path("environment").asText());

        // Independent side-effect assertion: the key lists back as metadata WITHOUT the secret.
        HttpResponse<String> list = get(authIdentity(
                "/internal/auth/keys?partnerId=" + partnerId + "&environment=SANDBOX"));
        assertEquals(200, list.statusCode(), "key list read-back. Body: " + list.body());
        JsonNode items = JSON.readTree(list.body());
        assertTrue(items.isArray() && items.size() >= 1,
                "issued key must list back. Body: " + list.body());
        assertFalse(list.body().contains(key.path("secretPlaintext").asText()),
                "the plaintext secret must never be re-exposed by the list endpoint (SEC-09)");
    }

    @Test
    @Order(4)
    @DisplayName("④ prefund: provision opening balance, top up, balance reflects both")
    void prefundProvisionAndTopUp() throws Exception {
        String provisionBody = """
                { "partnerCode": "%s", "openingBalanceUsd": "%s",
                  "lowBalanceThresholdUsd": "1000.00" }
                """.formatted(PARTNER_CODE, OPENING_USD.toPlainString());
        HttpResponse<String> provisioned = post(prefunding("/v1/prefunding/provision"), provisionBody);
        assertEquals(201, provisioned.statusCode(),
                "provision should return 201. Body: " + provisioned.body());

        String creditBody = "{ \"amount\": \"" + TOPUP_USD.toPlainString() + "\" }";
        HttpResponse<String> credited = post(
                prefunding("/v1/prefunding/" + PARTNER_CODE + "/credit"), creditBody);
        assertEquals(200, credited.statusCode(),
                "top-up credit should return 200. Body: " + credited.body());

        // Independent side-effect assertion: the balance really is opening + top-up.
        HttpResponse<String> balance = get(
                prefunding("/v1/prefunding/" + PARTNER_CODE + "/balance"));
        assertEquals(200, balance.statusCode(), "balance read-back. Body: " + balance.body());
        JsonNode view = JSON.readTree(balance.body());
        BigDecimal expected = OPENING_USD.add(TOPUP_USD);
        assertEquals(0, expected.compareTo(new BigDecimal(view.path("balance").asText())),
                "balance must equal opening + top-up. Body: " + balance.body());
    }

    // -------------------------------------------------------------------------
    // Process / readiness helpers (same pattern as WalletScanPayE2ETest)
    // -------------------------------------------------------------------------

    private static String configRegistry(String path) {
        return "http://localhost:" + PORT_CONFIG_REGISTRY + path;
    }

    private static String authIdentity(String path) {
        return "http://localhost:" + PORT_AUTH_IDENTITY + path;
    }

    private static String prefunding(String path) {
        return "http://localhost:" + PORT_PREFUNDING + path;
    }

    private static void launchService(String name, int port, String... extraArgs) throws IOException {
        Path jar = resolveJar(repoRoot.resolve("services").resolve(name).resolve("build/libs"), name);
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        List<String> cmd = new ArrayList<>(List.of(
                javaBin,
                "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xss512k",
                "-Xmx256m", "-XX:MaxMetaspaceSize=160m",
                "-Dspring.main.lazy-initialization=true",
                "-jar", jar.toString(),
                "--server.port=" + port,
                "--spring.application.name=" + name));
        cmd.addAll(List.of(extraArgs));

        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(logDir.resolve(name + ".log").toFile())
                .start();
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
            return r.statusCode() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Path resolveJar(Path libsDir, String name) {
        if (!Files.isDirectory(libsDir)) {
            fail("Boot jar dir not found: " + libsDir
                    + " — ./gradlew :e2e-tests:e2eTest builds the service jars first.");
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

    private static HttpResponse<String> get(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String url, String json, String... headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json");
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(builder.POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
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
