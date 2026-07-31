package com.gme.pay.http;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fleet-wide guard: <b>an outbound HTTP client must be built from the injected builder <em>bean</em>,
 * not from a static {@code builder()} factory</b> — unless it installs a transport of its own.
 * Covers <b>both</b> stacks: {@code RestClient} (blocking) and {@code WebClient} (reactive).
 *
 * <h2>The defect this exists to prevent</h2>
 *
 * <p>{@link HttpClientTimeoutAutoConfiguration} bounds the blocking stack through a
 * {@code RestClientCustomizer}; {@link WebClientTimeoutAutoConfiguration} bounds the reactive one
 * through a {@code WebClientCustomizer}. Spring Boot applies each <b>only to the builder bean</b> it
 * auto-configures. {@code RestClient.builder()} and {@code WebClient.builder()} are <em>static
 * factories</em> that return fresh, uncustomized builders, so a client built from one has <b>no connect
 * timeout and no read timeout at all</b> — the fleet-wide floor never reaches it.
 *
 * <p>This is the worst shape a timeout defect can take, because it is invisible from both ends. The
 * property {@code gmepay.http.client.read-timeout} resolves, it shows up in {@code /actuator/env}, the
 * auto-configurations' own tests pass, and a reviewer reading either the client or the configuration
 * sees a bounded system. Only the two together reveal that the bound was never applied. T3-11's first
 * pass recorded that these clients "inherit the fleet floor"; they do not, and this guard is the
 * mechanism that stops that belief from being re-formed.
 *
 * <h2>Why the reactive half was added</h2>
 *
 * <p>The first version of this guard scanned for {@code RestClient.builder()} only. api-gateway's three
 * upstream hops are {@code WebClient}, so they were not merely unfixed — they were <b>undetectable</b>,
 * and the guard reported a clean sweep while the component every external request passes through had no
 * outbound bound of any kind. A guard that cannot see a whole stack is a guard that certifies its own
 * blind spot.
 *
 * <h2>Why a source scan and not a Spring test</h2>
 *
 * <p>Each client lives in its own service module and no module can see another's classes, so a test that
 * only covered its own module would have to be copied twenty times — which is exactly the copying that
 * produced the offenders in {@link #KNOWN_UNBOUNDED}. This reads the fleet's sources from the one module
 * every service already depends on.
 *
 * <p>The check is deliberately crude: does a file that calls a static factory also install a transport?
 * It cannot prove the installed timeouts are sensible — {@code InternalHttpTimeoutTest},
 * {@code SchemeOutboundTimeoutTest}, smart-router's {@code ResolvePathTimeoutTest} and api-gateway's
 * {@code ReactiveOutboundTimeoutTest} do that against real unresponsive sockets. This one proves the
 * client is reachable by <em>some</em> bound rather than by none.
 *
 * <p>The detection rule itself lives in {@link OutboundClientScanner} so that
 * {@link OutboundHttpTimeoutGuardDetectsNewOffendersTest} can point it at a fixture and prove it
 * actually fires. A guard whose detection has never been exercised is a guard with an unverified regex.
 */
class OutboundHttpTimeoutWiringGuardTest {

    /**
     * Clients known to still build from a static factory, with no transport of their own, and therefore
     * <b>still unbounded</b>. Every one lives in a service outside this change's scope.
     *
     * <p>This is a shrinking baseline, not an exemption list: the test below fails BOTH when a file
     * outside it is unbounded AND when a file inside it has been fixed without being removed. An entry
     * can only ever be deleted.
     *
     * <p>The fix is one line per client and is now proven in eleven places — take
     * {@code RestClient.Builder} (or {@code WebClient.Builder}) as a constructor parameter and call
     * {@code builder.baseUrl(url)}, exactly as every scheme adapter, {@code rate-fx}'s two clients,
     * {@code prefunding}'s config-registry client, {@code payment-executor}'s revenue-ledger client,
     * {@code smart-router}'s three resolve-path clients, {@code settlement-reconciliation}'s two,
     * {@code reporting-compliance}'s two and {@code api-gateway}'s config-registry client now do. Do not
     * instead call {@code .requestFactory(..)} on a builder a test may later bind
     * {@code MockRestServiceServer} to: that binding works by installing a request factory, so
     * overwriting the factory afterwards silently detaches the test from its mock and opens real
     * sockets.
     *
     * <p><b>What remains, and why each was left.</b> {@code ops-partner-bff} (12) and
     * {@code config-registry} (4) were concurrently owned by another agent — twelve of those sixteen are
     * one repeated pattern, so they are one commit for whoever holds that module.
     * {@code auth-identity}'s partner-credential client is outside the stated file scope; it sits on the
     * authenticated edge and should be next.
     */
    private static final Set<String> KNOWN_UNBOUNDED = Set.of(
            // --- config-registry: reference-data writes + KYB + credit-limit push ---------------
            "services/config-registry/src/main/java/com/gme/pay/registry/client/rest/"
                    + "RestAuthIdentityClient.java",
            "services/config-registry/src/main/java/com/gme/pay/registry/client/"
                    + "RestNotificationWebhookClient.java",
            "services/config-registry/src/main/java/com/gme/pay/registry/kyb/KybInternalAuth.java",
            "services/config-registry/src/main/java/com/gme/pay/registry/prefunding/push/"
                    + "RestPrefundingCreditLimitClient.java",
            // --- auth-identity: partner credential lookup on the authenticated edge ------------
            "services/auth-identity/src/main/java/com/gme/pay/auth/client/"
                    + "RestPartnerCredentialClient.java",
            // --- ops-partner-bff: operator and portal reads (12 clients, one pattern) ----------
            "services/ops-partner-bff/src/main/java/com/gme/pay/bff/client/rest/RestApiKeyClient.java",
            "services/ops-partner-bff/src/main/java/com/gme/pay/bff/client/rest/"
                    + "RestAuditTrailClient.java",
            "services/ops-partner-bff/src/main/java/com/gme/pay/bff/client/rest/"
                    + "RestOperatorActionAuditClient.java",
            "services/ops-partner-bff/src/main/java/com/gme/pay/bff/client/rest/"
                    + "RestOpsControlClient.java",
            "services/ops-partner-bff/src/main/java/com/gme/pay/bff/client/rest/"
                    + "RestPlatformSettingsClient.java",
            "services/ops-partner-bff/src/main/java/com/gme/pay/bff/client/rest/"
                    + "RestPortalWebhookClient.java",
            "services/ops-partner-bff/src/main/java/com/gme/pay/bff/client/rest/"
                    + "RestPrefundingClient.java",
            "services/ops-partner-bff/src/main/java/com/gme/pay/bff/client/rest/"
                    + "RestRevenueLedgerClient.java",
            "services/ops-partner-bff/src/main/java/com/gme/pay/bff/client/rest/"
                    + "RestSandboxKeyClient.java",
            "services/ops-partner-bff/src/main/java/com/gme/pay/bff/client/rest/"
                    + "RestSettlementClient.java",
            "services/ops-partner-bff/src/main/java/com/gme/pay/bff/client/rest/"
                    + "RestSystemHealthClient.java",
            "services/ops-partner-bff/src/main/java/com/gme/pay/bff/client/rest/"
                    + "RestTransactionMgmtClient.java",
            "services/ops-partner-bff/src/main/java/com/gme/pay/bff/client/rest/"
                    + "RestWebhookOpsClient.java");

    /**
     * The services this gap was scoped to change. A client in one of these must never appear in the
     * baseline, because in-scope debt recorded as accepted debt is just debt that was not paid.
     */
    private static final List<String> IN_SCOPE_PREFIXES = List.of(
            "services/api-gateway/",
            "services/merchant-qr-data/",
            "services/notification-webhook/",
            "services/payment-executor/",
            "services/prefunding/",
            "services/qr-service/",
            "services/rate-fx/",
            "services/reporting-compliance/",
            "services/revenue-ledger/",
            "services/scheme-adapter-",
            "services/settlement-reconciliation/",
            "services/smart-router/",
            "services/transaction-mgmt/");

    @Test
    @DisplayName("no outbound RestClient or WebClient is built unbounded outside the recorded baseline")
    void everyOutboundClientIsReachableByATimeout() {
        Path root = OutboundClientScanner.repositoryRoot();
        List<OutboundClientScanner.Finding> findings =
                OutboundClientScanner.scan(root.resolve("services"), root);
        List<String> unbounded = OutboundClientScanner.unboundedPaths(findings);

        assertTrue(findings.size() >= KNOWN_UNBOUNDED.size(),
                "expected to find the fleet's outbound clients, found " + findings.size()
                        + " users of a static builder() factory — has the scan root moved?");

        Set<String> unexpected = new LinkedHashSet<>(unbounded);
        unexpected.removeAll(KNOWN_UNBOUNDED);
        if (!unexpected.isEmpty()) {
            fail("""
                    These clients build from the STATIC RestClient.builder() / WebClient.builder() \
                    factory and install no transport of their own, so the fleet's connect/read floor \
                    never reaches them: they have NO read timeout, while \
                    gmepay.http.client.read-timeout resolves and appears in /actuator/env as though \
                    it applied. Fix: take `RestClient.Builder builder` (or `WebClient.Builder`) as a \
                    constructor parameter and call `builder.baseUrl(url).build()`. Do NOT call \
                    .requestFactory(..) on a builder a test may bind MockRestServiceServer to. \
                    Offenders: """ + unexpected);
        }

        Set<String> staleBaseline = new LinkedHashSet<>(KNOWN_UNBOUNDED);
        staleBaseline.removeAll(unbounded);
        assertTrue(staleBaseline.isEmpty(),
                "KNOWN_UNBOUNDED in " + getClass().getSimpleName() + " is stale: " + staleBaseline
                        + " is now bounded. Delete the entry so the baseline keeps shrinking.");
    }

    @Test
    @DisplayName("the baseline contains nothing from a service this gap was scoped to fix")
    void baselineNeverExcusesAnInScopeService() {
        List<String> inScope = KNOWN_UNBOUNDED.stream()
                .filter(p -> IN_SCOPE_PREFIXES.stream().anyMatch(p::startsWith))
                .sorted()
                .toList();
        assertTrue(inScope.isEmpty(),
                "these are inside this gap's change scope, so they must be FIXED rather than recorded "
                        + "as accepted debt: " + inScope);
    }
}
