package com.gme.pay.gateway.partner;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Externalised partner-credential table, bound from {@code gateway.partner-credentials.*}.
 *
 * <p>This is where the gateway gets HMAC signing material and per-partner edge policy from
 * (see {@link AuthIdentityVerifiedPartnerCredentialService} for why signing material cannot come
 * from auth-identity's {@code api_keys} table). Operators enter partner rows through env /
 * Spring Cloud Config / a mounted file; the table is empty by default, and an empty table
 * authenticates nobody.
 *
 * <p><b>T0-7:</b> {@link #getSource()} used to default to {@code stub}, selecting a hard-coded map
 * of api keys and HMAC secrets that were <em>published in this repository</em>. That bean no longer
 * exists in the shipped build and {@code stub} is no longer an accepted value — see
 * {@link PartnerCredentialConfig}.
 *
 * <p>Example (values from the environment, never literals):
 * <pre>
 * gateway:
 *   partner-credentials:
 *     source: config
 *     partners:
 *       - api-key: ${ACME_API_KEY}          # the pk_… id auth-identity issued
 *         partner-id: partner_acme          # config-registry partner CODE (allowlist key)
 *         hmac-secret: ${ACME_HMAC_SECRET}  # the one-time sk_… plaintext from issuance
 *         type: OVERSEAS
 *         rate-quote-ttl-seconds: 300
 *         ip-cidr-ranges: ["203.0.113.0/24"]
 *         mtls-cert-fingerprint: ${ACME_MTLS_FINGERPRINT}
 * </pre>
 *
 * <p>Secrets ({@code hmac-secret}) must come from a placeholder / environment variable in real
 * deployments — never a literal in a checked-in file. {@link ConfigPartnerCredentialService} logs
 * (without values) how many rows loaded, so a silently-empty mount is visible at boot.
 */
@ConfigurationProperties(prefix = "gateway.partner-credentials")
public class ConfigPartnerCredentialProperties {

    /** The only credential source in a shipped build; {@code stub} is rejected at startup. */
    public static final String SOURCE_CONFIG = "config";

    /**
     * The removed source. Named here so {@link PartnerCredentialConfig} can produce a specific
     * error rather than a generic "unknown value", and so a grep for it lands on the explanation.
     */
    public static final String SOURCE_STUB = "stub";

    /**
     * Which credential source supplies HMAC signing material. {@code config} (the default and the
     * only accepted value) reads {@link #getPartners()}. {@code stub} is rejected: the bean it named
     * carried api keys and secrets published in git.
     */
    private String source = SOURCE_CONFIG;

    /** The partner rows, keyed by {@code apiKey} at load time. */
    private List<PartnerEntry> partners = new ArrayList<>();

    /**
     * Whether every presented api key must also be ACTIVE in auth-identity's {@code api_keys}
     * store (T0-7). Default {@code true}, and turning it off is a deliberate, logged downgrade:
     * with it off, revoking a partner key upstream no longer stops the key working at the edge.
     * Exists only so a gateway can be exercised in isolation (no auth-identity in the fleet) —
     * {@link PartnerCredentialConfig} logs a WARN naming the consequence when it is false.
     */
    private boolean verifyWithAuthIdentity = true;

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

    public boolean isVerifyWithAuthIdentity() {
        return verifyWithAuthIdentity;
    }

    public void setVerifyWithAuthIdentity(boolean verifyWithAuthIdentity) {
        this.verifyWithAuthIdentity = verifyWithAuthIdentity;
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
