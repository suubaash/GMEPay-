package com.gme.pay.gateway.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * T0-7 — <b>the published stub keys authenticate nothing, and a misconfigured source refuses to
 * boot.</b>
 *
 * <p>The gap: {@code gateway.partner-credentials.source} defaulted to {@code stub} in the shipped
 * {@code application.yml} <em>and</em> in the properties class, no deployment file overrode it, and
 * the bean it selected held {@code pk_test_abc}/{@code sk_test_xyz} plus
 * {@code pk_test_no_mtls}/{@code sk_test_no_mtls} with {@code List.of()} as the IP allowlist. Anyone
 * with a checkout could HMAC-sign requests as {@code partner_test_001} against the whole routed
 * {@code /v1/**} surface of a default-configured gateway.
 */
class PartnerCredentialConfigTest {

    private static final String BASE_URL = "http://auth-identity:8080";
    private static final String SECRET = "test-fixture-internal-token";

    private final PartnerCredentialConfig config = new PartnerCredentialConfig();

    /** A WebClient.Builder whose exchanges never leave the JVM. */
    private static WebClient.Builder builderAnswering(String json) {
        return WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(org.springframework.http.HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(json)
                        .build()));
    }

    private static ConfigPartnerCredentialProperties props(String source) {
        ConfigPartnerCredentialProperties p = new ConfigPartnerCredentialProperties();
        p.setSource(source);
        return p;
    }

    // ------------------------------------------------------------------ startup

    @Test
    @DisplayName("source=stub → refuses to boot (the removed bean published keys and secrets)")
    void stubSourceRefusesToBoot() {
        assertThatThrownBy(() -> config.partnerCredentialService(
                props("stub"), builderAnswering("{}"), BASE_URL, SECRET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refuses to start")
                .hasMessageContaining("source=stub is no longer supported")
                .hasMessageContaining("PUBLISHED");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"db", "postgres", "STUBB", "  ", "registry"})
    @DisplayName("an unrecognised/typo'd source → refuses to boot rather than guessing")
    void unknownSourceRefusesToBoot(String source) {
        assertThatThrownBy(() -> config.partnerCredentialService(
                props(source), builderAnswering("{}"), BASE_URL, SECRET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a recognised credential source");
    }

    @Test
    @DisplayName("'stub' is not in the accepted-source roster at all")
    void stubIsNotAnAcceptedSource() {
        assertThat(PartnerCredentialConfig.ACCEPTED_SOURCES)
                .containsExactly(ConfigPartnerCredentialProperties.SOURCE_CONFIG)
                .doesNotContain(ConfigPartnerCredentialProperties.SOURCE_STUB);
    }

    @ParameterizedTest
    @ValueSource(strings = {"config", "CONFIG", "Config", " config "})
    @DisplayName("source=config (any casing) → boots")
    void configSourceBoots(String source) {
        assertThatCode(() -> config.partnerCredentialService(
                props(source), builderAnswering("{}"), BASE_URL, SECRET))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the code default is config, not stub")
    void codeDefaultIsConfig() {
        assertThat(new ConfigPartnerCredentialProperties().getSource())
                .isEqualTo(ConfigPartnerCredentialProperties.SOURCE_CONFIG);
        assertThat(new ConfigPartnerCredentialProperties().isVerifyWithAuthIdentity()).isTrue();
    }

    // ------------------------------------------------------- the published keys

    @Test
    @DisplayName("the api keys the deleted stub published authenticate NOTHING on a default gateway")
    void publishedStubKeysAuthenticateNothing() {
        // A default-configured gateway: source=config, empty partner table, lifecycle check on.
        // The status client is wired to answer active=true for ANY key, so this test cannot pass by
        // accident through auth-identity saying no — the rejection has to come from the gateway
        // holding no signing material for these keys.
        PartnerCredentialService svc = config.partnerCredentialService(
                new ConfigPartnerCredentialProperties(),
                builderAnswering("{\"found\":true,\"active\":true,\"partnerId\":1}"),
                BASE_URL, SECRET);

        for (String published : List.of(
                TestPartnerCredentials.PUBLISHED_API_KEY,
                TestPartnerCredentials.PUBLISHED_API_KEY_NO_MTLS)) {
            assertThat(svc.findByApiKey(published).blockOptional())
                    .as("published stub key %s must not resolve to a credential", published)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("an empty partner table authenticates nobody (fail closed by default)")
    void emptyTableAuthenticatesNobody() {
        PartnerCredentialService svc = config.partnerCredentialService(
                new ConfigPartnerCredentialProperties(),
                builderAnswering("{\"found\":true,\"active\":true,\"partnerId\":1}"),
                BASE_URL, SECRET);

        assertThat(svc.findByApiKey("pk_live_anything").blockOptional()).isEmpty();
        assertThat(svc.findByApiKey(null).blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("verify-with-auth-identity=false yields the bare config source (documented downgrade)")
    void optOutYieldsBareSource() {
        ConfigPartnerCredentialProperties p = new ConfigPartnerCredentialProperties();
        p.setVerifyWithAuthIdentity(false);

        PartnerCredentialService svc = config.partnerCredentialService(
                p, builderAnswering("{}"), BASE_URL, SECRET);

        assertThat(svc).isInstanceOf(ConfigPartnerCredentialService.class);
    }

    // ------------------------------------------------- the shipped config file

    @Test
    @DisplayName("the SHIPPED application.yml selects config and carries no published credential")
    void shippedConfigDoesNotSelectTheStub() throws Exception {
        String yaml;
        try (var in = new ClassPathResource("application.yml").getInputStream()) {
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // Strip comments so the explanatory prose (which names the old literals) is not matched.
        String cfg = yaml.lines()
                .filter(l -> !l.stripLeading().startsWith("#"))
                .collect(Collectors.joining("\n"));

        assertThat(cfg)
                .as("the credential source must not be the stub")
                .doesNotContain("source: stub")
                .as("the credential source must be the config table")
                .contains("source: config")
                .as("the partner table must ship empty")
                .contains("partners: []")
                .as("the auth-identity lifecycle check must ship on")
                .contains("verify-with-auth-identity: true");

        assertThat(cfg)
                .as("no partner credential literal may ship in config")
                .doesNotContain(TestPartnerCredentials.PUBLISHED_API_KEY)
                .doesNotContain(TestPartnerCredentials.PUBLISHED_SECRET)
                .doesNotContain(TestPartnerCredentials.PUBLISHED_API_KEY_NO_MTLS);

        assertThat(cfg)
                .as("the edge controls must ship fail-CLOSED (T0-7)")
                .contains("trust_header_only_in_dev: false")
                .contains("fail-open: false")
                .contains("enabled: true");
    }

    @Test
    @DisplayName("no main source file anywhere in the module contains a published credential")
    void noMainSourceContainsAPublishedCredential() throws Exception {
        // The stub class was DELETED rather than @Profile-gated: a profile still ships the literals.
        // Walk the module's main sources so re-adding them anywhere fails here.
        java.nio.file.Path main = java.nio.file.Path.of("src", "main");
        assertThat(java.nio.file.Files.isDirectory(main))
                .as("test must run with the module dir as CWD; got %s",
                        java.nio.file.Path.of("").toAbsolutePath())
                .isTrue();

        try (var files = java.nio.file.Files.walk(main)) {
            List<String> offenders = files
                    .filter(java.nio.file.Files::isRegularFile)
                    .filter(p -> {
                        try {
                            String text = java.nio.file.Files.readString(p);
                            // Comments in main sources legitimately NAME the removed keys to explain
                            // the gap; only a non-comment occurrence is a finding.
                            return text.lines()
                                    .filter(l -> {
                                        String s = l.stripLeading();
                                        return !s.startsWith("//") && !s.startsWith("*")
                                                && !s.startsWith("/*") && !s.startsWith("#");
                                    })
                                    .anyMatch(l -> l.contains(TestPartnerCredentials.PUBLISHED_SECRET)
                                            || l.contains("sk_test_no_mtls"));
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .map(java.nio.file.Path::toString)
                    .toList();
            assertThat(offenders)
                    .as("a published HMAC secret is back in main sources")
                    .isEmpty();
        }
    }
}
