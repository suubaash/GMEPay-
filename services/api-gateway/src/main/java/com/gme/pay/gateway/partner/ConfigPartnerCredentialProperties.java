package com.gme.pay.gateway.partner;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Externalised partner-credential table, bound from {@code gateway.partner-credentials.*}.
 *
 * <p>This is the config-backed credential source the gateway uses in environments that have
 * no live config-registry / DB integration yet: operators enter partner rows in
 * {@code application-*.yml} (or env / Spring Cloud Config) instead of recompiling the
 * hard-coded {@link StubPartnerCredentialService}. {@link ConfigPartnerCredentialService}
 * activates over the stub when {@code gateway.partner-credentials.source=config}; the stub
 * remains the default fallback so the service still boots standalone.
 *
 * <p>Example:
 * <pre>
 * gateway:
 *   partner-credentials:
 *     source: config
 *     partners:
 *       - api-key: pk_live_acme
 *         partner-id: partner_acme
 *         hmac-secret: ${ACME_HMAC_SECRET}
 *         type: OVERSEAS
 *         rate-quote-ttl-seconds: 300
 *         ip-cidr-ranges: ["203.0.113.0/24"]
 *         mtls-cert-fingerprint: "aabb...."
 * </pre>
 *
 * <p>Secrets ({@code hmac-secret}) must come from a placeholder / environment variable in real
 * deployments — never a literal in a checked-in file.
 */
@ConfigurationProperties(prefix = "gateway.partner-credentials")
public class ConfigPartnerCredentialProperties {

    /** Which credential source is active: {@code stub} (default) or {@code config}. */
    private String source = "stub";

    /** The partner rows, keyed by {@code apiKey} at load time. */
    private List<PartnerEntry> partners = new ArrayList<>();

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public List<PartnerEntry> getPartners() {
        return partners;
    }

    public void setPartners(List<PartnerEntry> partners) {
        this.partners = partners;
    }

    /** One partner credential row from config. */
    public static class PartnerEntry {
        private String apiKey;
        private String partnerId;
        private String hmacSecret;
        private List<String> ipCidrRanges = new ArrayList<>();
        private PartnerCredentials.PartnerType type = PartnerCredentials.PartnerType.OVERSEAS;
        private int rateQuoteTtlSeconds = 300;
        private String mtlsCertFingerprint;

        public PartnerCredentials toCredentials() {
            return new PartnerCredentials(
                    partnerId,
                    apiKey,
                    hmacSecret,
                    ipCidrRanges == null ? List.of() : List.copyOf(ipCidrRanges),
                    type,
                    rateQuoteTtlSeconds,
                    mtlsCertFingerprint);
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getPartnerId() {
            return partnerId;
        }

        public void setPartnerId(String partnerId) {
            this.partnerId = partnerId;
        }

        public String getHmacSecret() {
            return hmacSecret;
        }

        public void setHmacSecret(String hmacSecret) {
            this.hmacSecret = hmacSecret;
        }

        public List<String> getIpCidrRanges() {
            return ipCidrRanges;
        }

        public void setIpCidrRanges(List<String> ipCidrRanges) {
            this.ipCidrRanges = ipCidrRanges;
        }

        public PartnerCredentials.PartnerType getType() {
            return type;
        }

        public void setType(PartnerCredentials.PartnerType type) {
            this.type = type;
        }

        public int getRateQuoteTtlSeconds() {
            return rateQuoteTtlSeconds;
        }

        public void setRateQuoteTtlSeconds(int rateQuoteTtlSeconds) {
            this.rateQuoteTtlSeconds = rateQuoteTtlSeconds;
        }

        public String getMtlsCertFingerprint() {
            return mtlsCertFingerprint;
        }

        public void setMtlsCertFingerprint(String mtlsCertFingerprint) {
            this.mtlsCertFingerprint = mtlsCertFingerprint;
        }
    }
}
