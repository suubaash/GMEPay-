package com.gme.pay.registry.prefunding.push;

/**
 * config-registry's seam to prefunding for the Wave-3 credit-limit push
 * (IR-pf-2). config-registry owns a partner's credit line + AML caps; this port
 * pushes them to prefunding ({@code PUT
 * /internal/v1/prefunding/{partnerId}/credit-limit}) when they are set or the
 * partner is activated, so prefunding no longer needs them on every request.
 *
 * <p>Two implementations, the same rest-vs-stub discipline as
 * {@code KybScreeningClient} / {@code NotificationWebhookClient}:
 * {@link RestPrefundingCreditLimitClient} (active when
 * {@code gmepay.prefunding.client=rest}) and {@link NoOpPrefundingCreditLimitClient}
 * (the default — local dev / unit slices push nothing).
 */
public interface PrefundingCreditLimitClient {

    /**
     * Push a partner's credit line + AML caps to prefunding.
     *
     * @param partnerCode the partner business code — prefunding keys its balance
     *                    rows by this code, so it is the {@code {partnerId}} path
     *                    segment (the BFF/internal-network convention is the
     *                    business code, not the BIGINT surrogate).
     * @param command     the caps to push; callers should skip the call when
     *                    {@link CreditLimitPushCommand#isEmpty()}.
     */
    void pushCreditLimit(String partnerCode, CreditLimitPushCommand command);
}
