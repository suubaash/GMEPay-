package com.gme.pay.gateway.partner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Config-backed {@link PartnerCredentialService}: resolves partners from
 * {@link ConfigPartnerCredentialProperties} ({@code gateway.partner-credentials.partners[]})
 * instead of the hard-coded {@link StubPartnerCredentialService}.
 *
 * <p>Activated by {@code gateway.partner-credentials.source=config} ({@code @Primary} +
 * {@code @ConditionalOnProperty}, the same convention {@link com.gme.pay.gateway.registry.RestConfigRegistryClient}
 * uses). When that flag is absent the stub stays the default, so the gateway still boots
 * standalone for local dev and tests.
 *
 * <p>This is the genuine "config/DB-backed credential source with stub fallback" the gap plan
 * asks for, expressed at an interface boundary: it reads externalised config today and can be
 * swapped for an R2DBC/Redis implementation (T18) without touching any filter — the contract is
 * {@link PartnerCredentialService#findByApiKey}.
 */
@Service
@Primary
@ConditionalOnProperty(name = "gateway.partner-credentials.source", havingValue = "config")
public class ConfigPartnerCredentialService implements PartnerCredentialService {

    private static final Logger log = LoggerFactory.getLogger(ConfigPartnerCredentialService.class);

    private final Map<String, PartnerCredentials> byApiKey;

    public ConfigPartnerCredentialService(ConfigPartnerCredentialProperties props) {
        Map<String, PartnerCredentials> store = new LinkedHashMap<>();
        for (ConfigPartnerCredentialProperties.PartnerEntry entry : props.getPartners()) {
            if (entry.getApiKey() == null || entry.getApiKey().isBlank()) {
                log.warn("Skipping config partner with blank api-key (partner-id={})",
                        entry.getPartnerId());
                continue;
            }
            PartnerCredentials prev = store.put(entry.getApiKey(), entry.toCredentials());
            if (prev != null) {
                log.warn("Duplicate api-key '{}' in gateway.partner-credentials — last one wins",
                        entry.getApiKey());
            }
        }
        this.byApiKey = Map.copyOf(store);
        log.info("ConfigPartnerCredentialService active: {} partner(s) loaded from config",
                byApiKey.size());
    }

    @Override
    public Mono<PartnerCredentials> findByApiKey(String apiKey) {
        PartnerCredentials creds = apiKey == null ? null : byApiKey.get(apiKey);
        return creds == null ? Mono.empty() : Mono.just(creds);
    }
}
