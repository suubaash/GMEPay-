package com.gme.pay.merchant.domain;

import java.util.List;

/**
 * Optional capability for repositories that can enumerate their stored merchants,
 * enabling full-list reconciliation (orphan deactivation) for ZP0051 / ZP0053.
 *
 * <p>Kept separate from {@link MerchantRepository} so the read-only lookup port stays
 * minimal: callers that only resolve by QR code depend on {@link MerchantRepository},
 * while the full-sync path checks {@code instanceof ReconcilableMerchantRepository}
 * and degrades to upsert-only when the backing store cannot enumerate.
 */
public interface ReconcilableMerchantRepository {

    /** Returns all currently-stored merchant records (active and inactive). */
    List<Merchant> findAll();
}
