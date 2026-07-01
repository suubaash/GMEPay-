package com.gme.pay.registry.web;

import com.gme.pay.registry.scheme.SchemeCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Standalone MockMvc test for {@link SchemeCatalogController} ({@code GET /v1/schemes}).
 * Verifies the catalog serialises with ZEROPAY first and active, and exposes the
 * field names the BFF {@code SchemeSummary} binds to.
 */
class SchemeCatalogControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new SchemeCatalogController(new SchemeCatalogService())).build();
    }

    @Test
    void getSchemes_returnsCatalogWithZeropayActiveFirst() throws Exception {
        mvc.perform(get("/v1/schemes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[0].schemeId").value("ZEROPAY"))
                .andExpect(jsonPath("$[0].country").value("KR"))
                .andExpect(jsonPath("$[0].currency").value("KRW"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                // NEPAL is the second live adapter (ACTIVE), right after ZEROPAY.
                .andExpect(jsonPath("$[1].schemeId").value("NEPAL"))
                .andExpect(jsonPath("$[1].country").value("NP"))
                .andExpect(jsonPath("$[1].status").value("ACTIVE"))
                // Phase-2 roadmap schemes are present but honestly marked PLANNED.
                .andExpect(jsonPath("$[2].status").value("PLANNED"));
    }
}
