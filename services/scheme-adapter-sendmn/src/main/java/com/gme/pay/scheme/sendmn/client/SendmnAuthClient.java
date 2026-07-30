package com.gme.pay.scheme.sendmn.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * SendMN session-token client — {@code POST /api/Authentication} with credential
 * <b>headers</b> ({@code Username}/{@code AgentCode}/{@code AuthKey}; no body) →
 * {@code detail.token}. Response is <b>plain JSON</b> (not the encryptedData envelope).
 *
 * <p>Token is valid for 90 minutes; we cache it and proactively refresh after
 * {@code sendmn.token.refresh-after-minutes} (default 80). {@link #invalidate()} forces
 * a re-auth on the next call — used by {@link SendmnSchemeApiClient} when SendMN answers
 * {@code S102} (token invalid) / {@code S104} (token expired).</p>
 */
@Component
public class SendmnAuthClient {

    private final RestClient restClient;
    private final String username;
    private final String agentCode;
    private final String authKey;
    private final Duration refreshAfter;
    private final Clock clock;

    private final Object lock = new Object();
    private String cachedToken;
    private Instant fetchedAt;

    /** Primary constructor — wired by Spring. {@code @Autowired} required (2+ ctors). */
    @Autowired
    public SendmnAuthClient(
            RestClient.Builder builder,
            @Value("${sendmn.base-url:http://localhost:9106}") String baseUrl,
            @Value("${sendmn.username:sendmn-username-placeholder}") String username,
            @Value("${sendmn.agent-code:sendmn-agentcode-placeholder}") String agentCode,
            @Value("${sendmn.auth-key:sendmn-authkey-placeholder}") String authKey,
            @Value("${sendmn.token.refresh-after-minutes:80}") long refreshAfterMinutes) {
        // T3-11: the outbound read timeout is deliberately NOT installed here. It comes from the
        // shared RestClient.Builder, which com.gme.pay.http.HttpClientTimeoutAutoConfiguration bounds
        // fleet-wide and which this adapter tightens to 4s via gmepay.http.client.read-timeout in its
        // own config file, where the number and its nesting inside payment-executor's 5s hub->adapter
        // budget are explained.
        //
        // Setting it on this builder instead would be actively harmful in one specific way worth
        // recording: MockRestServiceServer.bindTo(builder) works by INSTALLING A REQUEST FACTORY, so a
        // client that overwrites the factory after binding silently detaches from the mock and starts
        // opening real sockets. Configuration bounds the socket without disturbing that seam.
        this(builder.baseUrl(baseUrl).build(),
                username, agentCode, authKey, refreshAfterMinutes, Clock.systemUTC());
    }

    /** Package-private test constructor — accepts a pre-built RestClient + clock. */
    SendmnAuthClient(RestClient restClient, String username, String agentCode, String authKey,
                     long refreshAfterMinutes, Clock clock) {
        this.restClient = restClient;
        this.username = username;
        this.agentCode = agentCode;
        this.authKey = authKey;
        this.refreshAfter = Duration.ofMinutes(refreshAfterMinutes);
        this.clock = clock;
    }

    /** Returns a valid session token, authenticating (or refreshing) when needed. */
    public String getToken() {
        synchronized (lock) {
            if (cachedToken != null && fetchedAt != null
                    && Duration.between(fetchedAt, clock.instant()).compareTo(refreshAfter) < 0) {
                return cachedToken;
            }
            cachedToken = authenticate();
            fetchedAt = clock.instant();
            return cachedToken;
        }
    }

    /** Drops the cached token so the next {@link #getToken()} re-authenticates. */
    public void invalidate() {
        synchronized (lock) {
            cachedToken = null;
            fetchedAt = null;
        }
    }

    public String username() {
        return username;
    }

    public String agentCode() {
        return agentCode;
    }

    private String authenticate() {
        AuthResponse resp;
        try {
            resp = restClient.post()
                    .uri("/api/Authentication")
                    .header("Username", username)
                    .header("AgentCode", agentCode)
                    .header("AuthKey", authKey)
                    .retrieve()
                    .body(AuthResponse.class);
        } catch (RestClientResponseException ex) {
            throw new ApiException(ErrorCode.SCHEME_UNAVAILABLE,
                    "sendmn authentication HTTP " + ex.getStatusCode().value() + ": "
                            + ex.getResponseBodyAsString());
        } catch (ResourceAccessException ex) {
            throw new ApiException(ErrorCode.SCHEME_UNAVAILABLE,
                    "sendmn unreachable during authentication: " + ex.getMessage());
        }
        if (resp == null || resp.detail() == null || resp.detail().token() == null
                || !"0".equals(resp.code())) {
            throw new ApiException(ErrorCode.SCHEME_UNAVAILABLE,
                    "sendmn authentication rejected: code="
                            + (resp == null ? "<empty>" : resp.code())
                            + " message=" + (resp == null ? "" : resp.message()));
        }
        return resp.detail().token();
    }

    // ------------------------------------------------------------------- wire DTOs

    /** POST /api/Authentication response (plain JSON, no envelope). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuthResponse(String code, String message, Detail detail) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Detail(String token, String note, String processId) {}
    }
}
