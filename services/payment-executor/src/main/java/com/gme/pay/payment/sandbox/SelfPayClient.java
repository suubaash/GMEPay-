package com.gme.pay.payment.sandbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.payment.client.rest.RestClientSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Loopback HTTP client the sandbox E2E runner uses to drive this service's OWN payment endpoints
 * (`POST /v1/pay/classify`, `POST /v1/pay`) over the REAL wire path, against a configurable self
 * base-url ({@code gmepay.self.base-url}, default {@code http://localhost:8080}).
 *
 * <p>Unlike the other {@code Rest*Client}s this one does NOT throw on a non-2xx response — the
 * runner needs the status + raw body of a 422 decline to record a meaningful step detail. Both
 * calls therefore return a small {@link Result} carrying {@code httpStatus}, the parsed body and
 * the raw body string.
 */
@Component
public class SelfPayClient {

    private final RestClient restClient;
    private final ObjectMapper mapper;

    @Autowired
    public SelfPayClient(RestClient.Builder builder,
                         @Value("${gmepay.self.base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = RestClientSupport.withJavaTime(builder.clone()).baseUrl(baseUrl).build();
        this.mapper = new ObjectMapper();
    }

    /** Test constructor — inject a preconfigured RestClient (e.g. bound to MockMvc / a stub server). */
    public SelfPayClient(RestClient restClient) {
        this.restClient = restClient;
        this.mapper = new ObjectMapper();
    }

    /** POST /v1/pay/classify. */
    public Result<ClassifyResponse> classify(String qrPayload) {
        return exchange("/v1/pay/classify",
                new ClassifyRequest(qrPayload), ClassifyResponse.class);
    }

    /** POST /v1/pay. */
    public Result<PayResponse> pay(PayRequest request) {
        return exchange("/v1/pay", request, PayResponse.class);
    }

    private <T> Result<T> exchange(String uri, Object body, Class<T> type) {
        var spec = restClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
        return spec.exchange((req, res) -> {
            int status = res.getStatusCode().value();
            String raw = new String(res.getBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            T parsed = null;
            if (raw != null && !raw.isBlank()) {
                try {
                    parsed = mapper.readValue(raw, type);
                } catch (Exception ignored) {
                    // Leave parsed null; the caller falls back to the raw body for the detail.
                }
            }
            return new Result<>(status, parsed, raw);
        });
    }

    /** HTTP outcome of a loopback call: status code, parsed body (nullable) and raw body. */
    public record Result<T>(int httpStatus, T body, String rawBody) {
    }

    // ---- wire formats ----

    record ClassifyRequest(String qrPayload) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClassifyResponse(
            boolean supported,
            String network,
            String country,
            String currency,
            String mode,
            String scheme
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PayRequest(
            String qrPayload,
            String amountKrw,
            String currency,
            String partner,
            String userRef
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PayResponse(
            String status,
            String schemeTxnRef,
            String txnRef,
            String declineReason
    ) {
    }
}
