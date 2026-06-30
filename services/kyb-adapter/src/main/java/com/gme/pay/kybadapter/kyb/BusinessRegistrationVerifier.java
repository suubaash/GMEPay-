package com.gme.pay.kybadapter.kyb;

import com.gme.pay.kyb.KybSubject;
import java.time.Instant;

/**
 * Business-registration verification port (the second leg of a full KYB run,
 * alongside the lib-kyb {@code KybProvider} sanctions/PEP screening).
 *
 * <p>In Korea this is the KFTC 사업자등록 진위확인 rail (business-registration
 * authenticity check against the National Tax Service registry): given the
 * entity's tax/registration number and legal name, confirm the registration
 * exists, is active, and the name matches. The port keeps that vendor concern
 * out of the orchestration the same way {@code KybProvider} keeps sanctions
 * screening out — a vendor swap (or the KFTC certificate landing) is a
 * configuration change, never a refactor.
 *
 * <p>Adapter roster (selected by {@code gmepay.kyb.biz-reg.provider}):
 * <ul>
 *   <li>{@link com.gme.pay.kybadapter.kyb.registration.StubBusinessRegistrationVerifier}
 *       — deterministic in-process default;</li>
 *   <li>{@link com.gme.pay.kybadapter.kyb.registration.KftcBusinessRegistrationVerifier}
 *       — KFTC placeholder, fails fast until the production certificate is
 *       provisioned (mirrors config-registry's {@code KftcVerificationAdapter}).</li>
 * </ul>
 *
 * <p>Implementations are side-effect free with respect to platform state: the
 * {@code KybVerificationService} owns the persisted run record and the event.
 */
public interface BusinessRegistrationVerifier {

    /**
     * Verify the subject's business registration. Never returns {@code null};
     * a registration that cannot be located surfaces as
     * {@link BizRegStatus#NOT_FOUND}, a name/number mismatch as
     * {@link BizRegStatus#MISMATCH} — not as an exception (those are verdicts an
     * analyst dispositions, not transport failures).
     */
    BizRegResult verify(KybSubject subject);

    /** Verdict of a business-registration verification run. */
    enum BizRegStatus {
        /** Registration exists, is active, and the name matches. */
        VERIFIED,
        /** No registration found for the supplied number. */
        NOT_FOUND,
        /** Registration found but legal name / status does not reconcile. */
        MISMATCH,
        /** No tax/registration number supplied — verification could not run. */
        SKIPPED
    }

    /**
     * One business-registration verification result.
     *
     * @param status     the verdict; never {@code null}.
     * @param ref        provider-side reference of the check (stable for the
     *                   stub so tests can assert on it); {@code null} when
     *                   {@link BizRegStatus#SKIPPED}.
     * @param verifiedAt when the provider performed the check (UTC, MICROS).
     */
    record BizRegResult(BizRegStatus status, String ref, Instant verifiedAt) {
    }
}
