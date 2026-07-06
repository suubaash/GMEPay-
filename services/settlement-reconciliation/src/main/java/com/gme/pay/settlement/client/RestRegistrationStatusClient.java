package com.gme.pay.settlement.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.settlement.port.RegistrationStatusPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Production {@link RegistrationStatusPort}: consults scheme-adapter-zeropay's
 * {@code GET /internal/scheme/zeropay/registration-status?businessDate=} (the projection over its
 * {@code zp_batch_files} lifecycle rows). Active when
 * {@code settlement.clients.scheme-adapter-zeropay.enabled=true}; otherwise the permissive
 * in-process adapter applies (dev/test keep generating without a live adapter).
 *
 * <p><b>Fail-CLOSED:</b> if the adapter is unreachable or errors, registration status is UNKNOWN —
 * this returns {@code (false, false)} so the prerequisite gate blocks the settlement request
 * rather than asking the scheme to settle payments whose registration was never confirmed.
 */
@Component
@Primary
@ConditionalOnProperty(name = "settlement.clients.scheme-adapter-zeropay.enabled", havingValue = "true")
public class RestRegistrationStatusClient implements RegistrationStatusPort {

    private static final Logger log = LoggerFactory.getLogger(RestRegistrationStatusClient.class);

    private final RestClient restClient;

    @Autowired
    public RestRegistrationStatusClient(
            @Value("${settlement.clients.scheme-adapter-zeropay.base-url:http://scheme-adapter-zeropay:8080}")
            String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).build());
    }

    /** Package-private for tests to inject a pre-built RestClient. */
    RestRegistrationStatusClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public RegistrationStatus statusFor(LocalDate businessDate) {
        try {
            WireStatus wire = restClient.get()
                    .uri("/internal/scheme/zeropay/registration-status?businessDate={d}",
                            businessDate.format(DateTimeFormatter.ISO_LOCAL_DATE))
                    .retrieve()
                    .body(WireStatus.class);
            if (wire == null) {
                log.warn("registration-status returned empty body for {} — failing CLOSED", businessDate);
                return new RegistrationStatus(false, false);
            }
            return new RegistrationStatus(wire.zp0011Succeeded(), wire.zp0012Received());
        } catch (RestClientException e) {
            log.warn("registration-status unreachable for {} — failing CLOSED (settlement blocked): {}",
                    businessDate, e.getMessage());
            return new RegistrationStatus(false, false);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WireStatus(boolean zp0011Succeeded, boolean zp0012Received) {}
}
