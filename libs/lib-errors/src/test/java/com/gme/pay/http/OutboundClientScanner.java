package com.gme.pay.http;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The detection rule behind {@link OutboundHttpTimeoutWiringGuardTest}, extracted so it can be
 * <b>pointed at a fixture</b> instead of only at the real tree.
 *
 * <h2>Why extraction was necessary rather than tidy</h2>
 *
 * <p>The first version of the guard held its logic inline. That guard could report "today's tree is
 * clean", but nothing anywhere proved it would <em>notice</em> a new offender — and a guard whose
 * detection has never been exercised is indistinguishable from a guard with a broken regex. The whole
 * T3-11 second pass exists because a mechanism was built, tested against itself, and its reach assumed.
 * Repeating that shape in the test written to prevent it would be the same mistake one level up.
 *
 * <p>So the scanner takes a root. {@link OutboundHttpTimeoutWiringGuardTest} points it at
 * {@code services/}; {@code OutboundHttpTimeoutGuardDetectsNewOffendersTest} points it at a temporary
 * directory containing a deliberately untimed client and asserts it is found.
 *
 * <h2>The rule</h2>
 *
 * <p>A main-source file is <b>unbounded</b> when it builds a client from a <em>static</em> factory
 * ({@code RestClient.builder()} or {@code WebClient.builder()}) and installs no transport of its own
 * ({@code .requestFactory(} for the blocking stack, {@code .clientConnector(} for the reactive one).
 *
 * <p>The static factory is the entire mechanism of the defect. Spring Boot applies
 * {@code RestClientCustomizer} to the {@code RestClient.Builder} <b>bean</b> and
 * {@code WebClientCustomizer} to the {@code WebClient.Builder} <b>bean</b>. Both static factories return
 * a fresh, uncustomized builder, so the fleet floor never reaches a client built from one — while
 * {@code gmepay.http.client.read-timeout} still resolves and still appears in {@code /actuator/env}. The
 * check is deliberately crude: it proves a client is reachable by <em>some</em> bound rather than by
 * none. Whether the bound is sensible is proved elsewhere, against real unresponsive sockets.
 */
final class OutboundClientScanner {

    /** Static factories that bypass every customizer in the context, per stack. */
    private static final List<String> STATIC_FACTORIES =
            List.of("RestClient.builder()", "WebClient.builder()");

    /**
     * The escape hatch, per stack: a client that installs its own transport is bounded by that
     * transport. {@code requestFactory} is the blocking seam, {@code clientConnector} the reactive one —
     * and omitting the reactive one is precisely how api-gateway's three WebClient hops stayed invisible
     * to the first version of this guard.
     */
    private static final List<String> OWN_TRANSPORT =
            List.of(".requestFactory(", ".clientConnector(");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    private OutboundClientScanner() {}

    /** One scanned file: its repo-relative path and whether it escaped every bound. */
    record Finding(String path, boolean unbounded) {}

    /**
     * Every main-source file under {@code scanRoot} that calls a static builder factory, each flagged
     * with whether it also installs a transport of its own.
     *
     * @param relativeTo the directory paths are reported relative to (the repository root in the real
     *                   scan; the fixture root in the self-test)
     */
    static List<Finding> scan(Path scanRoot, Path relativeTo) {
        List<Finding> findings = new ArrayList<>();
        for (Path file : javaSourcesUnder(scanRoot)) {
            // Comments are stripped first: this very class names both patterns in its own javadoc, and
            // several production classes explain in a comment why they do NOT use the static factory.
            // A scanner that matched those would report the fix as the defect.
            String source = stripComments(read(file));
            if (STATIC_FACTORIES.stream().noneMatch(source::contains)) {
                continue;
            }
            boolean bounded = OWN_TRANSPORT.stream().anyMatch(source::contains);
            findings.add(new Finding(relativise(relativeTo, file), !bounded));
        }
        return findings;
    }

    static List<String> unboundedPaths(List<Finding> findings) {
        return findings.stream().filter(Finding::unbounded).map(Finding::path).toList();
    }

    private static String stripComments(String source) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll("")).replaceAll("");
    }

    private static List<Path> javaSourcesUnder(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    // build/ holds generated + copied sources; scanning it would double-count.
                    .filter(p -> !p.toString().replace('\\', '/').contains("/build/"))
                    // src/test holds MockRestServiceServer wiring, which is bounded by construction.
                    .filter(p -> !p.toString().replace('\\', '/').contains("/src/test/"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String relativise(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /** Walks up from the module directory to the directory holding {@code settings.gradle}. */
    static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.exists(candidate.resolve("settings.gradle"))
                    && Files.isDirectory(candidate.resolve("services"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "could not locate the repository root (settings.gradle + services/) above "
                        + Path.of("").toAbsolutePath());
    }
}
