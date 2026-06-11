package com.gme.pay.registry.bank.verify;

import com.gme.pay.registry.bank.BankVerificationStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic in-process {@link AccountVerificationProvider} — the default
 * adapter (active when {@code gmepay.account-verification.provider} is unset
 * or {@code stub}), mirroring the {@code StubKybClient} wiring so local dev
 * and unit slices verify accounts with zero external infrastructure.
 *
 * <h2>Determinism contract</h2>
 *
 * <p>The verdict is a pure function of the account coordinates:
 * <ul>
 *   <li>{@code bankCountry == "KR"} → {@link BankVerificationStatus#KFTC_VERIFIED}
 *       (the rail a real KFTC adapter would use);</li>
 *   <li>anything else → {@link BankVerificationStatus#BANK_LETTER} (the
 *       overseas default).</li>
 * </ul>
 * The {@code evidenceRef} is likewise derived from the coordinates (stable
 * across runs) so tests can assert on it; only {@code verifiedAt} carries the
 * wall clock, MICROS-truncated per the platform's persisted-Instant rule.
 */
@Component
@ConditionalOnProperty(name = "gmepay.account-verification.provider",
        havingValue = "stub", matchIfMissing = true)
public class StubVerificationAdapter implements AccountVerificationProvider {

    @Override
    public VerificationResult verify(AccountRef accountRef) {
        if (accountRef == null) {
            throw new IllegalArgumentException("accountRef is required");
        }
        boolean korean = "KR".equalsIgnoreCase(accountRef.bankCountry());
        BankVerificationStatus status = korean
                ? BankVerificationStatus.KFTC_VERIFIED
                : BankVerificationStatus.BANK_LETTER;
        // Stable, input-derived reference: same account → same ref on every run.
        String evidenceRef = (korean ? "stub-kftc-" : "stub-letter-")
                + Integer.toHexString((accountRef.bankCountry() + "/"
                        + accountRef.currency() + "/"
                        + accountRef.ibanOrAccountNumber()).hashCode());
        return new VerificationResult(status, evidenceRef,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
}
