package com.gme.pay.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gme.pay.rbac.constraint.Constraint;
import com.gme.pay.rbac.constraint.ConstraintEngine;
import com.gme.pay.rbac.constraint.ConstraintType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MockMvc test for P3c: the {@link RbacPermissionInterceptor} narrowing a granted permission
 * with dynamic constraints from a {@link ConstraintSource}, evaluated by the
 * {@link ConstraintEngine}. Uses LOCATION / AMOUNT constraints (deterministic, driven by
 * request data) rather than wall-clock TIME so the assertions are stable. Also exercises the
 * {@link HeaderConstraintSource} decode path end-to-end.
 */
class RbacConstraintEnforcementTest {

    private static final String PERM = "report.generate";

    @RestController
    static class ReportController {
        @GetMapping("/report")
        @RequiresPermission(PERM)
        String report() {
            return "report";
        }
    }

    static class CapturingListener implements RbacDecisionListener {
        final List<RbacDecision> decisions = new ArrayList<>();

        @Override
        public void onDecision(RbacDecision d) {
            decisions.add(d);
        }
    }

    private static ConstraintSource fixed(Constraint... cs) {
        List<Constraint> list = List.of(cs);
        return (perm, ctx, req) -> list;
    }

    private MockMvc mvc(RbacMode mode, CapturingListener listener, ConstraintSource source) {
        RbacProperties props = new RbacProperties();
        props.setEnabled(true);
        props.setMode(mode);
        return MockMvcBuilders.standaloneSetup(new ReportController())
                .addFilters(new RbacContextFilter())
                .addInterceptors(new RbacPermissionInterceptor(props, listener, source, new ConstraintEngine()))
                .build();
    }

    // --------------------------------------------------------------- enforcement

    @Test
    @DisplayName("Permission held + LOCATION constraint satisfied (JP) → 200")
    void constraintSatisfied_allows() throws Exception {
        CapturingListener listener = new CapturingListener();
        var source = fixed(new Constraint(ConstraintType.LOCATION, Map.of("countries", "JP")));
        mvc(RbacMode.ENFORCE, listener, source).perform(get("/report")
                        .header(RbacHeaders.PRINCIPAL_ID, "op-jp")
                        .header(RbacHeaders.PERMISSIONS, PERM)
                        .header(RbacHeaders.COUNTRY, "JP"))
                .andExpect(status().isOk());
        assertThat(listener.decisions).singleElement()
                .satisfies(d -> assertThat(d.allowed()).isTrue());
    }

    @Test
    @DisplayName("Permission held but LOCATION constraint violated (KR) → 403 with reason")
    void constraintViolated_forbids() throws Exception {
        CapturingListener listener = new CapturingListener();
        var source = fixed(new Constraint(ConstraintType.LOCATION, Map.of("countries", "JP")));
        mvc(RbacMode.ENFORCE, listener, source).perform(get("/report")
                        .header(RbacHeaders.PRINCIPAL_ID, "op-kr")
                        .header(RbacHeaders.PERMISSIONS, PERM)
                        .header(RbacHeaders.COUNTRY, "KR"))
                .andExpect(status().isForbidden());
        assertThat(listener.decisions).singleElement()
                .satisfies(d -> {
                    assertThat(d.allowed()).isFalse();
                    assertThat(d.reason()).contains("constraint denied").contains("country");
                });
    }

    @Test
    @DisplayName("AMOUNT constraint: over the threshold → 403")
    void amountOverThreshold_forbids() throws Exception {
        CapturingListener listener = new CapturingListener();
        var source = fixed(new Constraint(ConstraintType.AMOUNT, Map.of("maxAmount", "1000")));
        mvc(RbacMode.ENFORCE, listener, source).perform(get("/report")
                        .header(RbacHeaders.PRINCIPAL_ID, "op-1")
                        .header(RbacHeaders.PERMISSIONS, PERM)
                        .param("amount", "1500"))
                .andExpect(status().isForbidden());
        assertThat(listener.decisions.get(0).reason()).contains("exceeds threshold");
    }

