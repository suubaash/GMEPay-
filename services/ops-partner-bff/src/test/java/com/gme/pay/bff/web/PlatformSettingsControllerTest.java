package com.gme.pay.bff.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.PlatformSettingsClient;
import com.gme.pay.bff.web.dto.PlatformSettingView;
import com.gme.pay.rbac.RbacHeaders;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc test for {@link PlatformSettingsController}: asserts the three admin endpoints
 * pass through to the mocked {@link PlatformSettingsClient} (list / get / update) with the
 * right arguments, and that the authenticated principal is forwarded as {@code updatedBy}.
 * RBAC uses the dev gate-off {@code OpsRbacGuard(false)} so an absent permissions header is
 * allowed (fail-closed enforcement is covered by OpsRbacGuardTest).
 */
class PlatformSettingsControllerTest {

    private MockMvc mvc;
    private PlatformSettingsClient client;

    private static PlatformSettingView view(String key, String value) {
        return new PlatformSettingView(key, value, "NUMBER", "desc", Instant.parse("2026-07-01T00:00:00Z"), "system");
    }

    @BeforeEach
    void setUp() {
        client = mock(PlatformSettingsClient.class);
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(new PlatformSettingsController(client, new OpsRbacGuard(false)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    @Test
    void listProxiesToClient() throws Exception {
        when(client.list()).thenReturn(List.of(
                view("prefunding.alert.tier1.pct", "95"),
                view("wallet.fee.krw", "500")));

        mvc.perform(get("/v1/admin/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].key").value("prefunding.alert.tier1.pct"))
                .andExpect(jsonPath("$[1].value").value("500"));

        verify(client).list();
    }

    @Test
    void getProxiesToClient() throws Exception {
        when(client.get("wallet.fee.krw")).thenReturn(view("wallet.fee.krw", "500"));

        mvc.perform(get("/v1/admin/settings/wallet.fee.krw"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("wallet.fee.krw"))
                .andExpect(jsonPath("$.value").value("500"));

        verify(client).get("wallet.fee.krw");
    }

    @Test
    void putProxiesToClientAndForwardsPrincipal() throws Exception {
        when(client.update(eq("prefunding.alert.tier1.pct"), eq("90"), eq("alice")))
                .thenReturn(view("prefunding.alert.tier1.pct", "90"));

        mvc.perform(put("/v1/admin/settings/prefunding.alert.tier1.pct")
                        .header(RbacHeaders.PRINCIPAL_ID, "alice")
                        .header(RbacHeaders.PERMISSIONS, "ops:operate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"90\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("90"));

        // principal forwarded as updatedBy; value passed straight through.
        verify(client).update("prefunding.alert.tier1.pct", "90", "alice");
    }
}
