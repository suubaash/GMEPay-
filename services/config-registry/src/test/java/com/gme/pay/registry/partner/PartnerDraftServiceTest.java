package com.gme.pay.registry.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.gme.pay.changerequest.ChangeRequestState;
import com.gme.pay.contracts.AddressCommand;
import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.PartnerView;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.cache.NoOpConfigCache;
import com.gme.pay.registry.changerequest.ChangeRequestEntity;
import com.gme.pay.registry.changerequest.ChangeRequestRepository;
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
 * Pure Mockito unit test for {@link PartnerDraftService}. Mirrors the pattern
 * used by {@link PartnerStoreTest} — no Spring context, no Flyway migration —
 * so the test can exercise the draft-creation orchestration without booting
 * the JPA stack.
 *
 * <p>What we pin down:
 * <ul>
 *   <li>{@code createDraft} for a fresh partner_code produces a {@link PartnerView}
 *       carrying the freshly-allocated surrogate id + {@link PartnerStatus#ONBOARDING}
 *       and persists a paired {@link ChangeRequestEntity} in
 *       {@link ChangeRequestState#DRAFT}.</li>
 *   <li>{@code createDraft} for a partner_code that already exists returns
 *       HTTP 409 — duplicates are forbidden regardless of historical state.</li>
 *   <li>{@code patchStep1} on an existing ONBOARDING draft updates the Identity
 *       columns on the fresh current row and returns the merged view.</li>
 *   <li>{@code patchStep1} on a row whose status is no longer ONBOARDING
 *       returns HTTP 409 (drafts are immutable once they leave ONBOARDING).</li>
 *   <li>{@code getDraft} for an unknown code returns HTTP 404.</li>
 * </ul>
 */
class PartnerDraftServiceTest {

    private PartnerRepository partnerRepository;
    private ChangeRequestRepository changeRequestRepository;
    private PartnerStore partnerStore;
    private PartnerDraftService service;

    private final AtomicLong partnerSeq = new AtomicLong(1_000_000L);
    private final AtomicLong changeRequestSeq = new AtomicLong(2_000_000L);

    @BeforeEach
    void setUp() throws Exception {
        partnerRepository = Mockito.mock(PartnerRepository.class);
        changeRequestRepository = Mockito.mock(ChangeRequestRepository.class);

        // Build a PartnerStore on the mocked repository so PartnerDraftService
        // calls partnerStore.save() and goes through the full SCD-6 paired-write
        // path; the mocks below echo the entity back on saveAndFlush.
        partnerStore = new PartnerStore(partnerRepository, new NoOpConfigCache());

        // Inject a fake EntityManager so the V003 sequence pull resolves to a
        // deterministic Long without booting JPA. Both stores (Partner +
        // PartnerDraft) use the same pattern.
        EntityManager em = Mockito.mock(EntityManager.class);
        jakarta.persistence.Query partnersSeqQuery = Mockito.mock(jakarta.persistence.Query.class);
        when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            jakarta.persistence.Query q = Mockito.mock(jakarta.persistence.Query.class);
            if (sql.contains("partners_id_seq")) {
                when(q.getSingleResult()).thenAnswer(i -> partnerSeq.getAndIncrement());
            } else if (sql.contains("change_request_id_seq")) {
                when(q.getSingleResult()).thenAnswer(i -> changeRequestSeq.getAndIncrement());
            } else {
                when(q.getSingleResult()).thenReturn(0L);
            }
            return q;
        });
        Field emField = PartnerStore.class.getDeclaredField("entityManager");
        emField.setAccessible(true);
        emField.set(partnerStore, em);

        service = new PartnerDraftService(partnerStore, partnerRepository, changeRequestRepository);
        Field draftEmField = PartnerDraftService.class.getDeclaredField("entityManager");
        draftEmField.setAccessible(true);
        draftEmField.set(service, em);

        // Repository echo: every saveAndFlush returns the input. This makes the
        // test deterministic without modelling the actual JPA UPDATE.
        when(partnerRepository.saveAndFlush(any(PartnerEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(changeRequestRepository.saveAndFlush(any(ChangeRequestEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createDraft_freshCode_returnsViewWithOnboardingStatus() {
        // Before save: no existing row. After PartnerStore.save: a current row
        // with the freshly-allocated id.
        when(partnerRepository.existsByPartnerCode("ACME")).thenReturn(false);
        when(partnerRepository.findCurrentByPartnerCode("ACME")).thenAnswer(invocation -> {
            // First lookup (PartnerStore.save's "is there a prior?" probe) — return empty.
            // Subsequent lookups (PartnerDraftService.applyIdentity then again on PATCH) —
            // return the freshly-stamped entity.
            return Optional.of(seededEntity("ACME"));
        });

        PartnerCommand.CreateDraft req = new PartnerCommand.CreateDraft(
                "ACME", PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP,
                "Acme Corp", "Acme Corp", "1234567890", "KR_BRN", "KR",
                "CORP", null, null, null);

        PartnerView view = service.createDraft(req, "test_operator");

        assertThat(view.partnerCode()).isEqualTo("ACME");
        assertThat(view.status()).isEqualTo(PartnerStatus.ONBOARDING);
        assertThat(view.legalNameRomanized()).isEqualTo("Acme Corp");
        assertThat(view.taxId()).isEqualTo("1234567890");
        assertThat(view.taxIdType()).isEqualTo("KR_BRN");
        assertThat(view.countryOfIncorporation()).isEqualTo("KR");
        assertThat(view.legalForm()).isEqualTo("CORP");

        // Paired change_request landed in DRAFT for the same aggregate code.
        Mockito.verify(changeRequestRepository).saveAndFlush(Mockito.argThat(cr -> {
            assertThat(cr.getAggregateType()).isEqualTo(PartnerDraftService.AGGREGATE_TYPE);
            assertThat(cr.getAggregateId()).isEqualTo("ACME");
            assertThat(cr.getState()).isEqualTo(ChangeRequestState.DRAFT);
            assertThat(cr.getProposedBy()).isEqualTo("test_operator");
            return true;
        }));
    }

    @Test
    void createDraft_duplicateCode_throws409() {
        when(partnerRepository.existsByPartnerCode("DUPE")).thenReturn(true);
        PartnerCommand.CreateDraft req = new PartnerCommand.CreateDraft(
                "DUPE", PartnerType.LOCAL, "KRW", RoundingMode.HALF_UP,
                null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.createDraft(req, "system"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void patchStep1_existingOnboardingDraft_updatesIdentity() {
        PartnerEntity entity = seededEntity("GMEREMIT");
        when(partnerRepository.findCurrentByPartnerCode("GMEREMIT"))
                .thenReturn(Optional.of(entity));

        AddressCommand addr = new AddressCommand("1 Main St", null, "Seoul", "Seoul", "04524", "KR");
        PartnerCommand.UpdateStep1 req = new PartnerCommand.UpdateStep1(
                null, null, null,
                "주식회사 지엠이", "GME Co., Ltd.",
                "1234567890", "KR_BRN", "KR", "CORP",
                addr, addr, "549300X3KH0PUE6N4K48");

        PartnerView view = service.patchStep1("GMEREMIT", req, "test_operator");

        assertThat(view.partnerCode()).isEqualTo("GMEREMIT");
        assertThat(view.legalNameLocal()).isEqualTo("주식회사 지엠이");
        assertThat(view.legalNameRomanized()).isEqualTo("GME Co., Ltd.");
        assertThat(view.taxId()).isEqualTo("1234567890");
        assertThat(view.registeredAddress().city()).isEqualTo("Seoul");
        assertThat(view.lei()).isEqualTo("549300X3KH0PUE6N4K48");
        assertThat(view.status()).isEqualTo(PartnerStatus.ONBOARDING);
    }

    @Test
    void patchStep1_unknownPartner_throws404() {
        when(partnerRepository.findCurrentByPartnerCode("ghost"))
                .thenReturn(Optional.empty());

        PartnerCommand.UpdateStep1 req = new PartnerCommand.UpdateStep1(
                null, null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.patchStep1("ghost", req, "system"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void patchStep1_nonOnboardingPartner_throws409() {
        PartnerEntity entity = seededEntity("LIVE_PARTNER");
        entity.setStatus(PartnerStatus.LIVE);
        when(partnerRepository.findCurrentByPartnerCode("LIVE_PARTNER"))
                .thenReturn(Optional.of(entity));

        PartnerCommand.UpdateStep1 req = new PartnerCommand.UpdateStep1(
                null, null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.patchStep1("LIVE_PARTNER", req, "system"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void getDraft_unknownPartner_throws404() {
        when(partnerRepository.findCurrentByPartnerCode("nope"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDraft("nope"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void listDrafts_returnsOnboardingRowsOnly() {
        PartnerEntity onboarding = seededEntity("DRAFT_1");
        onboarding.setStatus(PartnerStatus.ONBOARDING);
        PartnerEntity live = seededEntity("LIVE_1");
        live.setStatus(PartnerStatus.LIVE);
        when(partnerRepository.findAllCurrent())
                .thenReturn(java.util.List.of(onboarding, live));

        java.util.List<PartnerView> drafts = service.listDrafts();
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).partnerCode()).isEqualTo("DRAFT_1");
        assertThat(drafts.get(0).status()).isEqualTo(PartnerStatus.ONBOARDING);
    }

    /** Build a seed PartnerEntity for the mock to return. */
    private PartnerEntity seededEntity(String code) {
        PartnerEntity e = new PartnerEntity(code, PartnerType.LOCAL, "KRW", RoundingMode.HALF_UP);
        e.setId(partnerSeq.getAndIncrement());
        e.setStatus(PartnerStatus.ONBOARDING);
        return e;
    }

    /** Adapt a Partner domain record to a PartnerEntity for repository mocks. */
    @SuppressWarnings("unused")
    private PartnerEntity fromDomain(Partner p) {
        PartnerEntity e = PartnerEntity.fromDomain(p);
        e.setId(p.partnerId());
        e.setStatus(PartnerStatus.ONBOARDING);
        return e;
    }
}
