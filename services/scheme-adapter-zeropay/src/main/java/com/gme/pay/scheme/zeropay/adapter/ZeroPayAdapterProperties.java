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
