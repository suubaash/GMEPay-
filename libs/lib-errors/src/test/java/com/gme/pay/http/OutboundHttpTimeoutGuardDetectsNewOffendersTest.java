package com.gme.pay.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves that {@link OutboundHttpTimeoutWiringGuardTest} would actually <b>catch a new untimed
 * client</b>, rather than merely recording that today's tree happens to be clean.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The guard's value is entirely in what it does to code that does not exist yet. Run against the real
 * tree it can only ever report "no unexpected offenders" — a result it would also report if its pattern
 * were misspelled, if the walk skipped {@code src/main}, or if comment-stripping ate the match. There is
 * no observation of the real tree that distinguishes "the fleet is clean" from "the detector is broken".
 *
 * <p>That is the same mistake T3-11's second pass was written up for: <b>a mechanism proved against
 * itself, its reach assumed.</b> The correction is a fixture the detector must find — a synthetic source
 * tree containing exactly the client shape a future author would write, in <b>both</b> stacks, so the
 * detector's sensitivity is measured instead of inferred.
 *
 * <p>Each case also carries its negative twin: the fixed form of the same client must NOT be reported.
 * A detector that flags everything passes the positive case and is useless, so the pair is what makes
 * either half meaningful.
 */
class OutboundHttpTimeoutGuardDetectsNewOffendersTest {

    @TempDir
    Path fixtureRoot;

    /** Writes a fixture file at a service-like path so the scanner's filters are exercised for real. */
    private String write(String relativePath, String body) throws IOException {
        Path file = fixtureRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return relativePath;
    }

    private List<String> unboundedInFixture() {
        return OutboundClientScanner.unboundedPaths(
                OutboundClientScanner.scan(fixtureRoot, fixtureRoot));
    }

    // ------------------------------------------------------------------------
    // Blocking stack
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("RestClient (blocking)")
    class Blocking {

        @Test
        @DisplayName("a newly added client using the static factory is reported")
        void aNewUntimedRestClientIsDetected() throws IOException {
            String path = write("services/new-service/src/main/java/com/gme/pay/new_/RestThingClient.java",
                    """
                    package com.gme.pay.new_;

                    import org.springframework.web.client.RestClient;

                    /** The shape a future author writes without knowing about the customizer. */
                    public class RestThingClient {
                        private final RestClient restClient;

                        public RestThingClient(String baseUrl) {
                            this.restClient = RestClient.builder().baseUrl(baseUrl).build();
                        }
                    }
                    """);

            assertThat(unboundedInFixture())
                    .as("the guard must FAIL on a new client built from the static factory — this is "
                            + "the whole point of it existing")
                    .containsExactly(path);
        }

        @Test
        @DisplayName("the fixed form of the same client is not reported")
        void theInjectedBuilderFormIsNotDetected() throws IOException {
            write("services/new-service/src/main/java/com/gme/pay/new_/RestThingClient.java",
                    """
                    package com.gme.pay.new_;

                    import org.springframework.web.client.RestClient;

                    public class RestThingClient {
                        private final RestClient restClient;

                        public RestThingClient(RestClient.Builder builder, String baseUrl) {
                            this.restClient = builder.baseUrl(baseUrl).build();
                        }
                    }
                    """);

            assertThat(unboundedInFixture())
                    .as("a detector that flags the FIX as well as the defect is a detector nobody will "
                            + "keep, so the negative case is as load-bearing as the positive one")
                    .isEmpty();
        }

        @Test
        @DisplayName("a static-factory client that installs its own request factory is not reported")
        void anExplicitRequestFactoryIsAcceptedAsABound() throws IOException {
            write("services/new-service/src/main/java/com/gme/pay/new_/RestThingClient.java",
                    """
                    package com.gme.pay.new_;

                    import com.gme.pay.http.HttpClientTimeouts;
                    import java.time.Duration;
                    import org.springframework.web.client.RestClient;

                    public class RestThingClient {
                        private final RestClient restClient;

                        public RestThingClient(String baseUrl) {
                            this.restClient = RestClient.builder()
                                    .baseUrl(baseUrl)
                                    .requestFactory(HttpClientTimeouts.requestFactory(
                                            Duration.ofSeconds(2), Duration.ofSeconds(4)))
                                    .build();
                        }
                    }
                    """);

            assertThat(unboundedInFixture()).isEmpty();
        }
    }

    // ------------------------------------------------------------------------
    // Reactive stack — the half that was undetectable before this change
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("WebClient (reactive)")
    class Reactive {

