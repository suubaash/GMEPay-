package com.gme.pay.gateway.partner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Config-backed {@link PartnerCredentialService}: resolves partners from
 * {@link ConfigPartnerCredentialProperties} ({@code gateway.partner-credentials.partners[]}).
 *
 * <p>Not a bean itself. {@link PartnerCredentialConfig} constructs it as the signing-material
 * source behind {@link AuthIdentityVerifiedPartnerCredentialService} — before T0-7 this class was
 * an opt-in {@code @Primary} override and the default was the hard-coded
 * {@code StubPartnerCredentialService}, whose api keys and secrets were published in git. Being a
 * plain class rather than a conditional bean is the point: there is now exactly one way the
 * credential source gets built, and it is explicit.
 *
 * <p>An entry with a blank {@code api-key} or a blank {@code hmac-secret} is skipped with a WARN:
 * a row without signing material cannot verify a signature, and admitting it would mean HMAC-ing
 * with an empty key.
 */
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
            // A blank hmac-secret would make HmacSignatureFilter sign with an empty key, which any
            // caller can reproduce. Drop the row rather than admit a forgeable partner (T0-7).
            if (entry.getHmacSecret() == null || entry.getHmacSecret().isBlank()) {
                log.error("Skipping config partner '{}' (partner-id={}): hmac-secret is blank, so no "
                        + "signature could be verified for it. Supply it from the environment.",
                        entry.getApiKey(), entry.getPartnerId());
                continue;
            }
            PartnerCredentials prev = store.put(entry.getApiKey(), entry.toCredentials());
            if (prev != null) {
                log.warn("Duplicate api-key '{}' in gateway.partner-credentials — last one wins",
                        entry.getApiKey());
            }
        }
        this.byApiKey = Map.copyOf(store);
        if (byApiKey.isEmpty()) {
            log.warn("ConfigPartnerCredentialService active with ZERO partner rows — the partner API "
                    + "will answer 401 INVALID_API_KEY to every caller. Populate "
                    + "gateway.partner-credentials.partners[] (api-key + hmac-secret from the "
                    + "environment) to admit a partner. This is the intended fail-closed default.");
        } else {
            log.info("ConfigPartnerCredentialService active: {} partner(s) loaded from config",
                    byApiKey.size());
        }
    }

    @Override
    public Mono<PartnerCredentials> findByApiKey(String apiKey) {
        PartnerCredentials creds = apiKey == null ? null : byApiKey.get(apiKey);
        return creds == null ? Mono.empty() : Mono.just(creds);
    }
}
