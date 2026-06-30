package com.gme.pay.gateway.partner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConfigPartnerCredentialService}: config rows are resolvable by API key,
 * unknown keys return empty, all fields round-trip, and a blank api-key row is skipped.
 */
class ConfigPartnerCredentialServiceTest {

    private static ConfigPartnerCredentialProperties.PartnerEntry entry(
            String apiKey, String partnerId, String secret) {
        ConfigPartnerCredentialProperties.PartnerEntry e =
                new ConfigPartnerCredentialProperties.PartnerEntry();
        e.setApiKey(apiKey);
        e.setPartnerId(partnerId);
        e.setHmacSecret(secret);
        e.setIpCidrRanges(List.of("203.0.113.0/24"));
        e.setType(PartnerCredentials.PartnerType.LOCAL);
        e.setRateQuoteTtlSeconds(120);
        e.setMtlsCertFingerprint("deadbeef");
        return e;
    }

    @Test
    void resolvesConfiguredPartner_withAllFields() {
        ConfigPartnerCredentialProperties props = new ConfigPartnerCredentialProperties();
        props.setSource("config");
        props.setPartners(List.of(entry("pk_live_acme", "partner_acme", "s3cr3t")));

        ConfigPartnerCredentialService svc = new ConfigPartnerCredentialService(props);
        PartnerCredentials creds = svc.findByApiKey("pk_live_acme").block();

        assertEquals("partner_acme", creds.partnerId());
        assertEquals("pk_live_acme", creds.apiKeyHash());
        assertEquals("s3cr3t", creds.apiSecretHmacKey());
        assertEquals(List.of("203.0.113.0/24"), creds.ipCidrRanges());
        assertEquals(PartnerCredentials.PartnerType.LOCAL, creds.type());
        assertEquals(120, creds.rateQuoteTtlSeconds());
        assertEquals("deadbeef", creds.mtlsCertFingerprint());
    }

    @Test
    void unknownKeyReturnsEmpty() {
        ConfigPartnerCredentialProperties props = new ConfigPartnerCredentialProperties();
        props.setPartners(List.of(entry("pk_live_acme", "partner_acme", "s3cr3t")));

        ConfigPartnerCredentialService svc = new ConfigPartnerCredentialService(props);
        assertNull(svc.findByApiKey("pk_unknown").block());
        assertNull(svc.findByApiKey(null).block());
    }

    @Test
    void blankApiKeyRowIsSkipped() {
        ConfigPartnerCredentialProperties props = new ConfigPartnerCredentialProperties();
        props.setPartners(List.of(
                entry("", "partner_blank", "s"),
                entry("pk_ok", "partner_ok", "s")));

        ConfigPartnerCredentialService svc = new ConfigPartnerCredentialService(props);
        assertTrue(svc.findByApiKey("pk_ok").blockOptional().isPresent());
        assertNull(svc.findByApiKey("").block(), "blank api-key row must not be registered");
    }
}
