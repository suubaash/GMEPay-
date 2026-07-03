package com.gme.pay.bff.client.rest;

import com.gme.pay.bff.client.PlatformSettingsClient;
import com.gme.pay.bff.web.dto.PlatformSettingView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Production {@link PlatformSettingsClient}. Talks to config-registry's platform-settings
 * endpoints over Spring 6 {@link RestClient}. Active when
 * {@code gmepay.config-registry.client=rest} (same activation convention as
 * {@link RestConfigRegistryClient}); otherwise the in-memory
 * {@link com.gme.pay.bff.client.stub.StubPlatformSettingsClient} wins.
 *
 * <p>Endpoint mapping (config-registry PlatformSettingController):
 * <ul>
 *   <li>{@code GET /v1/admin/settings}        -> {@link #list()}</li>
 *   <li>{@code GET /v1/admin/settings/{key}}  -> {@link #get(String)}</li>
 *   <li>{@code PUT /v1/admin/settings/{key}}  -> {@link #update(String, String, String)}</li>
 * </ul>
 *
 * <p>Upstream 4xx (404 unknown key, 400 NUMBER validation) propagate as
 * {@link org.springframework.web.client.RestClientResponseException} so the controller
 * surfaces the rejection to the admin UI rather than masking it.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.config-registry.client", havingValue = "rest")
public class RestPlatformSettingsClient implements PlatformSettingsClient {

    private static final Logger log = LoggerFactory.getLogger(RestPlatformSettingsClient.class);

    private final RestClient restClient;

    @Autowired
    public RestPlatformSettingsClient(
            @Value("${gmepay.config-registry.base-url:http://config-registry:8080}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).build());
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestPlatformSettingsClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public List<PlatformSettingView> list() {
        List<PlatformSettingView> body = restClient.get()
                .uri("/v1/admin/settings")
                .retrieve()
                .body(new ParameterizedTypeReference<List<PlatformSettingView>>() { });
        return body == null ? List.of() : body;
    }

    @Override
    public PlatformSettingView get(String key) {
        return restClient.get()
                .uri("/v1/admin/settings/{key}", key)
                .retrieve()
                .body(PlatformSettingView.class);
    }

    @Override
    public PlatformSettingView update(String key, String value, String updatedBy) {
        Map<String, String> body = new HashMap<>();
        body.put("value", value);
        if (updatedBy != null && !updatedBy.isBlank()) {
            body.put("updatedBy", updatedBy);
        }
        return restClient.put()
                .uri("/v1/admin/settings/{key}", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(PlatformSettingView.class);
    }
}
