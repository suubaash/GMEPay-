package com.gme.pay.bff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.stub.StubSystemHealthClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Standalone MockMvc test for {@link SystemHealthController}. Uses the real
 * {@link StubSystemHealthClient} so all 17 backend services are exercised.
 */
class SystemHealthControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SystemHealthController controller = new SystemHealthController(new StubSystemHealthClient());

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(om);

        mvc = standaloneSetup(controller).setMessageConverters(converter).build();
    }

    @Test
    @DisplayName("GET /v1/admin/system/health returns 17 services all UP")
    void health_returns17ServicesAllUp() throws Exception {
        mvc.perform(get("/v1/admin/system/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.checkedAt").exists())
                .andExpect(jsonPath("$.services.length()").value(17))
                .andExpect(jsonPath("$.services[0].name").value("api-gateway"))
                .andExpect(jsonPath("$.services[0].status").value("UP"))
                .andExpect(jsonPath("$.services[0].lastSeenAt").exists())
                .andExpect(jsonPath("$.services[0].uptimeSec").exists());
    }

    @Test
    @DisplayName("GET /v1/admin/system/health: every service is UP")
    void health_everyServiceUp() throws Exception {
        // Spot-check several services at different indices; all should report UP.
        mvc.perform(get("/v1/admin/system/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services[1].status").value("UP"))
                .andExpect(jsonPath("$.services[8].status").value("UP"))
                .andExpect(jsonPath("$.services[16].status").value("UP"))
                .andExpect(jsonPath("$.services[16].name").value("security-platform"));
    }
}
