package com.gme.sim.nepalqr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime toggles for the Nepal QR simulator.
 *
 * All the security-ish checks (IP allowlist, nonce window) are OFF by default so the
 * mock is lenient and easy to drive from GMEPay+; they can be turned on per-test.
 */
@Configuration
@ConfigurationProperties(prefix = "gmepay.sim.nepalqr")
public class NepalQrConfig {

    /** When true, the validate API rejects source IPs not on {@link #allowedIps}. */
    private boolean ipAllowlistEnabled = false;

    /** Source IPs accepted when {@link #ipAllowlistEnabled} is true. */
    private List<String> allowedIps = new ArrayList<>();

    /** When true, X-KhaltiNonce must satisfy ServerTs-100 <= nonce <= ServerTs+200. */
    private boolean nonceWindowEnabled = false;

    /** Default outcome for /qrscan-thirdparty/pay/ : APPROVE | PENDING | REJECT. */
    private String defaultPayOutcome = "APPROVE";

    public boolean isIpAllowlistEnabled() { return ipAllowlistEnabled; }
    public void setIpAllowlistEnabled(boolean v) { this.ipAllowlistEnabled = v; }

    public List<String> getAllowedIps() { return allowedIps; }
    public void setAllowedIps(List<String> v) { this.allowedIps = v; }

    public boolean isNonceWindowEnabled() { return nonceWindowEnabled; }
    public void setNonceWindowEnabled(boolean v) { this.nonceWindowEnabled = v; }

    public String getDefaultPayOutcome() { return defaultPayOutcome; }
    public void setDefaultPayOutcome(String v) { this.defaultPayOutcome = v; }
}
