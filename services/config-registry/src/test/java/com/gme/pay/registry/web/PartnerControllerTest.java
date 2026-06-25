package com.gme.pay.registry.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.contact.PartnerContactService;
import com.gme.pay.registry.partner.PartnerDraftService;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Standalone MockMvc test for {@link PartnerController}'s SPLIT-AWARE read path
 * (Step 5 consumption closure). {@code GET /v1/partners/{id}} and
 * {@code GET /v1/partners} must return the REAL collection_ccy/settle_a_ccy,
 * NOT the {@code PartnerView.ofCore} mirror that backfills both sides from
 * settlementCurrency.
 *
 * <p>This is the read consumed by settlement-reconciliation's
 * {@code RestPartnerConfigClient} (it reads {@code settleACcy} off this view to
 * pick the partner-leg settlement currency), so a strip here would silently
 * settle the partner in the wrong currency. The fixture deliberately uses a
 * partner whose real split (collect USD, settle KRW) DIFFERS from its
 * settlementCurrency (USD) so the mirror and the real value are distinguishable.
 */
class PartnerControllerTest {

    private PartnerRepository repository;
    private MockMvc mvc;

    /** settlementCurrency=USD but a REAL split: collect in USD, settle the partner leg in KRW. */
    private static PartnerEntity splitPartner() {
        PartnerEntity e = new PartnerEntity("ACME", PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP);
        e.setId(42L);
        e.setCollectionCcy("USD");
        e.setSettleACcy("KRW");
        return e;
    }

    @BeforeEach
    void setUp() {
        repository = mock(PartnerRepository.class);
        // get()/list() use only the repository + the static PartnerDraftService.toView;
        // the other collaborators are unused on the read path but required by the ctor.
        PartnerController controller = new PartnerController(
                mock(PartnerStore.class), repository,
                mock(PartnerDraftService.class), mock(PartnerContactService.class));
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    @Test
    @DisplayName("GET /v1/partners/{id} returns the REAL settle_a_ccy, not the settlementCurrency mirror")
    void get_returnsRealSplit() throws Exception {
        when(repository.findCurrentByPartnerCode("ACME")).thenReturn(Optional.of(splitPartner()));

        mvc.perform(get("/v1/partners/{id}", "ACME"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlementCurrency").value("USD"))
                .andExpect(jsonPath("$.collectionCcy").value("USD"))
                .andExpect(jsonPath("$.settleACcy").value("KRW")); // would be "USD" under the old ofCore strip
    }

    @Test
    @DisplayName("GET /v1/partners (list) carries the real split per row")
    void list_returnsRealSplit() throws Exception {
        when(repository.findAllCurrent()).thenReturn(List.of(splitPartner()));

        mvc.perform(get("/v1/partners"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].collectionCcy").value("USD"))
                .andExpect(jsonPath("$[0].settleACcy").value("KRW"));
    }

    @Test
    @DisplayName("GET /v1/partners/{id} for an unknown partner returns 404")
    void get_unknown_404() throws Exception {
        when(repository.findCurrentByPartnerCode("GHOST")).thenReturn(Optional.empty());

        mvc.perform(get("/v1/partners/{id}", "GHOST"))
                .andExpect(status().isNotFound());
    }
}
