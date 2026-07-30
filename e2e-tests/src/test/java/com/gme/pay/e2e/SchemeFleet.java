package com.gme.pay.e2e;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
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
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Minimal subprocess fleet harness for <b>adapter ↔ simulator pair</b> E2E tests
 * (SendMN, 9Pay — Phase 4 of {@code Documentation/schemes/QR_SCHEME_ACCOMMODATION_PLAN.md}).
 *
 * <p>Same boot pattern as {@link WalletScanPayE2ETest} (boot jars as detached JVMs on H2 /
 * in-memory, readiness = the port answers HTTP), extracted so scheme-pair tests can boot a
 * two-process fleet with per-process <b>environment variables</b> (the 9Pay pair exchanges
 * RSA PEM key material, which cannot ride on a Windows command line).</p>
 *
 * <p>Teardown is strict: {@link #shutdown()} force-kills every process (and descendants)
 * and then <b>verifies the ports actually closed</b> — a zombie JVM fails the run loudly
 * instead of poisoning the next one.</p>
 */
public final class SchemeFleet {

    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /**
     * Shared service-to-service internal-auth token for every E2E fleet in this module (T0-2 / T0-5).
     *
     * <p>Two things now depend on it. (1) <b>Boot:</b> {@code auth-identity}, {@code prefunding},
     * {@code scheme-adapter-zeropay} and {@code rate-fx} REFUSE TO START with a blank
     * {@code GMEPAY_INTERNAL_AUTH_SECRET}, so any fleet that launches one of them must export it or
     * the process exits during startup. (2) <b>Calls:</b> the tests drive gated surfaces directly
     * ({@code /internal/**}, {@code /v1/prefunding/**}), so every request needs the
     * {@code X-Gme-Internal} header — hence {@link #INTERNAL_HEADER} on the helpers below.
     *
     * <p>This is a <b>test fixture, not a credential</b>: it lives only in the test source set, is
     * never packaged, and is deliberately self-describing so it cannot be mistaken for a deployment
     * value. Sims get it too; they ignore it.
     */
    public static final String INTERNAL_SECRET = "e2e-fixture-internal-token-not-a-deployment-secret";

    /** The header name the {@code com.gme.pay.internalauth} gate reads (kept literal: e2e-tests does not depend on lib-errors). */
    public static final String INTERNAL_HEADER = "X-Gme-Internal";

    /** Env every launched component receives, so a gated service boots and its callers can reach it. */
    public static final Map<String, String> INTERNAL_AUTH_ENV =
            Map.of("GMEPAY_INTERNAL_AUTH_SECRET", INTERNAL_SECRET);

    private final Path repoRoot;
    private final Path logDir;
    private final List<Service> fleet = new ArrayList<>();

    private record Service(String name, Process process, int port) {}

    public SchemeFleet(String logSubdir) {
        this.repoRoot = findRepoRoot();
        this.logDir = repoRoot.resolve("e2e-tests/build/" + logSubdir);
        try {
            Files.createDirectories(logDir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create log dir " + logDir, e);
        }
    }

    public Path repoRoot() {
        return repoRoot;
    }

    /**
     * Directory holding one {@code <service>.log} per launched process.
     *
     * <p>Exposed for {@code footprint.PerTxnFootprintE2ETest}, which measures the bytes of log a
     * payment actually emits — every process is launched with {@code redirectErrorStream(true)}
     * into this directory, so the file size IS the service's emitted stdout+stderr volume.
     */
    public Path logDir() {
        return logDir;
    }

    /** Names of the launched components, in launch order. */
    public List<String> serviceNames() {
        return fleet.stream().map(Service::name).toList();
    }

    // -------------------------------------------------------------------------
    // Boot
    // -------------------------------------------------------------------------

    /** Fails fast when a port is already bound (a zombie from a previous run would corrupt the test). */
    public void assertPortsFree(int... ports) {
        for (int port : ports) {
            if (portListening(port)) {
                fail("Port " + port + " is already in use before fleet boot — kill the stale "
                        + "process first (netstat -ano | findstr :" + port + ").");
            }
        }
    }

    public void launchService(String name, int port, Map<String, String> env, String... extraArgs) throws IOException {
        Path jar = resolveJar(repoRoot.resolve("services").resolve(name).resolve("build/libs"), name);
        launch(name, jar, port, false, env, extraArgs);
    }

    /** Sims live in standalone Gradle builds; their boot jar is built on demand via the wrapper. */
    public void launchSim(String name, int port, Map<String, String> env, String... extraArgs) throws Exception {
        ensureSimJar(name);
        Path jar = resolveJar(repoRoot.resolve("simulators").resolve(name).resolve("build/libs"), name);
        launch(name, jar, port, true, env, extraArgs);
    }

    private void launch(String name, Path jar, int port, boolean sim,
                        Map<String, String> env, String... extraArgs) throws IOException {
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
        // Internal-auth secret FIRST, so a per-test env map can still override it deliberately.
        // Without it a gated service (auth-identity / prefunding / scheme-adapter-zeropay / rate-fx)
        // exits during startup and awaitUp() fails with "exited during startup".
        pb.environment().putAll(INTERNAL_AUTH_ENV);
        pb.environment().putAll(env);
        fleet.add(new Service(name, pb.start(), port));
    }

    public void awaitUp(Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        List<Service> pending = new ArrayList<>(fleet);
        while (!pending.isEmpty() && Instant.now().isBefore(deadline)) {
            pending.removeIf(s -> isUp(s.port()));
            for (Service s : pending) {
                if (s.process() != null && !s.process().isAlive()) {
                    dumpLogs();
                    fail("Service '" + s.name() + "' exited during startup (exit="
                            + s.process().exitValue() + "). See logs in " + logDir);
                }
            }
            if (pending.isEmpty()) break;
            sleep(2000);
        }
        if (!pending.isEmpty()) {
            dumpLogs();
            fail("Fleet did not come up within " + timeout.toSeconds() + "s. Still down: "
                    + pending.stream().map(s -> s.name() + ":" + s.port()).toList()
                    + ". Logs in " + logDir);
        }
    }

    // -------------------------------------------------------------------------
    // Teardown — kill + PROVE the ports closed (no zombie JVMs)
    // -------------------------------------------------------------------------

    public void shutdown() {
        for (Service s : fleet) {
            if (s.process() != null && s.process().isAlive()) {
                s.process().descendants().forEach(ProcessHandle::destroyForcibly);
                s.process().destroyForcibly();
            }
        }
        for (Service s : fleet) {
            try {
                if (s.process() != null) {
                    s.process().waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Verify every port actually released (Windows can lag a moment after process death).
        Instant deadline = Instant.now().plusSeconds(15);
        List<Service> still = new ArrayList<>(fleet);
        while (!still.isEmpty() && Instant.now().isBefore(deadline)) {
            still.removeIf(s -> !portListening(s.port()));
            if (!still.isEmpty()) sleep(1000);
        }
        if (!still.isEmpty()) {
            throw new IllegalStateException("ZOMBIE fleet processes — ports still bound after kill: "
                    + still.stream().map(s -> s.name() + ":" + s.port() + " (pid "
                            + (s.process() == null ? "?" : s.process().pid()) + ")").toList()
                    + ". Kill manually: taskkill /F /PID <pid>");
        }
    }

    // -------------------------------------------------------------------------
    // Probes
    // -------------------------------------------------------------------------

    /** "Up" = the port answers HTTP with any status (same heuristic as run-fleet.ps1). */
    public static boolean isUp(int port) {
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

    /** Raw TCP-level check — true when something is listening on the port. */
    public static boolean portListening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Jar resolution + on-demand sim build (mirrors WalletScanPayE2ETest)
    // -------------------------------------------------------------------------

    private static Path resolveJar(Path libsDir, String name) {
        if (!Files.isDirectory(libsDir)) {
            fail("Boot jar dir not found: " + libsDir + " — build the jar first "
                    + "(./gradlew :e2e-tests:e2eTest builds service jars; sims build on demand).");
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

    private void ensureSimJar(String name) throws Exception {
        Path libs = repoRoot.resolve("simulators/" + name + "/build/libs");
        if (Files.isDirectory(libs)) {
            try (Stream<Path> f = Files.list(libs)) {
                if (f.anyMatch(p -> p.getFileName().toString().matches(name + "-.*\\.jar")
                        && !p.getFileName().toString().contains("plain"))) {
                    return;
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
        throw new IllegalStateException("Could not locate repo root (no settings.gradle above " + dir + ")");
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    // Both helpers always present X-Gme-Internal (T0-2 / T0-5). Several of the URLs these tests hit
    // are gated (/internal/scheme/**, /v1/prefunding/**) and would answer 401 without it; on an
    // ungated route the extra header is simply ignored, so it is safe to send unconditionally rather
    // than asking each call site to decide.
    public static HttpResponse<String> get(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header(INTERNAL_HEADER, INTERNAL_SECRET)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    public static HttpResponse<String> post(String url, String json) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .header(INTERNAL_HEADER, INTERNAL_SECRET)
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    public void dumpLogs() {
        for (Service s : fleet) {
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

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
