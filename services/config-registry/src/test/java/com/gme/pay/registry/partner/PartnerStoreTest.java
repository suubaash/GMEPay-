package com.gme.pay.registry.partner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.cache.NoOpConfigCache;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Field;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pure unit test for {@link PartnerStore} against a Mockito stub of the repository.
 * Exercises the same behaviours as before the V004 bitemporal refactor (HALF_UP/DOWN
 * seed partners, updateRoundingMode persistence, unknown partner throws), without
 * spinning up the Spring/JPA context.
 *
 * <p>After V004 the store reads through {@code findCurrentByPartnerCode} and writes
 * via {@code saveAndFlush} (UPDATE prior + INSERT new in one txn), so the mock
 * stubs target those methods. The surrogate-id sequence pull is faked through a
 * Mockito stub of {@link EntityManager#createNativeQuery(String)} so the store's
 * application-layer "select nextval('partners_id_seq')" path runs without a real
 * JPA context. See {@code PartnerRepositoryIT} and {@code BitemporalPartnerTest}
 * for the Flyway-backed integration coverage.
 */
class PartnerStoreTest {

    private PartnerRepository repository;
    private PartnerStore store;
    private final AtomicLong fakeSequence = new AtomicLong(1_000_000L);

    @BeforeEach
    void setUp() throws Exception {
        repository = Mockito.mock(PartnerRepository.class);
        // No-op cache: every read is a miss, so the store behaves as a plain DB
        // pass-through — exactly the production path when Redis is not configured.
        store = new PartnerStore(repository, new NoOpConfigCache());

        // Inject a Mockito EntityManager so the V003 sequence pull in
        // PartnerStore.save resolves to a deterministic Long without booting JPA.
        EntityManager em = Mockito.mock(EntityManager.class);
        jakarta.persistence.Query seqQuery = Mockito.mock(jakarta.persistence.Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(seqQuery);
        when(seqQuery.getSingleResult()).thenAnswer(inv -> fakeSequence.getAndIncrement());
        Field emField = PartnerStore.class.getDeclaredField("entityManager");
        emField.setAccessible(true);
        emField.set(store, em);

        // Mimic the runtime seed: GMEREMIT (HALF_UP) and SENDMN (DOWN). Stubbing
        // findCurrentByPartnerCode (post-V004 hot path) rather than the legacy
        // findByPartnerCode which now returns any matching row regardless of
        // supersession.
        PartnerEntity gmeremit = new PartnerEntity("GMEREMIT", PartnerType.LOCAL, "KRW", RoundingMode.HALF_UP);
        gmeremit.setId(1L);
        when(repository.findCurrentByPartnerCode("GMEREMIT")).thenReturn(Optional.of(gmeremit));

        PartnerEntity sendmn = new PartnerEntity("SENDMN", PartnerType.OVERSEAS, "USD", RoundingMode.DOWN);
        sendmn.setId(2L);
        when(repository.findCurrentByPartnerCode("SENDMN")).thenReturn(Optional.of(sendmn));

        when(repository.findCurrentByPartnerCode("NOPE")).thenReturn(Optional.empty());

        // saveAndFlush echoes the entity back (both halves of the SCD-6 paired write).
        when(repository.saveAndFlush(any(PartnerEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void seededPartnersHaveExpectedRoundingModes() {
        assertEquals(RoundingMode.HALF_UP, store.get("GMEREMIT").settlementRoundingMode());
        assertEquals(RoundingMode.DOWN, store.get("SENDMN").settlementRoundingMode());
    }

    @Test
    void updateRoundingModePersists() {
        // After updateRoundingMode, subsequent get() should see the new mode. SCD-6
        // semantics: the save() inserts a new current row; the mock's
        // findCurrentByPartnerCode must therefore start returning the freshly saved
        // entity (which is the second argument to saveAndFlush — first call closes
        // the prior, second call inserts the new).
        when(repository.saveAndFlush(any(PartnerEntity.class))).thenAnswer(inv -> {
            PartnerEntity saved = inv.getArgument(0);
            if (saved.getSupersededAt() == null) {
                // This is the INSERT of the new current row. Wire the mock to return
                // it from subsequent findCurrentByPartnerCode calls.
                when(repository.findCurrentByPartnerCode(saved.getPartnerCode()))
                        .thenReturn(Optional.of(saved));
            }
            return saved;
        });

        Partner updated = store.updateRoundingMode("GMEREMIT", RoundingMode.FLOOR);
        assertEquals(RoundingMode.FLOOR, updated.settlementRoundingMode());
        assertNotNull(updated.partnerId(),
                "the new current row must carry a freshly-allocated surrogate id");
        assertEquals(RoundingMode.FLOOR, store.get("GMEREMIT").settlementRoundingMode());
    }

    @Test
    void unknownPartnerThrows404() {
        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> store.get("NOPE"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }
}
