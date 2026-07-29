package com.gme.pay.registry.client;

import com.gme.pay.registry.client.rest.RestAuthIdentityClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for gap <b>T1-1</b> — "go-live credentials are fabricated and
 * authenticate nothing".
 *
 * <h2>The defect this pins shut</h2>
 *
 * <p>{@link StubAuthIdentityClient} used to be an unconditional
 * {@code @Component} and {@link StubNotificationWebhookClient} carried
 * {@code matchIfMissing = true}, while the REST clients required an explicit
 * {@code gmepay.<svc>.client=rest}. That selector was set for ops-partner-bff
 * (docker-compose.yml / values.yaml) but NEVER for config-registry — so in every
 * deployed environment the partner-activation flow returned:
 *
 * <ul>
 *   <li>an API key + HMAC secret that exist in no {@code api_keys} row, so
 *       auth-identity's {@code POST /internal/auth/keys/resolve} answers
 *       {@code found=false} and every signed partner request is rejected;</li>
 *   <li>a {@code whsec_} webhook secret notification-webhook has never seen, so
 *       no delivery signature can ever be verified;</li>
 *   <li>a {@code revokeKey()} that does nothing.</li>
 * </ul>
 *
 * <p>The failure mode was SILENT: the activation modal rendered plausible
 * {@code pk_live_…} / {@code sk_live_…} strings. The fix inverts the defaults so
 * the only way to get unverifiable material is to ask for it by name — and this
 * test fails the build if anyone flips them back.
 *
 * <p>{@link ApplicationContextRunner} is used rather than a
 * {@code @SpringBootTest}: the point is what the {@code @ConditionalOnProperty}
 * annotations resolve to for a given selector value, independent of
 * {@code application.properties} (which now also pins {@code rest} explicitly —
 * belt and braces, but it must not be the only thing standing between an
 * operator and a dead credential).
 */
@DisplayName("T1-1: credential-issuance clients default to the REAL transport")
class CredentialClientSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StubAuthIdentityClient.class,
                    RestAuthIdentityClient.class,
                    StubNotificationWebhookClient.class,
                    RestNotificationWebhookClient.class);

    @Test
    @DisplayName("NO selector set -> the REST clients win (a missing env var can never fabricate)")
    void noSelector_selectsRestClients() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AuthIdentityClient.class);
            assertThat(context.getBean(AuthIdentityClient.class))
                    .isInstanceOf(RestAuthIdentityClient.class);
            assertThat(context).doesNotHaveBean(StubAuthIdentityClient.class);

            assertThat(context).hasSingleBean(NotificationWebhookClient.class);
            assertThat(context.getBean(NotificationWebhookClient.class))
                    .isInstanceOf(RestNotificationWebhookClient.class);
            assertThat(context).doesNotHaveBean(StubNotificationWebhookClient.class);
        });
    }

    @Test
    @DisplayName("selector=rest -> the REST clients win (the documented deployed value)")
    void restSelector_selectsRestClients() {
        runner.withPropertyValues(
                        "gmepay.auth-identity.client=rest",
                        "gmepay.notification-webhook.client=rest")
                .run(context -> {
                    assertThat(context.getBean(AuthIdentityClient.class))
                            .isInstanceOf(RestAuthIdentityClient.class);
                    assertThat(context.getBean(NotificationWebhookClient.class))
                            .isInstanceOf(RestNotificationWebhookClient.class);
                });
    }

    @Test
    @DisplayName("selector=stub -> the stubs are reachable, but ONLY by explicit opt-in")
    void stubSelector_isOptInOnly() {
        runner.withPropertyValues(
                        "gmepay.auth-identity.client=stub",
                        "gmepay.notification-webhook.client=stub")
                .run(context -> {
                    assertThat(context).hasSingleBean(AuthIdentityClient.class);
                    assertThat(context.getBean(AuthIdentityClient.class))
                            .isInstanceOf(StubAuthIdentityClient.class);
                    assertThat(context).doesNotHaveBean(RestAuthIdentityClient.class);

                    assertThat(context).hasSingleBean(NotificationWebhookClient.class);
                    assertThat(context.getBean(NotificationWebhookClient.class))
                            .isInstanceOf(StubNotificationWebhookClient.class);
                    assertThat(context).doesNotHaveBean(RestNotificationWebhookClient.class);
                });
    }

    @Test
    @DisplayName("selector case does not matter: REST / Rest still select the real client")
    void selectorIsCaseInsensitive() {
        // @ConditionalOnProperty compares havingValue with equalsIgnoreCase, so a
        // shouted or title-cased env value cannot accidentally fall through to the
        // stub. Pinned because it is load-bearing for hand-edited deployment files.
        runner.withPropertyValues("gmepay.auth-identity.client=REST",
                        "gmepay.notification-webhook.client=Rest")
                .run(context -> {
                    assertThat(context.getBean(AuthIdentityClient.class))
                            .isInstanceOf(RestAuthIdentityClient.class);
                    assertThat(context.getBean(NotificationWebhookClient.class))
                            .isInstanceOf(RestNotificationWebhookClient.class);
                });
    }

    @Test
    @DisplayName("an unrecognised selector leaves NO client bean, so the service fails fast")
    void unknownSelector_yieldsNoBean_soInjectionFailsLoudly() {
        // PartnerCredentialService / WebhookProvisioningService take these as required
        // constructor args: no bean => "no qualifying bean of type AuthIdentityClient"
        // at context refresh. A misconfigured deployment must refuse to boot rather
        // than boot and issue material nothing can verify. (Contrast the old wiring,
        // where ANY value other than the exact string "rest" silently fell through to
        // the stub — including the empty string and every value never set at all.)
        runner.withPropertyValues(
                        "gmepay.auth-identity.client=mock",
                        "gmepay.notification-webhook.client=http")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AuthIdentityClient.class);
                    assertThat(context).doesNotHaveBean(NotificationWebhookClient.class);
                });
    }

    @Test
    @DisplayName("base URLs come from gmepay.<svc>.base-url and default to the compose hostnames")
    void baseUrlsAreConfigurable() {
        // Guards the pair of env vars the deployment files must set alongside the
        // selector: with the selector on but the base-url pointing at a hostname that
        // does not resolve, activation 502s (loud) rather than fabricating (silent) —
        // still, the defaults must be the compose-internal 8080 listeners so a
        // correctly-networked stack works with the selector alone.
        runner.withPropertyValues(
                        "gmepay.auth-identity.base-url=http://localhost:18085",
                        "gmepay.notification-webhook.base-url=http://localhost:18086")
                .run(context -> {
                    assertThat(context).hasSingleBean(RestAuthIdentityClient.class);
                    assertThat(context).hasSingleBean(RestNotificationWebhookClient.class);
                });
    }
}
