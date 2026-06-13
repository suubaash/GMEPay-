package com.gme.sim.wallet.config;

import com.gme.sim.wallet.model.PartnerProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Centralised property bag for the wallet simulator.
 * Two constructors → @Autowired on the @Value one (Spring 6 trap).
 */
@Component
public class WalletProperties {

    private final PartnerProfile defaultPartner;
    private final String schemeBaseUrl;
    private final String rateBaseUrl;
    private final BigDecimal serviceFeKrw;
    private final BigDecimal sendmnFxMargin;

    @Autowired
    public WalletProperties(
            @Value("${gmepay.sim.wallet.default-partner:GMEREMIT}") String defaultPartner,
            @Value("${gmepay.sim.scheme.base-url:http://localhost:9102}") String schemeBaseUrl,
            @Value("${gmepay.sim.rate.base-url:http://localhost:9101}") String rateBaseUrl,
            @Value("${gmepay.sim.wallet.service-fee-krw:500}") String serviceFeKrw,
            @Value("${gmepay.sim.wallet.sendmn.fx-margin:0.02}") String sendmnFxMargin
    ) {
        this.defaultPartner = PartnerProfile.valueOf(defaultPartner.toUpperCase());
        this.schemeBaseUrl = schemeBaseUrl;
        this.rateBaseUrl = rateBaseUrl;
        this.serviceFeKrw = new BigDecimal(serviceFeKrw);
        this.sendmnFxMargin = new BigDecimal(sendmnFxMargin);
    }

    public PartnerProfile getDefaultPartner() { return defaultPartner; }
    public String getSchemeBaseUrl()           { return schemeBaseUrl; }
    public String getRateBaseUrl()             { return rateBaseUrl; }
    public BigDecimal getServiceFeeKrw()       { return serviceFeKrw; }
    public BigDecimal getSendmnFxMargin()      { return sendmnFxMargin; }
}
