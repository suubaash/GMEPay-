package com.gme.pay.scheme.zeropay.adapter.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Static configuration for a registered scheme adapter (SCH-06 §1.3.2).
 * Credential fields are references into the secrets store — never plaintext values.
 */
public record SchemeConfig(
        String schemeId,
        String schemeName,
        String operatorName,
        String payoutCurrency,
        List<SupportedMode> supportedModes,
        List<String> supportedCountries,
        String realtimeApiBaseUrl,
        String sftpHost,
        int sftpPort,
        String sftpUsername,
        String sftpPrivateKeyRef,
        String pgpPublicKeyRef,
        String pgpPrivateKeyRef,
        String inboundDir,
        String outboundDir,
        int fileRetentionDays,
        Map<String, BigDecimal> merchantFeeTable,
        Map<String, BigDecimal> vanFeeTable,
        BigDecimal gmeFeeSharePct
) {

    public enum SupportedMode { MPM, CPM }
}
