package com.gme.sim.gmeremit.model;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory store seeded with 3 demo users (₩500,000 each).
 */
@Component
public class WalletStore {

    private final Map<String, WalletUser> users = new LinkedHashMap<>();

    public WalletStore() {
        seed("user-001", "Alice Kim",   "500000");
        seed("user-002", "Bob Lee",     "500000");
        seed("user-003", "Chloe Park",  "500000");
    }

    private void seed(String id, String name, String balance) {
        users.put(id, new WalletUser(id, name, new BigDecimal(balance)));
    }

    public List<WalletUser> allUsers() {
        return List.copyOf(users.values());
    }

    public Optional<WalletUser> findUser(String userId) {
        return Optional.ofNullable(users.get(userId));
    }

    /** Re-seeds the store to its initial state. Used by tests only. */
    public synchronized void resetForTest() {
        users.clear();
        seed("user-001", "Alice Kim",  "500000");
        seed("user-002", "Bob Lee",    "500000");
        seed("user-003", "Chloe Park", "500000");
    }
}
