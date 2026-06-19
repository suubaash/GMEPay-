package com.gme.pay.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Standalone MockMvc test for the cross-service RBAC enforcement layer: the
 * {@link RbacContextFilter} (headers → {@link PermissionContext}) + the
 * {@link RbacPermissionInterceptor} ({@link RequiresPermission} → allow/deny).
 */
class RbacEnforcementTest {

    private static final String PERM = "settlement.resolve_exception";

    @RestController
    static class SecuredController {
        @GetMapping("/secure")
        @RequiresPermission(PERM)
        String secure() {
            return "ok";
        }

        @GetMapping("/open")
        String open() {
            return "open";
        }
    }

    static class CapturingListener implements RbacDecisionListener {
        final List<RbacDecision> decisions = new ArrayList<>();

        @Override
        public void onDecision(RbacDecision d) {
            decisions.add(d);
        }
    }

    private MockMvc mvc(RbacMode mode, CapturingListener listener) {
        RbacProperties props = new RbacProperties();
        props.setEnabled(true);
        props.setMode(mode);
        return MockMvcBuilders.standaloneSetup(new SecuredController())
                .addFilters(new RbacContextFilter())
                .addInterceptors(new RbacPermissionInterceptor(props, listener))
                .build();
    }

    @Test
    @DisplayName("ENFORCE: caller with the permission → 200 + allow decision")
    void enforce_withPermission_allows() throws Exception {
        CapturingListener listener = new CapturingListener();
        mvc(RbacMode.ENFORCE, listener).perform(get("/secure")
                        .header(RbacHeaders.PRINCIPAL_ID, "op-1")
                        .header(RbacHeaders.PERMISSIONS, "partner.view," + PERM + ",txn.view"))
                .andExpect(status().isOk());
        assertThat(listener.decisions).singleElement()
                .satisfies(d -> {
                    assertThat(d.allowed()).isTrue();
                    assertThat(d.permission()).isEqualTo(PERM);
                });
    }

    @Test
    @DisplayName("ENFORCE: caller missing the permission → 403 + deny decision")
    void enforce_withoutPermission_forbids() throws Exception {
        CapturingListener listener = new CapturingListener();
        mvc(RbacMode.ENFORCE, listener).perform(get("/secure")
                        .header(RbacHeaders.PRINCIPAL_ID, "op-1")
                        .header(RbacHeaders.PERMISSIONS, "partner.view,txn.view"))
                .andExpect(status().isForbidden());
        assertThat(listener.decisions).singleElement()
                .satisfies(d -> assertThat(d.allowed()).isFalse());
    }

    @Test
    @DisplayName("ENFORCE: no RBAC headers (anonymous) → 403")
    void enforce_anonymous_forbids() throws Exception {
        CapturingListener listener = new CapturingListener();
        mvc(RbacMode.ENFORCE, listener).perform(get("/secure"))
                .andExpect(status().isForbidden());
        assertThat(listener.decisions.get(0).reason()).contains("unauthenticated");
    }

    @Test
    @DisplayName("ENFORCE: '*' super-grant (CFO override) → 200")
    void enforce_wildcard_allows() throws Exception {
        CapturingListener listener = new CapturingListener();
        mvc(RbacMode.ENFORCE, listener).perform(get("/secure")
                        .header(RbacHeaders.PRINCIPAL_ID, "cfo")
                        .header(RbacHeaders.PERMISSIONS, "*"))
                .andExpect(status().isOk());
        assertThat(listener.decisions.get(0).allowed()).isTrue();
    }

    @Test
    @DisplayName("AUDIT: missing permission is allowed through but recorded as a deny")
    void audit_withoutPermission_allowsButRecords() throws Exception {
        CapturingListener listener = new CapturingListener();
        mvc(RbacMode.AUDIT, listener).perform(get("/secure")
                        .header(RbacHeaders.PRINCIPAL_ID, "op-1")
                        .header(RbacHeaders.PERMISSIONS, "txn.view"))
                .andExpect(status().isOk());
        assertThat(listener.decisions).singleElement()
                .satisfies(d -> {
                    assertThat(d.allowed()).isFalse();
                    assertThat(d.mode()).isEqualTo(RbacMode.AUDIT);
                });
    }

    @Test
    @DisplayName("un-annotated handler is never gated and emits no decision")
    void unannotated_isOpen() throws Exception {
        CapturingListener listener = new CapturingListener();
        mvc(RbacMode.ENFORCE, listener).perform(get("/open"))
                .andExpect(status().isOk());
        assertThat(listener.decisions).isEmpty();
    }
}
