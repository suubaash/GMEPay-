package com.gme.pay.bff.client.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gme.pay.bff.web.dto.ApprovalSummary;
import com.gme.pay.rbac.RbacHeaders;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Verifies {@link RestApprovalQueueClient} maps auth-identity's {@code ApprovalRequestView} onto
 * {@link ApprovalSummary} and, crucially, forwards the acting approver's
 * {@code X-Gme-Principal-Id} + {@code X-Gme-Permissions} on approve/reject so the downstream
 * enforces against the real approver.
 */
class RestApprovalQueueClientTest {

    private static final String QUEUE_JSON = """
            [{"id":1001,"requestType":"REFUND","subjectRef":"RF-1","amount":2500.00,"currency":"USD",
              "tierLabel":"L1","status":"PENDING","requiredSteps":1,"currentStep":0,
              "requestedBy":"op.maria","requestedAt":"2026-06-17T00:00:00Z","decidedAt":null,
              "rejectReason":null,"decisions":[]}]
            """;

    private static final String APPROVED_JSON = """
            {"id":1001,"requestType":"REFUND","subjectRef":"RF-1","amount":2500.00,"currency":"USD",
             "tierLabel":"L1","status":"APPROVED","requiredSteps":1,"currentStep":1,
             "requestedBy":"op.maria","requestedAt":"2026-06-17T00:00:00Z","decidedAt":"2026-06-17T01:00:00Z",
             "rejectReason":null,
             "decisions":[{"stepIndex":0,"requiredPermission":"refund.approve_l1","approverId":"op.kim",
                           "decision":"APPROVE","cfoOverride":false,"reason":"ok","decidedAt":"2026-06-17T01:00:00Z"}]}
            """;

    @Test
    void listPending_mapsRequestViews() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo(endsWith("/v1/approvals"))).andExpect(method(GET))
                .andRespond(withSuccess(QUEUE_JSON, MediaType.APPLICATION_JSON));

        List<ApprovalSummary> queue = new RestApprovalQueueClient(b.build()).listPending();
        server.verify();

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).subjectRef()).isEqualTo("RF-1");
        assertThat(queue.get(0).tierLabel()).isEqualTo("L1");
        assertThat(queue.get(0).amount()).isEqualByComparingTo("2500.00");
    }

    @Test
    void approve_forwardsApproverIdentityAndPermissions() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo(containsString("/v1/approvals/1001/approve"))).andExpect(method(POST))
                .andExpect(header(RbacHeaders.PRINCIPAL_ID, "op.kim"))
                .andExpect(header(RbacHeaders.PERMISSIONS, containsString("refund.approve_l1")))
                .andRespond(withSuccess(APPROVED_JSON, MediaType.APPLICATION_JSON));

        ApprovalSummary done = new RestApprovalQueueClient(b.build())
                .approve(1001L, "op.kim", Set.of("refund.approve_l1"), "ok");
        server.verify();

        assertThat(done.status()).isEqualTo("APPROVED");
        assertThat(done.decisions()).singleElement()
                .satisfies(d -> assertThat(d.approverId()).isEqualTo("op.kim"));
    }
}
