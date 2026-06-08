package com.gme.pay.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Utility that writes a canonical GMEPay+ error envelope to the HTTP response and
 * short-circuits the filter chain.
 *
 * <p>Envelope shape (API-05 §8.1):
 * <pre>
 * {
 *   "error": {
 *     "code":       "INVALID_SIGNATURE",
 *     "message":    "...",
 *     "request_id": "req_...",
 *     "details":    []
 *   }
 * }
 * </pre>
 */
public final class GatewayErrorWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GatewayErrorWriter() {}

    /**
     * Write an error response and complete the exchange.
     *
     * @param exchange  current server exchange
     * @param status    HTTP status to set
     * @param errorCode API-05 error code string (e.g. {@code "INVALID_SIGNATURE"})
     * @param message   human-readable message
     * @param details   list of field-level error detail maps; pass an empty list for no details
     * @return a {@link Mono<Void>} that completes when the response is flushed
     */
    public static Mono<Void> writeError(
            ServerWebExchange exchange,
            HttpStatus status,
            String errorCode,
            String message,
            List<Map<String, String>> details) {

        String requestId = exchange.getResponse().getHeaders().getFirst("X-Request-ID");
        if (requestId == null) {
            requestId = "req_unknown";
        }

        Map<String, Object> errorBody = Map.of(
                "error", Map.of(
                        "code", errorCode,
                        "message", message,
                        "request_id", requestId,
                        "details", details));

        String json;
        try {
            json = MAPPER.writeValueAsString(errorBody);
        } catch (JsonProcessingException e) {
            json = "{\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"serialization failure\"}}";
        }

        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        DataBuffer buffer = exchange.getResponse()
                .bufferFactory()
                .wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /** Convenience overload with no field-level details. */
    public static Mono<Void> writeError(
            ServerWebExchange exchange,
            HttpStatus status,
            String errorCode,
            String message) {
        return writeError(exchange, status, errorCode, message, List.of());
    }
}
