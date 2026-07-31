package com.gme.pay.e2e.load;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The safety interlock, pinned.
 *
 * <p><b>Untagged on purpose</b> — this runs in the ordinary {@code test} task, i.e. on every
 * {@code gradlew build} and in CI. The load harness itself never runs in CI (there is no test that
 * invokes it), but the *guarantee that it refuses a non-local target* has to be checked continuously:
 * a refactor that widened {@link LoadTargetGuard#LOCAL_HOSTS} or made the acknowledgement flag optional
 * would otherwise be invisible until someone pointed a load generator at production.
 */
@DisplayName("LoadTargetGuard: refuses anything that is not a local dev target")
class LoadTargetGuardTest {

    private static final String LOCAL = "http://localhost:18084";

    @Nested
    @DisplayName("accepts")
    class Accepts {

        @Test
        @DisplayName("localhost + the acknowledgement flag")
        void localhostWithAck() {
            assertDoesNotThrow(() -> LoadTargetGuard.requireLocalDevTarget(List.of(LOCAL), true));
        }

        @Test
        @DisplayName("every documented local form (loopback, IPv6, docker host alias, *.localhost)")
        void everyLocalForm() {
            List<String> forms = List.of(
                    "http://localhost:18084",
                    "http://127.0.0.1:18084",
                    "http://[::1]:18084",
                    "http://host.docker.internal:18084",
                    "http://payment-executor.localhost:18084",
                    "https://localhost:8443");
            for (String form : forms) {
                assertDoesNotThrow(() -> LoadTargetGuard.requireLocalDevTarget(List.of(form), true),
                        form + " should be accepted as local");
            }
        }

        @Test
        @DisplayName("several local targets at once (the money path + the scrape targets)")
        void multipleLocalTargets() {
            assertDoesNotThrow(() -> LoadTargetGuard.requireLocalDevTarget(
                    List.of("http://localhost:18084", "http://localhost:18101", "http://127.0.0.1:18082"),
                    true));
        }
    }

    @Nested
    @DisplayName("refuses")
    class Refuses {

        @Test
        @DisplayName("a remote host, even with the acknowledgement flag")
        void remoteHostEvenWithAck() {
            LoadTargetGuard.RefusedException e = assertThrows(LoadTargetGuard.RefusedException.class,
                    () -> LoadTargetGuard.requireLocalDevTarget(
                            List.of("https://api.gmepay.example.com"), true));
            assertTrue(e.reasons().stream().anyMatch(r -> r.contains("not local/dev")),
                    "should say the host is not local, got: " + e.reasons());
        }

        @Test
        @DisplayName("a local host WITHOUT the acknowledgement flag")
        void localWithoutAck() {
            LoadTargetGuard.RefusedException e = assertThrows(LoadTargetGuard.RefusedException.class,
                    () -> LoadTargetGuard.requireLocalDevTarget(List.of(LOCAL), false));
            assertTrue(e.reasons().stream().anyMatch(r -> r.contains(LoadTargetGuard.ACK_FLAG)),
                    "should demand " + LoadTargetGuard.ACK_FLAG + ", got: " + e.reasons());
        }

        @Test
        @DisplayName("ONE remote target among many local ones — no partial runs")
        void oneRemoteAmongLocals() {
            assertThrows(LoadTargetGuard.RefusedException.class,
                    () -> LoadTargetGuard.requireLocalDevTarget(
                            List.of("http://localhost:18084",
                                    "http://localhost:18101",
                                    "http://10.20.30.40:8080"),
                            true));
        }

        @Test
        @DisplayName("a production marker on an otherwise-local host (tunnels and hosts-file aliases)")
        void prodMarkerOnLocalHost() {
            LoadTargetGuard.RefusedException e = assertThrows(LoadTargetGuard.RefusedException.class,
                    () -> LoadTargetGuard.requireLocalDevTarget(
                            List.of("http://prod.localhost:18084"), true));
            assertTrue(e.reasons().stream().anyMatch(r -> r.contains("production marker")),
                    "should veto the production marker, got: " + e.reasons());
        }

        @Test
        @DisplayName("a private LAN address — 'not the internet' is not the same as 'local'")
        void privateLanAddress() {
            for (String lan : List.of("http://192.168.1.50:18084", "http://10.0.0.5:18084",
                    "http://172.16.4.9:18084")) {
                assertThrows(LoadTargetGuard.RefusedException.class,
                        () -> LoadTargetGuard.requireLocalDevTarget(List.of(lan), true),
                        lan + " must be refused: it is somebody else's machine");
            }
        }

        @Test
        @DisplayName("a bare host:port with no scheme (ambiguous)")
        void bareHostPort() {
            assertThrows(LoadTargetGuard.RefusedException.class,
                    () -> LoadTargetGuard.requireLocalDevTarget(List.of("localhost:18084"), true));
        }

        @Test
        @DisplayName("a non-HTTP scheme")
        void nonHttpScheme() {
            assertThrows(LoadTargetGuard.RefusedException.class,
                    () -> LoadTargetGuard.requireLocalDevTarget(List.of("ftp://localhost/x"), true));
        }

        @Test
        @DisplayName("a URL carrying embedded credentials")
        void embeddedCredentials() {
            LoadTargetGuard.RefusedException e = assertThrows(LoadTargetGuard.RefusedException.class,
                    () -> LoadTargetGuard.requireLocalDevTarget(
                            List.of("http://user:secret@localhost:18084"), true));
            assertTrue(e.reasons().stream().anyMatch(r -> r.contains("credentials")),
                    "should reject embedded credentials, got: " + e.reasons());
        }

        @Test
        @DisplayName("an empty target list — nothing to verify means nothing may run")
        void emptyTargetList() {
            assertThrows(LoadTargetGuard.RefusedException.class,
                    () -> LoadTargetGuard.requireLocalDevTarget(List.of(), true));
        }

        @Test
        @DisplayName("a blank URL")
        void blankUrl() {
            assertThrows(LoadTargetGuard.RefusedException.class,
                    () -> LoadTargetGuard.requireLocalDevTarget(List.of("   "), true));
        }

        @Test
        @DisplayName("reports EVERY reason at once, not just the first")
        void reportsEveryReason() {
            LoadTargetGuard.RefusedException e = assertThrows(LoadTargetGuard.RefusedException.class,
                    () -> LoadTargetGuard.requireLocalDevTarget(
                            List.of("https://live.example.com"), false));
            // remote host + production marker + missing flag
            assertTrue(e.reasons().size() >= 3,
                    "expected at least 3 reasons, got " + e.reasons());
        }
    }

    @Test
    @DisplayName("the defaults LoadOptions ships are themselves accepted (so --help→run works)")
    void shippedDefaultsAreLocal() {
        LoadOptions opts = LoadOptions.parse(new String[] {LoadTargetGuard.ACK_FLAG});
        assertDoesNotThrow(() -> LoadTargetGuard.requireLocalDevTarget(opts.allTargets(), opts.acknowledged));
    }

    @Test
    @DisplayName("the shipped defaults are STILL refused without the flag")
    void shippedDefaultsStillNeedTheFlag() {
        LoadOptions opts = LoadOptions.parse(new String[0]);
        assertThrows(LoadTargetGuard.RefusedException.class,
                () -> LoadTargetGuard.requireLocalDevTarget(opts.allTargets(), opts.acknowledged));
    }
}
