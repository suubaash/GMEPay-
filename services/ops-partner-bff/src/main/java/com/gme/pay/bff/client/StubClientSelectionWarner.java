package com.gme.pay.bff.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Startup banner naming <b>every fabricating client this JVM actually wired</b>.
 *
 * <h2>The defect this exists to make impossible to miss</h2>
 *
 * <p>A {@code Stub*} bean that wins unless a values file says otherwise <em>is</em> production
 * wiring, and whether it is overridden is a property of a deployment file rather than of the code.
 * That is the T1-1 defect class, and this repository has now hit it three times: config-registry
 * handing out fabricated go-live credentials, this service's operator-action audit trail being an
 * in-memory list in every environment, and three selectors here ({@code reporting-compliance},
 * {@code system-health}, {@code webhook-ops}) that were set in no compose service and no values
 * file at all — so the Reports page served fixtures, the System Health page reported every service
 * UP whether or not it was running, and the webhook-secret panel reported no endpoints.
 *
 * <p>Inverting the selector defaults (see {@code application.properties}) fixes the default. It
 * does <b>not</b> fix the observability problem: an environment that deliberately or accidentally
 * sets {@code stub} still looks completely healthy. Actuator shows UP, no endpoint errors, and the
 * data is plausible — which is exactly why it survived this long. So the wiring is stated out loud,
 * once, at every boot.
 *
 * <h2>Why one banner instead of a WARN in each stub's constructor</h2>
 *
 * <p>Per-class WARNs are individually easy to lose in a startup log and, more importantly, they are
 * easy to <em>omit</em> when a new stub is added — the same "someone must remember" failure that
 * produced the defect. One listener that enumerates the container cannot forget a bean: a stub that
 * is wired appears here whether or not anybody wrote a log line for it. Two stubs additionally have
 * <b>no</b> real counterpart at all ({@code StubAuditClient}, {@code StubRatesClient}); nothing but
 * this banner would ever say so.
 *
 * <p>It logs at WARN when any stub is wired, and at INFO ("every upstream client is live") when
 * none is — the absence of a warning has to be a positive statement, not silence that could equally
 * mean the check never ran.
 */
@Component
public class StubClientSelectionWarner {

    private static final Logger log = LoggerFactory.getLogger(StubClientSelectionWarner.class);

    /** Package every fabricating client lives in. Membership is the test, not a hand-kept list. */
    static final String STUB_PACKAGE = "com.gme.pay.bff.client.stub";

    /**
     * Simple class name -> the selector that would replace it with the real client.
     *
     * <p>Only used to make the message actionable. A stub missing from this map is still reported
     * (with "no selector"), which is the correct behaviour for the two that genuinely have no real
     * implementation to switch to.
     */
    static final Map<String, String> SELECTOR_BY_STUB = Map.ofEntries(
            Map.entry("StubApiKeyClient", "gmepay.auth-identity.client"),
            Map.entry("StubApprovalQueueClient", "gmepay.auth-identity.client"),
            Map.entry("StubRbacAdminClient", "gmepay.auth-identity.client"),
            Map.entry("StubSandboxKeyClient", "gmepay.auth-identity.client"),
            Map.entry("StubAuditTrailClient", "gmepay.config-registry.client"),
            Map.entry("StubConfigRegistryClient", "gmepay.config-registry.client"),
            Map.entry("StubPlatformSettingsClient", "gmepay.config-registry.client"),
            Map.entry("StubPortalWebhookClient", "gmepay.notification-webhook.client"),
            Map.entry("StubPrefundingClient", "gmepay.prefunding.client"),
            Map.entry("StubReportingClient", "gmepay.reporting-compliance.client"),
            Map.entry("StubRevenueLedgerClient", "gmepay.revenue-ledger.client"),
            Map.entry("StubSettlementClient", "gmepay.settlement-reconciliation.client"),
            Map.entry("StubStatementClient", "gmepay.transaction-mgmt.client"),
            Map.entry("StubTransactionMgmtClient", "gmepay.transaction-mgmt.client"),
            Map.entry("StubSystemHealthClient", "gmepay.system-health.client"),
            Map.entry("StubOpsControlClient", "gmepay.ops-control.client"),
            Map.entry("StubWebhookOpsClient", "gmepay.webhook-ops.client"),
            Map.entry("StubOperatorActionAuditClient", "gmepay.operator-action-audit.client"));

    private final ListableBeanFactory beanFactory;

    public StubClientSelectionWarner(ListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    /** Simple names of the stub beans this container actually wired, in a stable order. */
    public List<String> wiredStubs() {
        Map<String, String> found = new LinkedHashMap<>();
        for (String name : beanFactory.getBeanDefinitionNames()) {
            Class<?> type;
            try {
                type = beanFactory.getType(name);
            } catch (RuntimeException e) {
                continue;   // a bean we cannot resolve is not a stub we can report on
            }
            if (type == null || type.getPackageName() == null) {
                continue;
            }
            if (STUB_PACKAGE.equals(type.getPackageName())) {
                found.put(type.getSimpleName(), name);
            }
        }
        List<String> names = new ArrayList<>(found.keySet());
        names.sort(String::compareTo);
        return List.copyOf(names);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        List<String> stubs = wiredStubs();
        if (stubs.isEmpty()) {
            log.info("Upstream client wiring: every client is the live one — no in-memory stub is "
                    + "wired in this JVM.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String stub : stubs) {
            String selector = SELECTOR_BY_STUB.get(stub);
            sb.append("\n  - ").append(stub).append(selector == null
                    ? "  (NO real implementation exists; this surface is fabricated by design and "
                            + "cannot be switched to a live upstream)"
                    : "  <- set " + selector + "=rest for the live upstream");
        }
        log.warn("IN-MEMORY STUB CLIENTS ARE WIRED ({}). The surfaces they back show FABRICATED "
                + "data that is not authoritative for anything: the values are plausible, the "
                + "endpoints return 200 and actuator reports UP, so nothing else will tell you.{}",
                stubs.size(), sb);
    }
}
