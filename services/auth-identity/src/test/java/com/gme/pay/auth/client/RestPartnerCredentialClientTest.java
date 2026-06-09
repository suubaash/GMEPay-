package com.gme.pay.auth.client;

import com.gme.pay.auth.domain.PartnerCredentialPort.ResolvedCredential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit test for {@link RestPartnerCredentialClient} using Spring's
 * {@link MockRestServiceServer} (bundled with spring-boot-starter-test).
 *
 * Verifies that the client issues the expected GET, parses the JSON body
 * into the {@link ResolvedCredential} value object, and degrades to
 * {@link Optional#empty()} on 4xx responses.
 */
class RestPartnerCredentialClientTest {

    private static final String BASE_URL = "http://config-registry-test";

    private RestClient restClient;
    private MockRestServiceServer server;
    private RestPartnerCredentialClient client;

    @BeforeEach
    void setUp() {
        // Build a RestClient on a RestTemplate so MockRestServiceServer can bind to it.
        org.springframework.web.client.RestTemplate restTemplate =
                new org.springframework.web.client.RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();

        restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .requestFactory(restTemplate.getRequestFactory())
                .build();

        client = new RestPartnerCredentialClient(restClient);
    }

    @Test
    void findActiveByApiKey_knownPartner_returnsResolvedCredential() {
        String apiKey = "pk_live_abc";
        String body = """
                {
                  "partnerId": 42,
                  "hmacSecret": "test-secret-exactly-32-chars-here",
                  "active": true
                }
                """;

        server.expect(requestTo(BASE_URL + "/v1/partners/" + apiKey))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        Optional<ResolvedCredential> result = client.findActiveByApiKey(apiKey);

        assertThat(result).isPresent();
        assertThat(result.get().partnerId()).isEqualTo(42L);
        assertThat(result.get().hmacSecret()).isEqualTo("test-secret-exactly-32-chars-here");
        server.verify();
    }

    @Test
    void findActiveByApiKey_unknownPartner_returnsEmpty() {
        String apiKey = "pk_live_unknown";

        server.expect(requestTo(BASE_URL + "/v1/partners/" + apiKey))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        Optional<ResolvedCredential> result = client.findActiveByApiKey(apiKey);

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    void findActiveByApiKey_inactivePartner_returnsEmpty() {
        String apiKey = "pk_live_inactive";
        String body = """
                {
                  "partnerId": 99,
                  "hmacSecret": "doesnt-matter",
                  "active": false
                }
                """;

        server.expect(requestTo(BASE_URL + "/v1/partners/" + apiKey))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        Optional<ResolvedCredential> result = client.findActiveByApiKey(apiKey);

        assertThat(result).isEmpty();
        server.verify();
    }
}
