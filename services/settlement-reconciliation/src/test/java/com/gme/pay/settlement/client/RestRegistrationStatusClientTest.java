package com.gme.pay.settlement.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gme.pay.internalauth.InternalAuthHeaders;
import com.gme.pay.settlement.port.RegistrationStatusPort.RegistrationStatus;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link MockRestServiceServer} tests for {@link RestRegistrationStatusClient} — the §8.2
 * registration prerequisite probe against scheme-adapter-zeropay's
 * {@code GET /internal/scheme/zeropay/registration-status}.
 *
 * <p>The T0-2 point: that path is now behind the adapter's internal-auth gate, so the probe must
 * present {@code X-Gme-Internal}. Because the client fails CLOSED on any error, a missing token does
 * not degrade gracefully — it blocks ZP0061/ZP0063 generation outright, which is why the 401 case is
 * pinned here explicitly.
 */
class RestRegistrationStatusClientTest {

    /** Test fixture, not a credential — never read outside the test source set. */
    private static final String INTERNAL_TOKEN = "fixture-token-not-a-deployment-secret";

    private static final String BASE = "http://scheme-adapter-zeropay:8080";
    private static final LocalDate DATE = LocalDate.of(2026, 7, 28);
    private static final String URL =
            BASE + "/internal/scheme/zeropay/registration-status?businessDate=2026-07-28";

    @Test
    @DisplayName("presents the configured internal token and maps the projection")
    void carriesTheInternalTokenAndMapsStatus() {
        RestClient.Builder b = RestRegistrationStatusClient.builderFor(
                RestClient.builder(), BASE, INTERNAL_TOKEN);
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestRegistrationStatusClient client = new RestRegistrationStatusClient(b.build());

        server.expect(requestTo(URL))
                .andExpect(method(GET))
                .andExpect(header(InternalAuthHeaders.INTERNAL_TOKEN, INTERNAL_TOKEN))
                .andRespond(withSuccess(
                        "{\"zp0011Succeeded\":true,\"zp0012Received\":true}",
                        MediaType.APPLICATION_JSON));

        RegistrationStatus status = client.statusFor(DATE);
        server.verify();
        assertThat(status.zp0011Succeeded()).isTrue();
        assertThat(status.zp0012Received()).isTrue();
    }

    @Test
    @DisplayName("a blank secret sends NO token — fail-closed, never a fabricated credential")
    void blankSecretSendsNoToken() {
        RestClient.Builder b = RestRegistrationStatusClient.builderFor(
                RestClient.builder(), BASE, "  ");
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestRegistrationStatusClient client = new RestRegistrationStatusClient(b.build());

        server.expect(requestTo(URL))
                .andExpect(headerDoesNotExist(InternalAuthHeaders.INTERNAL_TOKEN))
                .andRespond(withSuccess(
                        "{\"zp0011Succeeded\":true,\"zp0012Received\":true}",
                        MediaType.APPLICATION_JSON));

        client.statusFor(DATE);
        server.verify();
    }

    @Test
    @DisplayName("a gated adapter's 401 fails CLOSED — settlement generation blocked, not permitted")
    void unauthorizedFailsClosed() {
        RestClient.Builder b = RestRegistrationStatusClient.builderFor(
                RestClient.builder(), BASE, "");
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestRegistrationStatusClient client = new RestRegistrationStatusClient(b.build());

        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        RegistrationStatus status = client.statusFor(DATE);
        server.verify();
        assertThat(status.zp0011Succeeded()).isFalse();
        assertThat(status.zp0012Received()).isFalse();
    }

    @Test
    @DisplayName("adapter 5xx fails CLOSED as well")
    void serverErrorFailsClosed() {
        RestClient.Builder b = RestRegistrationStatusClient.builderFor(
                RestClient.builder(), BASE, INTERNAL_TOKEN);
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestRegistrationStatusClient client = new RestRegistrationStatusClient(b.build());

        server.expect(requestTo(URL)).andRespond(withServerError());

        RegistrationStatus status = client.statusFor(DATE);
        server.verify();
        assertThat(status.zp0011Succeeded()).isFalse();
        assertThat(status.zp0012Received()).isFalse();
    }
}