    @Test
    @DisplayName("AMOUNT constraint: at the threshold → 200")
    void amountAtThreshold_allows() throws Exception {
        CapturingListener listener = new CapturingListener();
        var source = fixed(new Constraint(ConstraintType.AMOUNT, Map.of("maxAmount", "1000")));
        mvc(RbacMode.ENFORCE, listener, source).perform(get("/report")
                        .header(RbacHeaders.PRINCIPAL_ID, "op-1")
                        .header(RbacHeaders.PERMISSIONS, PERM)
                        .param("amount", "1000"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CFO '*' super-grant bypasses constraints (wrong country) → 200")
    void cfoWildcard_bypassesConstraints() throws Exception {
        CapturingListener listener = new CapturingListener();
        var source = fixed(new Constraint(ConstraintType.LOCATION, Map.of("countries", "JP")));
        mvc(RbacMode.ENFORCE, listener, source).perform(get("/report")
                        .header(RbacHeaders.PRINCIPAL_ID, "cfo")
                        .header(RbacHeaders.PERMISSIONS, "*")
                        .header(RbacHeaders.COUNTRY, "KR"))
                .andExpect(status().isOk());
        assertThat(listener.decisions.get(0).allowed()).isTrue();
    }

    @Test
    @DisplayName("AUDIT: constraint violation passes through but is recorded as a deny")
    void audit_constraintViolated_allowsButRecords() throws Exception {
        CapturingListener listener = new CapturingListener();
        var source = fixed(new Constraint(ConstraintType.LOCATION, Map.of("countries", "JP")));
        mvc(RbacMode.AUDIT, listener, source).perform(get("/report")
                        .header(RbacHeaders.PRINCIPAL_ID, "op-kr")
                        .header(RbacHeaders.PERMISSIONS, PERM)
                        .header(RbacHeaders.COUNTRY, "KR"))
                .andExpect(status().isOk());
        assertThat(listener.decisions).singleElement()
                .satisfies(d -> {
                    assertThat(d.allowed()).isFalse();
                    assertThat(d.mode()).isEqualTo(RbacMode.AUDIT);
                });
    }

    @Test
    @DisplayName("No constraints from source → granted permission alone allows (regression)")
    void noConstraints_permissionAlone_allows() throws Exception {
        CapturingListener listener = new CapturingListener();
        mvc(RbacMode.ENFORCE, listener, ConstraintSource.NONE).perform(get("/report")
                        .header(RbacHeaders.PRINCIPAL_ID, "op-1")
                        .header(RbacHeaders.PERMISSIONS, PERM))
                .andExpect(status().isOk());
        assertThat(listener.decisions.get(0).allowed()).isTrue();
    }

    // --------------------------------------------------------- header decode path

    @Test
    @DisplayName("HeaderConstraintSource: edge-stamped constraints decode + enforce (JP allowed)")
    void headerSource_decodes_andAllows() throws Exception {
        CapturingListener listener = new CapturingListener();
        mvc(RbacMode.ENFORCE, listener, new HeaderConstraintSource()).perform(get("/report")
                        .header(RbacHeaders.PRINCIPAL_ID, "op-jp")
                        .header(RbacHeaders.PERMISSIONS, PERM)
                        .header(RbacHeaders.COUNTRY, "JP")
                        .header(RbacHeaders.CONSTRAINTS, "LOCATION:countries=JP|AMOUNT:maxAmount=1000")
                        .param("amount", "500"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("HeaderConstraintSource: edge-stamped constraints decode + deny (KR blocked)")
    void headerSource_decodes_andForbids() throws Exception {
        CapturingListener listener = new CapturingListener();
        mvc(RbacMode.ENFORCE, listener, new HeaderConstraintSource()).perform(get("/report")
                        .header(RbacHeaders.PRINCIPAL_ID, "op-kr")
                        .header(RbacHeaders.PERMISSIONS, PERM)
                        .header(RbacHeaders.COUNTRY, "KR")
                        .header(RbacHeaders.CONSTRAINTS, "LOCATION:countries=JP|AMOUNT:maxAmount=1000")
                        .param("amount", "500"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("HeaderConstraintSource.parse: multi-constraint wire format → typed constraints")
    void headerParse_decodesWireFormat() {
        var parsed = HeaderConstraintSource.parse(
                "TIME:timezone=Asia/Tokyo;startHour=9;endHour=17;days=MON,TUE,WED,THU,FRI"
                        + "|LOCATION:countries=JP|AMOUNT:maxAmount=1000");
        assertThat(parsed).hasSize(3);
        assertThat(parsed).extracting(Constraint::type)
                .containsExactly(ConstraintType.TIME, ConstraintType.LOCATION, ConstraintType.AMOUNT);
        var time = parsed.get(0);
        assertThat(time.get("timezone")).isEqualTo("Asia/Tokyo");
        assertThat(time.getSet("days")).containsExactly("MON", "TUE", "WED", "THU", "FRI");
        assertThat(parsed.get(2).getDecimal("maxAmount")).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("HeaderConstraintSource.parse: blank/unknown segments are skipped")
    void headerParse_skipsUnknown() {
        assertThat(HeaderConstraintSource.parse(null)).isEmpty();
        assertThat(HeaderConstraintSource.parse("")).isEmpty();
        // VELOCITY is not a known ConstraintType → skipped; LOCATION survives.
        var parsed = HeaderConstraintSource.parse("VELOCITY:max=5|LOCATION:countries=KR");
        assertThat(parsed).extracting(Constraint::type).containsExactly(ConstraintType.LOCATION);
    }
}
