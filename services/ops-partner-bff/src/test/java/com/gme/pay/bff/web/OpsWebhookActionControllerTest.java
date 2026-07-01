package com.gme.pay.bff.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.bff.client.WebhookOpsClient;
import com.gme.pay.bff.client.stub.StubOperatorActionAuditClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * MockMvc test for {@link OpsWebhookActionController}: replay delegates to
 * notification-webhook AND records the operator-action audit.
 */
class OpsWebhookActionControllerTest {

    private MockMvc mvc;
    private WebhookOpsClient webhooks;
    private StubOperatorActionAuditClient audit;

    @BeforeEach
    void setUp() {
        webhooks = mock(WebhookOpsClient.class);
        audit = new StubOperatorActionAuditClient();
        mvc = standaloneSetup(new OpsWebhookActionController(webhooks, audit))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .build();
    }

    @Test
    void replay_delegatesAndAudits() throws Exception {
        when(webhooks.replay(eq("DLV-42"), any()))
                .thenReturn(new WebhookOpsClient.ReplayResult("DLV-42", "REQUEUED", "accepted"));

        mvc.perform(post("/v1/admin/webhooks/DLV-42/replay")
                        .header("X-Gme-Principal-Id", "ops.admin@gmepay.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"partner reported missed webhook\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryId").value("DLV-42"))
                .andExpect(jsonPath("$.status").value("REQUEUED"));

        verify(webhooks).replay("DLV-42", "ops.admin@gmepay.com");
        assertThat(audit.captured()).singleElement()
                .satisfies(r -> {
                    assertThat(r.action()).isEqualTo("webhook.replay");
                    assertThat(r.target()).isEqualTo("DLV-42");
                    assertThat(r.reason()).isEqualTo("partner reported missed webhook");
                });
    }
}
