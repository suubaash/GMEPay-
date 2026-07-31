package com.gme.pay.bff.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.bff.client.rest.RestReportingClient;
import com.gme.pay.bff.client.rest.RestSystemHealthClient;
import com.gme.pay.bff.client.rest.RestWebhookOpsClient;
import com.gme.pay.bff.client.stub.StubReportingClient;
import com.gme.pay.bff.client.stub.StubSystemHealthClient;
import com.gme.pay.bff.client.stub.StubWebhookOpsClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * <b>The T1-1 defect class, closed for the whole BFF rather than one bean at a time.</b>
 *
 * <p>A {@code Stub*} bean carrying {@code matchIfMissing = true} is production wiring: it wins
 * unless a values file says otherwise, so whether the system does anything real is a property of a
 * deployment file rather than of the code. That defect has now been found three times in this
 * repository (config-registry's fabricated go-live credentials; this service's in-memory
 * operator-action audit trail; and the three selectors below), and the previous two were closed one
 * bean at a time. This test closes the class:
 *
 * <ol>
 *   <li>every {@code gmepay.*.client} selector in the shipped {@code application.properties}
 *       defaults to a REAL implementation — a new {@code :stub} default fails here;</li>
 *   <li>no {@code Stub*} bean carries {@code matchIfMissing} at all;</li>
 *   <li>every {@code Stub*} that HAS a real counterpart is gated {@code havingValue = "stub"}, so it
 *       is not even constructed unless someone asks for it by name — including the ones that used to
 *       be bare {@code @Component}s displaced only by a {@code @Primary} annotation
 *       ({@code StubPlatformSettingsClient} was one {@code @Primary} away from serving
 *       money-affecting platform settings out of a heap map);</li>
 *   <li>the matching {@code Rest*} bean carries {@code matchIfMissing = true}, so absence of the
 *       property selects the LIVE client;</li>
 *   <li>an unrecognised value leaves NO bean, so a consumer that requires one fails context refresh
 *       and the service refuses to start rather than silently degrading.</li>
 * </ol>
 *
 * <p><b>The three that were live in production.</b> {@code reporting-compliance},
 * {@code system-health} and {@code webhook-ops} were set in no compose service, no Helm values file
 * and nothing else — so their stubs answered in every environment. Concretely: the Reports page
 * served fixtures instead of BOK FX1014/FX1015 rows, the System Health page reported all 17 services
 * UP whether or not any of them were running (an operator surface that cannot report an outage), and
 * the webhook-secret panel reported zero endpoints, which is why T5-8's "every deploy target must set
 * rest" never got actioned — nothing failed when it wasn't. All three real clients were verified
 * against real endpoints before the inversion (T1-1's first step, the one that stopped the
 * operator-audit inversion): {@code GET /v1/reports} on reporting-compliance
 * ({@code ReportController}), {@code /actuator/health} fleet-wide, and notification-webhook's
 * {@code /v1/webhooks/deliveries/**} + {@code /v1/webhooks/endpoints/**}
 * ({@code WebhookReplayController}, {@code WebhookEndpointController}).
 */
class StubClientSelectorInversionTest {

    /** The one selector whose real implementation is a local table, not a REST hop. */
    private static final String OPERATOR_AUDIT = "gmepay.operator-action-audit.client";

    // ------------------------------------------------------------------ 1. shipped configuration

    @Test
    @DisplayName("no gmepay.*.client selector in the shipped properties still defaults to 'stub'")
    void noShippedSelectorDefaultsToStub() {
        Map<String, String> defaults = shippedSelectorDefaults();
        assertThat(defaults)
                .as("the selectors this test knows how to police must actually be found")
                .hasSizeGreaterThanOrEqualTo(10);

        List<String> stubbed = new ArrayList<>();
        defaults.forEach((property, value) -> {
            if ("stub".equalsIgnoreCase(value)) {
                stubbed.add(property);
            }
        });
        assertThat(stubbed).as("""
                These selectors default to the in-memory stub, so the surfaces they back serve \
                FABRICATED data in any environment that does not override them — and whether it is \
                overridden lives in a values file, not in the code. Invert the default (real client \
                matchIfMissing = true, stub havingValue = "stub" only) after verifying the real \
                client's endpoint actually exists.""")
                .isEmpty();
    }

    @Test
    @DisplayName("the three that were live in production now default to rest")
    void thePreviouslyUnsetSelectorsDefaultToRest() {
        Map<String, String> defaults = shippedSelectorDefaults();
        assertThat(defaults).containsEntry("gmepay.reporting-compliance.client", "rest");
        assertThat(defaults).containsEntry("gmepay.system-health.client", "rest");
        assertThat(defaults).containsEntry("gmepay.webhook-ops.client", "rest");
        // The odd one out, deliberately: its REST endpoint exists in no service, so the real
        // implementation is the durable local table. Guarded here so an over-eager sweep of
        // "make everything rest" cannot break it.
        assertThat(defaults).containsEntry(OPERATOR_AUDIT, "db");
    }

    // ------------------------------------------------------------------ 2-4. the annotations

    @Test
    @DisplayName("no Stub* bean carries matchIfMissing — a stub is never the fallback winner")
    void noStubIsMatchIfMissing() {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFilesIn(stubPackage())) {
            // Comments are stripped first: several of these classes now DOCUMENT that they used to
            // carry matchIfMissing, and a scan that could not tell the annotation from the history
            // lesson would either fail forever or force the explanation out of the code.
            if (stripComments(read(file)).contains("matchIfMissing")) {
                offenders.add(file.getFileName().toString());
            }
        }
        assertThat(offenders).as("""
                matchIfMissing = true on a Stub* bean means it wins whenever nobody chose, which is \
                every environment that forgot the selector. Put matchIfMissing on the REAL client \
                instead.""").isEmpty();
    }

    @Test
    @DisplayName("every Stub* with a real counterpart is opt-in (havingValue = \"stub\")")
    void everyStubWithACounterpartIsGated() {
        List<String> ungated = new ArrayList<>();
        for (Path file : javaFilesIn(stubPackage())) {
            String stubName = file.getFileName().toString();
            String restName = stubName.replaceFirst("^Stub", "Rest");
            if (!Files.exists(restPackage().resolve(restName))) {
                continue;   // no real implementation exists; see StubAuditClient / StubRatesClient
            }
            String source = stripComments(read(file));
            if (!source.contains("havingValue = \"stub\"")) {
                ungated.add(stubName);
            }
        }
        assertThat(ungated).as("""
                A Stub* bean with a real counterpart that carries no @ConditionalOnProperty is \
                CONSTRUCTED in every environment and merely displaced at injection time by the real \
                client's @Primary — i.e. it is one removed annotation away from being live. Gate it \
                on havingValue = "stub".""").isEmpty();
    }

    @Test
    @DisplayName("every Rest* selector bean carries matchIfMissing = true (absence selects LIVE)")
    void everyRestClientWinsWhenTheSelectorIsAbsent() {
        Pattern conditional = Pattern.compile(
                "@ConditionalOnProperty\\(\\s*name = \"(gmepay\\.[a-z-]+\\.client)\",\\s*"
                        + "havingValue = \"rest\"(?<mim>, matchIfMissing = true)?\\)");
        List<String> notDefault = new ArrayList<>();
        for (Path file : javaFilesIn(restPackage())) {
            Matcher m = conditional.matcher(stripComments(read(file)));
            if (!m.find()) {
                continue;   // not a selector-gated adapter
            }
            boolean isOperatorAudit = OPERATOR_AUDIT.equals(m.group(1));
            boolean wins = m.group("mim") != null;
            if (isOperatorAudit) {
                assertThat(wins).as("""
                        %s must NOT be matchIfMissing: no service exposes \
                        POST /v1/audit/operator-actions, so making it the fallback would fail-close \
                        every audited operator action. Its real implementation is the durable \
                        operator_action_audit table.""", file.getFileName())
                        .isFalse();
            } else if (!wins) {
                notDefault.add(file.getFileName().toString());
            }
        }
        assertThat(notDefault).as("""
                Without matchIfMissing = true on the real client, an absent selector selects NOTHING \
                — and the stub, or a bare @Component fallback, answers instead.""").isEmpty();
    }

    // ------------------------------------------------------------------ 5. behaviour

    @Test
    @DisplayName("absent selector -> the LIVE client; 'stub' -> the stub; anything else -> no bean")
    void theDecisionTableHolds() {
        assertDecisionTable("gmepay.webhook-ops.client", WebhookOpsClient.class,
                RestWebhookOpsClient.class, StubWebhookOpsClient.class, NeedsWebhookOps.class);
        assertDecisionTable("gmepay.system-health.client", SystemHealthClient.class,
                RestSystemHealthClient.class, StubSystemHealthClient.class, NeedsSystemHealth.class);
        assertDecisionTable("gmepay.reporting-compliance.client", ReportingClient.class,
                RestReportingClient.class, StubReportingClient.class, NeedsReporting.class);
    }

    private void assertDecisionTable(String selector, Class<?> port, Class<?> restType,
                                     Class<?> stubType, Class<?> consumer) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(Collaborators.class)
                .withUserConfiguration(restType, stubType);

        runner.run(ctx -> {
            assertThat(ctx).as("%s absent", selector).hasSingleBean(port);
            assertThat(ctx.getBean(port)).as("%s absent", selector).isInstanceOf(restType);
        });
        runner.withPropertyValues(selector + "=rest").run(ctx ->
                assertThat(ctx.getBean(port)).isInstanceOf(restType));
        runner.withPropertyValues(selector + "=stub").run(ctx -> {
            assertThat(ctx).hasSingleBean(port);
            assertThat(ctx.getBean(port)).isInstanceOf(stubType);
            assertThat(ctx).doesNotHaveBean(restType);
        });
        for (String bogus : new String[]{"mock", "http", "none", "live"}) {
            runner.withPropertyValues(selector + "=" + bogus)
                    .run(ctx -> assertThat(ctx).as("%s=%s", selector, bogus).doesNotHaveBean(port));
            // ...and what that costs a real consumer: the controllers take these as required
            // constructor arguments, so the context cannot refresh. Refusing to boot on a typo is
            // the point — the alternative is a surface that looks healthy and reports nothing real.
            runner.withPropertyValues(selector + "=" + bogus)
                    .withUserConfiguration(consumer)
                    .run(ctx -> assertThat(ctx).as("%s=%s", selector, bogus).hasFailed());
        }
    }


    // ------------------------------------------------------------------ 6. the startup banner

    @Test
    @DisplayName("the startup banner names every stub actually wired, and stays silent when none is")
    void theBannerReportsWhatIsWired() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(Collaborators.class)
                .withUserConfiguration(RestReportingClient.class, StubReportingClient.class,
                        StubClientSelectionWarner.class)
                .withPropertyValues("gmepay.reporting-compliance.client=stub")
                .run(ctx -> assertThat(ctx.getBean(StubClientSelectionWarner.class).wiredStubs())
                        .containsExactly("StubReportingClient"));

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(Collaborators.class)
                .withUserConfiguration(RestReportingClient.class, StubReportingClient.class,
                        StubClientSelectionWarner.class)
                .run(ctx -> assertThat(ctx.getBean(StubClientSelectionWarner.class).wiredStubs())
                        .isEmpty());
    }

    @Test
    @DisplayName("every gated stub is named in the banner's selector map, so the WARN is actionable")
    void theBannerKnowsEverySelector() {
        List<String> unmapped = new ArrayList<>();
        for (Path file : javaFilesIn(stubPackage())) {
            String simple = file.getFileName().toString().replace(".java", "");
            String source = stripComments(read(file));
            if (source.contains("havingValue = \"stub\"")
                    && !StubClientSelectionWarner.SELECTOR_BY_STUB.containsKey(simple)) {
                unmapped.add(simple);
            }
        }
        assertThat(unmapped).as(
                "a wired stub with no selector in the banner map reports as unswitchable, which is "
                        + "only true for StubAuditClient / StubRatesClient").isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    /** {@code gmepay.<x>.client} -> the default baked into the shipped properties file. */
    private static Map<String, String> shippedSelectorDefaults() {
        Pattern line = Pattern.compile(
                "^(gmepay\\.[a-z-]+\\.client)=\\$\\{[A-Z0-9_]+:([a-z]*)}\\s*$", Pattern.MULTILINE);
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = line.matcher(read(shippedProperties()));
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    private static Path moduleRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("src/main/java/com/gme/pay/bff/client"))) {
                return candidate;
            }
            Path nested = candidate.resolve("services/ops-partner-bff");
            if (Files.isDirectory(nested.resolve("src/main/java/com/gme/pay/bff/client"))) {
                return nested;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("cannot locate the ops-partner-bff module from "
                + Path.of("").toAbsolutePath());
    }

    private static Path stubPackage() {
        return moduleRoot().resolve("src/main/java/com/gme/pay/bff/client/stub");
    }

    private static Path restPackage() {
        return moduleRoot().resolve("src/main/java/com/gme/pay/bff/client/rest");
    }

    private static Path shippedProperties() {
        return moduleRoot().resolve("src/main/resources/application.properties");
    }

    private static List<Path> javaFilesIn(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> out = files.filter(p -> p.getFileName().toString().endsWith(".java")).toList();
            assertThat(out).as("no sources under %s — has the package moved?", dir).isNotEmpty();
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Drops block and line comments so a javadoc reference is never read as wiring. */
    private static String stripComments(String source) {
        String withoutBlocks = source.replaceAll("(?s)/\\*.*?\\*/", "");
        return withoutBlocks.replaceAll("(?m)^\\s*//.*$", "");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Configuration
    static class Collaborators {
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }

    @Configuration
    static class NeedsWebhookOps {
        @Bean
        String webhookOpsConsumer(WebhookOpsClient client) {
            return client.toString();
        }
    }

    @Configuration
    static class NeedsSystemHealth {
        @Bean
        String systemHealthConsumer(SystemHealthClient client) {
            return client.toString();
        }
    }

    @Configuration
    static class NeedsReporting {
        @Bean
        String reportingConsumer(ReportingClient client) {
            return client.toString();
        }
    }
}
