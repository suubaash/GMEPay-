package com.gme.pay.scheme.ninepay.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.scheme.ninepay.sign.NinepaySigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.PropertyPlaceholderHelper;
import org.springframework.web.client.RestClient;

import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Gap <b>T5-4</b>, defence (b): <b>9Pay response-signature verification is ON by default and
 * fails closed with no trust anchor.</b>
 *
 * <p>Before: {@code application.yml} shipped {@code verify-responses: false} and
 * {@code NinepayApiClient}'s {@code @Value} default was {@code false} too, so the default
 * configuration accepted 9Pay API responses — which drive payout state — with no signature
 * check at all.
 *
 * <p>Two independent things have to hold, and each is asserted here without trusting the
 * other:
 * <ol>
 *   <li>the <b>shipped YAML</b> resolves to {@code true} when
 *       {@code GMEPAY_SCHEME_NINEPAY_VERIFY_RESPONSES} is not set in the environment;</li>
 *   <li>the <b>code default</b> is {@code true} — proved behaviourally by wiring
 *       {@link NinepayApiClient} through a real Spring context with NO properties at all
 *       (so only the {@code @Value} defaults apply) and observing that a signed-looking but
 *       unverifiable response is rejected rather than trusted.</li>
 * </ol>
 */
class NinepayVerifyResponsesDefaultTest {

    private static final String BASE = "http://localhost:9107";
    private static final String PROPERTY = "gmepay.scheme.ninepay.verify-responses";

    @Test
    @DisplayName("shipped application.yml: verify-responses resolves to true with no env override")
    void yamlDefaultIsTrue() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        assertFalse(sources.isEmpty(), "application.yml must be loadable from the classpath");

        Object raw = sources.get(0).getProperty(PROPERTY);
        assertEquals("${GMEPAY_SCHEME_NINEPAY_VERIFY_RESPONSES:true}", String.valueOf(raw),
                "the yml must be env-overridable but default ON");

        // Resolve the placeholder the way Spring would with the env var absent.
        String resolved = new PropertyPlaceholderHelper("${", "}", ":", false)
                .replacePlaceholders(String.valueOf(raw), new Properties());
        assertTrue(Boolean.parseBoolean(resolved),
                "with GMEPAY_SCHEME_NINEPAY_VERIFY_RESPONSES unset, verification must be ON");
    }

    @Test
    @DisplayName("code default: a context with NO properties still refuses an unverifiable response")
    void valueDefaultIsTrueAndFailsClosed() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        // OUR private key is present (so the outbound request can be signed and we reach the
        // response-verification step) but 9PAY's public key is NOT — precisely the shipped
        // placeholder configuration this test is about.
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        NinepaySigner halfKeyed =
                new NinepaySigner(generator.generateKeyPair().getPrivate(), null, "SHA256");

        // AnnotationConfigApplicationContext (not a bare GenericApplicationContext) so the
        // annotation post-processors that honour @Autowired/@Value are registered — this must
        // exercise the SAME wiring path Spring Boot uses.
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // No property source whatsoever: every @Value falls back to its literal default,
            // including gmepay.scheme.ninepay.{private-key-pem,ninepay-public-key-pem} = ""
            // (the shipped placeholders) and verify-responses.
            context.registerBean(PropertySourcesPlaceholderConfigurer.class);
            context.registerBean(RestClient.Builder.class, (Supplier<RestClient.Builder>) () -> builder);
            context.registerBean(ObjectMapper.class, (Supplier<ObjectMapper>) ObjectMapper::new);
            context.registerBean(NinepaySigner.class, (Supplier<NinepaySigner>) () -> halfKeyed);
            // The ONLY bean whose @Value defaults are under test.
            context.registerBean(NinepayApiClient.class);
            context.refresh();

            assertFalse(halfKeyed.canVerify(),
                    "precondition: the shipped config has no 9Pay public key");

            NinepayApiClient client = context.getBean(NinepayApiClient.class);

            String body = "{\"success\":true,\"data\":{\"request_id\":\"L1\",\"partner_id\":\"GMEPAY\","
                    + "\"transaction_id\":\"9P1\",\"request_amount\":50000,\"transfer_amount\":50500,"
                    + "\"status\":\"SUCCESS\",\"created_at\":\"2026-07-27 10:00:00\","
                    + "\"signature\":\"YW55LXNpZ25hdHVyZQ==\"}}";
            server.expect(requestTo(BASE + "/service/transfer/info"))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

            // Had the default stayed false, this would have returned SUCCESS on trust alone.
            NinepayTransportException ex = assertThrows(NinepayTransportException.class,
                    () -> client.transferInfoByRequestId("L1", "GMEPAY9P2026072700001"));
            assertTrue(ex.getMessage().contains("cannot be verified"), ex.getMessage());
        }
    }
}
