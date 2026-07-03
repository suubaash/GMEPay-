package com.gme.pay.payment.web;

import com.gme.pay.payment.persistence.SandboxE2eRunRepository;
import com.gme.pay.payment.sandbox.E2eRunner;
import com.gme.pay.payment.sandbox.dto.E2eRunDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Standalone MockMvc tests for {@link SandboxE2eController}: the option catalog, a run's JSON shape,
 * and the 404 on a missing run. The {@link E2eRunner} and repository are mocked (no HTTP / DB).
 */
class SandboxE2eControllerTest {

    private E2eRunner runner;
    private SandboxE2eRunRepository repository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        runner = mock(E2eRunner.class);
        repository = mock(SandboxE2eRunRepository.class);
        mvc = standaloneSetup(new SandboxE2eController(runner, repository)).build();
    }

    @Test
    void options_listsCountriesPartnersAndMpmTypes() throws Exception {
        mvc.perform(get("/v1/sandbox/e2e/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countries[0].code", is("NP")))
                .andExpect(jsonPath("$.countries[0].currency", is("NPR")))
                .andExpect(jsonPath("$.countries[1].code", is("KR")))
                .andExpect(jsonPath("$.partners[0].code", is("GMEREMIT")))
                .andExpect(jsonPath("$.partners[1].code", is("SENDMN")))
                .andExpect(jsonPath("$.mpmTypes[0]", is("STATIC")))
                .andExpect(jsonPath("$.mpmTypes[1]", is("DYNAMIC")));
    }

    @Test
    void run_returnsRunDetailWithSteps() throws Exception {
        E2eRunDetail detail = new E2eRunDetail(
                7L, "2026-07-03T00:00:00Z", "KR", "GMEREMIT", "5000", "KRW", "STATIC",
                "PASS", null, 4,
                List.of(new E2eRunDetail.Step(1, "Resolve QR", "PASS", "ok", 1L, null),
                        new E2eRunDetail.Step(2, "Classify", "PASS", "ok", 2L, 200),
                        new E2eRunDetail.Step(3, "Pay", "PASS", "ok", 3L, 201),
                        new E2eRunDetail.Step(4, "Verify receipt", "PASS", "ZP-1", 0L, null)));
        when(runner.run(eq("KR"), eq("GMEREMIT"), eq("5000"), eq("STATIC"))).thenReturn(detail);

        String body = """
                { "country": "KR", "partner": "GMEREMIT", "amount": "5000", "mpmType": "STATIC" }
                """;

        mvc.perform(post("/v1/sandbox/e2e/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(7)))
                .andExpect(jsonPath("$.status", is("PASS")))
                .andExpect(jsonPath("$.currency", is("KRW")))
                .andExpect(jsonPath("$.stepCount", is(4)))
                .andExpect(jsonPath("$.steps[2].name", is("Pay")))
                .andExpect(jsonPath("$.steps[2].httpStatus", is(201)));
    }

    @Test
    void runDetail_missing_returns404() throws Exception {
        when(repository.findByIdWithSteps(anyLong())).thenReturn(Optional.empty());

        mvc.perform(get("/v1/sandbox/e2e/runs/999"))
                .andExpect(status().isNotFound());
    }
}