        @Test
        @DisplayName("a newly added WebClient using the static factory is reported")
        void aNewUntimedWebClientIsDetected() throws IOException {
            // Before WebClient.builder() was added to the scanner this fixture scanned CLEAN. That is
            // not a hypothetical: api-gateway's three real hops were unbounded at the time and the
            // guard reported a clean fleet, because no RestClientCustomizer has ever had anything to do
            // with a WebClient and the scan only knew one pattern.
            String path = write("services/new-gateway/src/main/java/com/gme/pay/gw/ThingClient.java",
                    """
                    package com.gme.pay.gw;

                    import org.springframework.web.reactive.function.client.WebClient;

                    public class ThingClient {
                        private final WebClient webClient;

                        public ThingClient(String baseUrl) {
                            this.webClient = WebClient.builder().baseUrl(baseUrl).build();
                        }
                    }
                    """);

            assertThat(unboundedInFixture())
                    .as("the reactive stack must be detectable too, or the guard certifies its own "
                            + "blind spot")
                    .containsExactly(path);
        }

        @Test
        @DisplayName("the injected WebClient.Builder form is not reported")
        void theInjectedWebClientBuilderFormIsNotDetected() throws IOException {
            write("services/new-gateway/src/main/java/com/gme/pay/gw/ThingClient.java",
                    """
                    package com.gme.pay.gw;

                    import org.springframework.web.reactive.function.client.WebClient;

                    public class ThingClient {
                        private final WebClient webClient;

                        public ThingClient(WebClient.Builder builder, String baseUrl) {
                            this.webClient = builder.baseUrl(baseUrl).build();
                        }
                    }
                    """);

            assertThat(unboundedInFixture()).isEmpty();
        }

        @Test
        @DisplayName("a static-factory WebClient that installs its own connector is not reported")
        void anExplicitClientConnectorIsAcceptedAsABound() throws IOException {
            write("services/new-gateway/src/main/java/com/gme/pay/gw/ThingClient.java",
                    """
                    package com.gme.pay.gw;

                    import org.springframework.http.client.reactive.ReactorClientHttpConnector;
                    import org.springframework.web.reactive.function.client.WebClient;

                    public class ThingClient {
                        private final WebClient webClient;

                        public ThingClient(ReactorClientHttpConnector connector, String baseUrl) {
                            this.webClient = WebClient.builder()
                                    .baseUrl(baseUrl)
                                    .clientConnector(connector)
                                    .build();
                        }
                    }
                    """);

            assertThat(unboundedInFixture()).isEmpty();
        }
    }

    // ------------------------------------------------------------------------
    // The scanner's own filters, each of which could silently disable detection
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("a static factory named only inside a comment is not reported")
    void commentsCannotProduceAFalsePositive() throws IOException {
        // Several fixed clients explain in a comment why they do NOT use the static factory, and this
        // guard's own javadoc names both patterns. Without comment-stripping the scanner would report
        // the documentation of the fix as an instance of the defect, and the baseline would fill up
        // with files that are already correct — at which point nobody reads the failures.
        write("services/new-service/src/main/java/com/gme/pay/new_/RestThingClient.java",
                """
                package com.gme.pay.new_;

                import org.springframework.web.client.RestClient;

                public class RestThingClient {
                    private final RestClient restClient;

                    public RestThingClient(RestClient.Builder builder, String baseUrl) {
                        // T3-11: deliberately NOT RestClient.builder() — the static factory escapes
                        // every RestClientCustomizer, including the timeout floor.
                        /* Nor WebClient.builder(), for the same reason. */
                        this.restClient = builder.baseUrl(baseUrl).build();
                    }
                }
                """);

        assertThat(unboundedInFixture()).isEmpty();
    }

    @Test
    @DisplayName("test sources and build output are excluded from the scan")
    void testSourcesAndBuildOutputAreNotReported() throws IOException {
        // Test fixtures use the static factory constantly and legitimately — MockRestServiceServer is
        // bound by installing a request factory, so a test client is bounded by construction. build/
        // holds generated and copied sources, which would double-count every real finding. Both
        // exclusions are necessary; both also mean a mis-typed filter could exclude src/main and turn
        // the whole guard into a no-op, so they are asserted rather than assumed.
        write("services/new-service/src/test/java/com/gme/pay/new_/RestThingClientTest.java",
                """
                package com.gme.pay.new_;

                import org.springframework.web.client.RestClient;

                class RestThingClientTest {
                    private final RestClient client = RestClient.builder().build();
                }
                """);
        write("services/new-service/build/generated/com/gme/pay/new_/GeneratedClient.java",
                """
                package com.gme.pay.new_;

                import org.springframework.web.client.RestClient;

                public class GeneratedClient {
                    private final RestClient client = RestClient.builder().build();
                }
                """);

        assertThat(unboundedInFixture()).isEmpty();
    }
}
