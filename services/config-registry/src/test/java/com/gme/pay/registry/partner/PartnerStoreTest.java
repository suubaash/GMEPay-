package com.gme.pay.registry.partner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.cache.NoOpConfigCache;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.RoundingMode;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pure unit test for {@link PartnerStore} against a Mockito stub of the repository.
 * Exercises the same behaviours as before the JPA refactor (HALF_UP/DOWN seed
 * partners, updateRoundingMode persistence, unknown partner throws), without
 * spinning up the Spring/JPA context. See {@code PartnerRepositoryIT} for the
 * Flyway-backed integration check.
 */
class PartnerStoreTest {

    private PartnerRepository repository;
    private PartnerStore store;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(PartnerRepository.class);
        // No-op cache: every read is a miss, so the store behaves as a plain DB
        // pass-through — exactly the production path when Redis is not configured.
        store = new PartnerStore(repository, new NoOpConfigCache());

        // Mimic the runtime seed: GMEREMIT (HALF_UP) and SENDMN (DOWN).
        when(repository.findById("GMEREMIT")).thenReturn(Optional.of(
                new PartnerEntity("GMEREMIT", PartnerType.LOCAL, "KRW", RoundingMode.HALF_UP)));
        when(repository.findById("SENDMN")).thenReturn(Optional.of(
                new PartnerEntity("SENDMN", PartnerType.OVERSEAS, "USD", RoundingMode.DOWN)));
        when(repository.findById(eq("NOPE"))).thenReturn(Optional.empty());

        // save() simply echoes the entity back.
        when(repository.save(any(PartnerEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void seededPartnersHaveExpectedRoundingModes() {
        assertEquals(RoundingMode.HALF_UP, store.get("GMEREMIT").settlementRoundingMode());
        assertEquals(RoundingMode.DOWN, store.get("SENDMN").settlementRoundingMode());
    }

    @Test
    void updateRoundingModePersists() {
        // After updateRoundingMode, subsequent get() should see the new mode.
        // Switch the repository's view of GMEREMIT to reflect the saved value.
        when(repository.save(any(PartnerEntity.class))).thenAnswer(inv -> {
            PartnerEntity saved = inv.getArgument(0);
            when(repository.findById(saved.getPartnerId())).thenReturn(Optional.of(saved));
            return saved;
        });

        Partner updated = store.updateRoundingMode("GMEREMIT", RoundingMode.FLOOR);
        assertEquals(RoundingMode.FLOOR, updated.settlementRoundingMode());
        assertEquals(RoundingMode.FLOOR, store.get("GMEREMIT").settlementRoundingMode());
    }

    @Test
    void unknownPartnerThrows404() {
        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> store.get("NOPE"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }
}
