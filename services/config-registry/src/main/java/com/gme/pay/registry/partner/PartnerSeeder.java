package com.gme.pay.registry.partner;

import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.RoundingMode;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds the two bootstrap partners (GMEREMIT, SENDMN) on startup if the
 * {@code partners} table is empty. Previously the seed lived inline in
 * {@link PartnerStore}'s constructor when storage was an in-memory map; with a
 * persistent backing store it must be idempotent across restarts, hence the
 * empty-table guard rather than upsert-on-boot.
 *
 * <p>The seeded rows go through {@link Partner#of(String, PartnerType, String,
 * RoundingMode)} which carries the {@code partnerCode} (e.g. {@code "GMEREMIT"})
 * and leaves the surrogate id {@code null}; V003's {@code partners_id_seq} default
 * fills the surrogate at INSERT time, and {@link PartnerEntity#toDomain()} reads it
 * back on the next round-trip.
 *
 * <p>This preserves the prior runtime behaviour for callers and tests that expect
 * a freshly-booted service to already know about GMEREMIT (HALF_UP) and SENDMN (DOWN).
 */
@Component
public class PartnerSeeder implements CommandLineRunner {

    /**
     * Audit principal for the seeded partners (gap T5-1). A seeder has no HTTP request and no
     * human operator, so it names itself: the audit row says which part of the platform created
     * the row, instead of the bare {@code "system"} literal that used to mean both "the platform"
     * and "nobody told me".
     */
    private static final String SEED_ACTOR = com.gme.pay.audit.AuditActors.system("partner-seeder");

    private final PartnerRepository repository;
    private final PartnerStore store;

    public PartnerSeeder(PartnerRepository repository, PartnerStore store) {
        this.repository = repository;
        this.store = store;
    }

    @Override
    public void run(String... args) {
        if (repository.count() > 0) {
            return;
        }
        store.save(Partner.of("GMEREMIT", PartnerType.LOCAL, "KRW", RoundingMode.HALF_UP), SEED_ACTOR);
        store.save(Partner.of("SENDMN", PartnerType.OVERSEAS, "USD", RoundingMode.DOWN), SEED_ACTOR);
    }
}
