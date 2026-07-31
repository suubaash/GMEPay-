package com.gme.sim.sendmn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/** {@code gmepay.sim.sendmn.*} — credentials, token lifetime, FX default, scenario seeds. */
@ConfigurationProperties(prefix = "gmepay.sim.sendmn")
public class SimSendmnProperties {

    private String username = "sendmn-username-placeholder";
    private String agentCode = "sendmn-agentcode-placeholder";
    private String authKey = "sendmn-authkey-placeholder";
    private long tokenValidityMinutes = 90;
    private BigDecimal defaultRate = new BigDecimal("3373.00");
    private int approveAfterPolls = 1;
    private boolean forceError304 = false;
    private boolean forceError307 = false;
    private boolean neverApprove = false;
    private long delayMillis = 0;
    private final FxPush fxPush = new FxPush();

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getAgentCode() { return agentCode; }
    public void setAgentCode(String agentCode) { this.agentCode = agentCode; }

    public String getAuthKey() { return authKey; }
    public void setAuthKey(String authKey) { this.authKey = authKey; }

    public long getTokenValidityMinutes() { return tokenValidityMinutes; }
    public void setTokenValidityMinutes(long tokenValidityMinutes) { this.tokenValidityMinutes = tokenValidityMinutes; }

    public BigDecimal getDefaultRate() { return defaultRate; }
    public void setDefaultRate(BigDecimal defaultRate) { this.defaultRate = defaultRate; }

    public int getApproveAfterPolls() { return approveAfterPolls; }
    public void setApproveAfterPolls(int approveAfterPolls) { this.approveAfterPolls = approveAfterPolls; }

    public boolean isForceError304() { return forceError304; }
    public void setForceError304(boolean forceError304) { this.forceError304 = forceError304; }

    public boolean isForceError307() { return forceError307; }
    public void setForceError307(boolean forceError307) { this.forceError307 = forceError307; }

    public boolean isNeverApprove() { return neverApprove; }
    public void setNeverApprove(boolean neverApprove) { this.neverApprove = neverApprove; }

    public long getDelayMillis() { return delayMillis; }
    public void setDelayMillis(long delayMillis) { this.delayMillis = delayMillis; }

    public FxPush getFxPush() { return fxPush; }

    /** Optional SendMN→partner rate registration push (doc §9 direction). */
    public static class FxPush {
        private boolean enabled = false;
        private String url = "http://localhost:8093/partner-hosted/fx-rate";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }
}
