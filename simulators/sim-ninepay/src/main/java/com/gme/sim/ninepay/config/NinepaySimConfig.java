package com.gme.sim.ninepay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Runtime configuration for the 9Pay simulator (prefix {@code sim.ninepay}).
 *
 * <p>Defaults are lenient/dev-friendly: keys are generated at startup, partner request
 * signatures are accepted unverified until a partner public key is configured (property
 * or {@code POST /sim/partner-key}), and the lifecycle ticks every ~2s.</p>
 */
@Configuration
@ConfigurationProperties(prefix = "sim.ninepay")
public class NinepaySimConfig {

    /** partner_id the sim accepts; anything else answers error 1001. */
    private String partnerId = "GMEPAY";

    /** RSA digest for request-verify AND response/IPN signing: SHA1/SHA224/SHA256/SHA384/SHA512. */
    private String signAlgorithm = "SHA256";

    /** Sim's own RSA-2048 private key (PEM PKCS#8). Blank = generate a dev keypair at startup. */
    private String privateKeyPem = "";

    /** Partner's RSA public key (PEM X.509). Blank = accept request signatures unverified. */
    private String partnerPublicKeyPem = "";

    /** Where terminal IPNs are pushed (the adapter's POST /scheme/ipn). */
    private String ipnUrl = "http://localhost:8096/scheme/ipn";

    /** Seeded prefunded balance, integer VND. */
    private long initialBalanceVnd = 5_000_000_000L;

    /** Flat per-payout 9Pay fee, integer VND. */
    private long feeVnd = 4_000L;

    /** Lifecycle timer step (PENDING -> PROCESSING -> terminal), ms. */
    private long lifecycleStepMs = 2_000L;

    /** Also advance one lifecycle step per /service/transfer/info poll. */
    private boolean advanceOnPoll = true;

    /** REVERSAL scenario: delay between SUCCESS IPN and the code-009 reversal IPN, ms. */
    private long reversalDelayMs = 3_000L;

    /** TIMEOUT scenario: how long /service/transfer stalls before answering 1063, ms. */
    private long timeoutHoldMs = 120_000L;

    public String getPartnerId() { return partnerId; }
    public void setPartnerId(String v) { this.partnerId = v; }

    public String getSignAlgorithm() { return signAlgorithm; }
    public void setSignAlgorithm(String v) { this.signAlgorithm = v; }

    public String getPrivateKeyPem() { return privateKeyPem; }
    public void setPrivateKeyPem(String v) { this.privateKeyPem = v; }

    public String getPartnerPublicKeyPem() { return partnerPublicKeyPem; }
    public void setPartnerPublicKeyPem(String v) { this.partnerPublicKeyPem = v; }

    public String getIpnUrl() { return ipnUrl; }
    public void setIpnUrl(String v) { this.ipnUrl = v; }

    public long getInitialBalanceVnd() { return initialBalanceVnd; }
    public void setInitialBalanceVnd(long v) { this.initialBalanceVnd = v; }

    public long getFeeVnd() { return feeVnd; }
    public void setFeeVnd(long v) { this.feeVnd = v; }

    public long getLifecycleStepMs() { return lifecycleStepMs; }
    public void setLifecycleStepMs(long v) { this.lifecycleStepMs = v; }

    public boolean isAdvanceOnPoll() { return advanceOnPoll; }
    public void setAdvanceOnPoll(boolean v) { this.advanceOnPoll = v; }

    public long getReversalDelayMs() { return reversalDelayMs; }
    public void setReversalDelayMs(long v) { this.reversalDelayMs = v; }

    public long getTimeoutHoldMs() { return timeoutHoldMs; }
    public void setTimeoutHoldMs(long v) { this.timeoutHoldMs = v; }
}
