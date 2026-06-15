package com.gme.sim.merchant.model;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory store for registered shops.
 */
@Component
public class ShopStore {

    private final Map<String, ShopRecord> shops = new ConcurrentHashMap<>();

    public void save(ShopRecord shop) {
        shops.put(shop.merchantId(), shop);
    }

    public Optional<ShopRecord> find(String merchantId) {
        return Optional.ofNullable(shops.get(merchantId));
    }

    public List<ShopRecord> findAll() {
        return new ArrayList<>(shops.values());
    }
}
