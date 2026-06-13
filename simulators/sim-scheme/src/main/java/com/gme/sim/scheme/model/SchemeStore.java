package com.gme.sim.scheme.model;

import com.gme.sim.scheme.config.SchemeConfig;
import com.gme.sim.scheme.config.SchemeProfile;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory store for merchants, CPM tokens, and payments.
 * Seeds two demo merchants on startup.
 */
@Component
public class SchemeStore {

    private final SchemeConfig schemeConfig;

    private final ConcurrentHashMap<String, MerchantRecord>  merchants = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CpmTokenRecord>  cpmTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PaymentRecord>   payments  = new ConcurrentHashMap<>();

    public SchemeStore(SchemeConfig schemeConfig) {
        this.schemeConfig = schemeConfig;
    }

    @PostConstruct
    void seedDemoMerchants() {
        SchemeProfile p = schemeConfig.getProfile();
        if (p == SchemeProfile.KHQR) {
            saveMerchant(new MerchantRecord("KHQR-M001", "Angkor Coffee",    "Siem Reap",     "5812"));
            saveMerchant(new MerchantRecord("KHQR-M002", "Phnom Penh Mart",  "Phnom Penh",    "5411"));
        } else if (p == SchemeProfile.ZEROPAY) {
            saveMerchant(new MerchantRecord("ZP-M001", "Seoul Noodle House", "Seoul",         "5812"));
            saveMerchant(new MerchantRecord("ZP-M002", "Busan Fish Market",  "Busan",         "5411"));
        }
    }

    // --- Merchants ---

    public void saveMerchant(MerchantRecord merchant) {
        merchants.put(merchant.merchantId(), merchant);
    }

    public Optional<MerchantRecord> findMerchant(String merchantId) {
        return Optional.ofNullable(merchants.get(merchantId));
    }

    public Collection<MerchantRecord> allMerchants() {
        return merchants.values();
    }

    // --- CPM Tokens ---

    public void saveCpmToken(CpmTokenRecord token) {
        cpmTokens.put(token.token(), token);
    }

    public Optional<CpmTokenRecord> findCpmToken(String token) {
        return Optional.ofNullable(cpmTokens.get(token));
    }

    // --- Payments ---

    public void savePayment(PaymentRecord payment) {
        payments.put(payment.getAuthId(), payment);
    }

    public Optional<PaymentRecord> findPayment(String authId) {
        return Optional.ofNullable(payments.get(authId));
    }
}
