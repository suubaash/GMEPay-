package com.gme.pay.kybadapter.kyb.registration;

import com.gme.pay.kyb.KybSubject;
import com.gme.pay.kybadapter.kyb.BusinessRegistrationVerifier;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic in-process {@link BusinessRegistrationVerifier} — the default
 * adapter (active when {@code gmepay.kyb.biz-reg.provider} is unset or
 * {@code stub}), mirroring lib-kyb's {@code StubKybAdapter} so local dev and
 * unit slices run a full KYB verification with zero external infrastructure.
 *
 * <h2>Determinism contract</h2>
 *
 * <p>The verdict is a pure function of the subject (no wall clock except
 * {@code verifiedAt}), so tests stage any outcome by naming the partner:
 * <ul>
 *   <li>blank tax/registration id → {@link BizRegStatus#SKIPPED};</li>
 *   <li>id (or a screened name) containing {@code "BIZREG_NOTFOUND"} →
 *       {@link BizRegStatus#NOT_FOUND};</li>
 *   <li>id (or a screened name) containing {@code "BIZREG_MISMATCH"} →
 *       {@link BizRegStatus#MISMATCH};</li>
 *   <li>otherwise → {@link BizRegStatus#VERIFIED}.</li>
 * </ul>
 * The {@code ref} is derived from the tax id (stable across runs); only
 * {@code verifiedAt} carries the wall clock, MICROS-truncated per the
 * platform's persisted-Instant rule.
 */
@Component
@ConditionalOnProperty(name = "gmepay.kyb.biz-reg.provider",
        havingValue = "stub", matchIfMissing = true)
public class StubBusinessRegistrationVerifier implements BusinessRegistrationVerifier {

    static final String NOT_FOUND_TOKEN = "BIZREG_NOTFOUND";
    static final String MISMATCH_TOKEN = "BIZREG_MISMATCH";

    @Override
    public BizRegResult verify(KybSubject subject) {
        if (subject == null) {
            throw new IllegalArgumentException("subject is required");
        }
        String taxId = subject.taxId();
        if (taxId == null || taxId.isBlank()) {
            return new BizRegResult(BizRegStatus.SKIPPED, null,
                    Instant.now().truncatedTo(ChronoUnit.MICROS));
        }
        String probe = (taxId + "|"
                + nullSafe(subject.legalNameLocal()) + "|"
                + nullSafe(subject.legalNameRomanized())).toUpperCase(Locale.ROOT);

        BizRegStatus status;
        if (probe.contains(NOT_FOUND_TOKEN)) {
            status = BizRegStatus.NOT_FOUND;
        } else if (probe.contains(MISMATCH_TOKEN)) {
            status = BizRegStatus.MISMATCH;
        } else {
            status = BizRegStatus.VERIFIED;
        }
        String ref = "stub-bizreg-" + Integer.toHexString(taxId.hashCode());
        return new BizRegResult(status, ref, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
