package com.gme.pay.scheme.zeropay.adapter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Spring {@code @ConfigurationProperties} binding for the ZeroPay adapter.
 * All values are bound from the {@code adapter.zeropay.*} namespace in
 * {@code application.yml}.
 */
@Component
@ConfigurationProperties(prefix = "adapter.zeropay")
public class ZeroPayAdapterProperties {

    private boolean enabled = false;
    private String schemeId = "ZEROPAY";
    private String schemeName = "ZeroPay";
    private String operatorName = "KFTC";
    private String payoutCurrency = "KRW";
    private String realtimeApiBaseUrl = "";
    private String sftpHost = "";
    private int sftpPort = 22;
    private String maiTag = "29";

    /** Institution code embedded in ZP00xx batch file headers. Defaults to "GME001". */
    private String institutionCode = "GME001";

    /** KST batch window enable flag (default false — scheduler fires only when true). */
    private boolean batchEnabled = false;

    /**
     * Real-time transport for the MPM/CPM payment path: {@code REST} (default) routes through the
     * sim-scheme REST simulator; {@code TCP} uses the real ZeroPay 전문-over-TCP transport
     * ({@code ZeroPayTcpTransport}). Switching is config-only — the SchemeClient contract is unchanged.
     */
    private String transport = "REST";

    /**
     * GME's KFTC 결제사업자 (payment-operator) code stamped into 전문 field 23 (requesting_org,
     * 3-char AN). Distinct from {@link #institutionCode} (the 6-char batch-file header code).
     */
    private String requestingOrg = "GME";

    /** ZeroPay 전문/TCP relay host (used when {@code transport=TCP}). */
    private String tcpHost = "";

    /** ZeroPay 전문/TCP relay port (used when {@code transport=TCP}). */
    private int tcpPort = 0;

    /** TCP connect timeout (ms) for the 전문 transport. */
    private int tcpConnectTimeoutMs = 5000;

    /** TCP read timeout (ms) for awaiting the 0210 response 전문. */
    private int tcpReadTimeoutMs = 15000;

    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }

    public String getRequestingOrg() { return requestingOrg; }
    public void setRequestingOrg(String requestingOrg) { this.requestingOrg = requestingOrg; }

    public String getTcpHost() { return tcpHost; }
    public void setTcpHost(String tcpHost) { this.tcpHost = tcpHost; }

    public int getTcpPort() { return tcpPort; }
    public void setTcpPort(int tcpPort) { this.tcpPort = tcpPort; }

    public int getTcpConnectTimeoutMs() { return tcpConnectTimeoutMs; }
    public void setTcpConnectTimeoutMs(int tcpConnectTimeoutMs) {
        this.tcpConnectTimeoutMs = tcpConnectTimeoutMs;
    }

    public int getTcpReadTimeoutMs() { return tcpReadTimeoutMs; }
    public void setTcpReadTimeoutMs(int tcpReadTimeoutMs) {
        this.tcpReadTimeoutMs = tcpReadTimeoutMs;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getSchemeId() { return schemeId; }
    public void setSchemeId(String schemeId) { this.schemeId = schemeId; }

    public String getSchemeName() { return schemeName; }
    public void setSchemeName(String schemeName) { this.schemeName = schemeName; }

    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }

    public String getPayoutCurrency() { return payoutCurrency; }
    public void setPayoutCurrency(String payoutCurrency) { this.payoutCurrency = payoutCurrency; }

    public String getRealtimeApiBaseUrl() { return realtimeApiBaseUrl; }
    public void setRealtimeApiBaseUrl(String realtimeApiBaseUrl) {
        this.realtimeApiBaseUrl = realtimeApiBaseUrl;
    }

    public String getSftpHost() { return sftpHost; }
    public void setSftpHost(String sftpHost) { this.sftpHost = sftpHost; }

    public int getSftpPort() { return sftpPort; }
    public void setSftpPort(int sftpPort) { this.sftpPort = sftpPort; }

    public String getMaiTag() { return maiTag; }
    public void setMaiTag(String maiTag) { this.maiTag = maiTag; }

    public String getInstitutionCode() { return institutionCode; }
    public void setInstitutionCode(String institutionCode) { this.institutionCode = institutionCode; }

    public boolean isBatchEnabled() { return batchEnabled; }
    public void setBatchEnabled(boolean batchEnabled) { this.batchEnabled = batchEnabled; }
}
