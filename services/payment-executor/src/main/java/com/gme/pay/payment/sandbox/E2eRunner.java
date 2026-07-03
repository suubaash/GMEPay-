package com.gme.pay.payment.sandbox;

import com.gme.pay.payment.persistence.SandboxE2eRunEntity;
import com.gme.pay.payment.persistence.SandboxE2eRunRepository;
import com.gme.pay.payment.persistence.SandboxE2eStepEntity;
import com.gme.pay.payment.sandbox.dto.E2eRunDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Executes a sandbox End-to-End payment journey over the REAL wire path (loopback HTTP to this
 * service's own {@code /v1/pay/classify} + {@code /v1/pay}) and persists the run + ordered steps
 * so a reviewer can inspect exactly where a corridor is mis-wired.
 *
 * <h2>Steps (stop at first FAIL; remaining steps SKIP)</h2>
 * <ol>
 *   <li><b>Resolve QR</b> — pick the (country, MPM type) QR; DYNAMIC injects the amount + a fresh CRC.
 *   <li><b>Classify</b> — POST /v1/pay/classify; PASS iff {@code supported && currency==expected}.
 *   <li><b>Pay</b> — POST /v1/pay; PASS iff HTTP 201 && status APPROVED.
 *   <li><b>Verify receipt</b> — PASS iff the Pay response carried a non-blank schemeTxnRef.
 * </ol>
 *
 * <p>Overall status is {@code FAIL} if any step failed (else {@code PASS}); {@code failedStep} is the
 * first failed step name.
 */
@Service
public class E2eRunner {

    private static final Logger log = LoggerFactory.getLogger(E2eRunner.class);

    static final String STEP_RESOLVE = "Resolve QR";
    static final String STEP_CLASSIFY = "Classify";
    static final String STEP_PAY = "Pay";
    static final String STEP_VERIFY = "Verify receipt";

    static final String PASS = "PASS";
    static final String FAIL = "FAIL";
    static final String SKIP = "SKIP";

    private final SelfPayClient selfPayClient;
    private final SandboxE2eRunRepository runRepository;

    public E2eRunner(SelfPayClient selfPayClient, SandboxE2eRunRepository runRepository) {
        this.selfPayClient = selfPayClient;
        this.runRepository = runRepository;
    }

    /** Currency for a country: NP→NPR, KR→KRW. */
    static String currencyFor(String country) {
        String c = country == null ? "" : country.trim().toUpperCase(Locale.ROOT);
        return switch (c) {
            case "NP" -> "NPR";
            case "KR" -> "KRW";
            default -> throw new IllegalArgumentException(
                    "Unsupported country: " + country + " (supported: NP, KR)");
        };
    }

    /**
     * Runs the full journey and returns its persisted detail. The run row is saved FIRST (to obtain
     * the id used in {@code userRef=e2e-<id>}), then updated with the final status + steps.
     */
    @Transactional
    public E2eRunDetail run(String country, String partner, String amount, String mpmType) {
        String currency = currencyFor(country);
        BigDecimal amountValue = amount != null ? new BigDecimal(amount.trim()) : null;

        SandboxE2eRunEntity run = new SandboxE2eRunEntity(
                Instant.now(), country, partner, amountValue, currency, mpmType);
        run = runRepository.save(run); // assigns the id used for userRef
        long runId = run.getId();

        List<SandboxE2eStepEntity> steps = execute(runId, country, partner, amount, currency, mpmType);

        String failedStep = steps.stream()
                .filter(s -> FAIL.equals(s.getStatus()))
                .map(SandboxE2eStepEntity::getName)
                .findFirst()
                .orElse(null);

        for (SandboxE2eStepEntity step : steps) {
            run.addStep(step);
        }
        run.setStatus(failedStep == null ? PASS : FAIL);
        run.setFailedStep(failedStep);
        run.setStepCount(steps.size());
        run = runRepository.save(run);

        return SandboxE2eMapper.toDetail(run);
    }

    /** Executes the ordered steps in memory; stops calling HTTP after the first FAIL (rest SKIP). */
    private List<SandboxE2eStepEntity> execute(long runId, String country, String partner,
                                               String amount, String currency, String mpmType) {
        List<SandboxE2eStepEntity> steps = new ArrayList<>();
        boolean dynamic = mpmType != null && "DYNAMIC".equalsIgnoreCase(mpmType.trim());

        // ---- Step 1: Resolve QR ----
        long t0 = System.nanoTime();
        String qr;
        try {
            qr = SandboxQrCatalog.resolve(country, mpmType, amount);
        } catch (RuntimeException ex) {
            steps.add(step(1, STEP_RESOLVE, FAIL, "Could not resolve QR: " + ex.getMessage(),
                    millis(t0), null));
            skipRemaining(steps, 2, STEP_CLASSIFY, STEP_PAY, STEP_VERIFY);
            return steps;
        }
        String qrType = dynamic ? "DYNAMIC" : "STATIC";
        steps.add(step(1, STEP_RESOLVE, PASS,
                "Resolved " + qrType + " " + country + " QR: " + qr, millis(t0), null));

        // ---- Step 2: Classify ----
        t0 = System.nanoTime();
        SelfPayClient.Result<SelfPayClient.ClassifyResponse> cr;
        try {
            cr = selfPayClient.classify(qr);
        } catch (RuntimeException ex) {
            steps.add(step(2, STEP_CLASSIFY, FAIL, "classify call failed: " + ex.getMessage(),
                    millis(t0), null));
            skipRemaining(steps, 3, STEP_PAY, STEP_VERIFY);
            return steps;
        }
        SelfPayClient.ClassifyResponse c = cr.body();
        boolean classifyOk = c != null && c.supported() && currency.equalsIgnoreCase(c.currency());
        if (classifyOk) {
            steps.add(step(2, STEP_CLASSIFY, PASS,
                    "GMEPay+ says: country=" + c.country() + ", currency=" + c.currency()
                            + ", scheme=" + c.scheme(), millis(t0), cr.httpStatus()));
        } else {
            steps.add(step(2, STEP_CLASSIFY, FAIL,
                    "GMEPay+ classify mismatch (expected currency=" + currency + "): "
                            + cr.rawBody(), millis(t0), cr.httpStatus()));
            skipRemaining(steps, 3, STEP_PAY, STEP_VERIFY);
            return steps;
        }

        // ---- Step 3: Pay ----
        // STATIC pays `amount`; DYNAMIC pays the amount embedded in the built QR (== amount).
        t0 = System.nanoTime();
        SelfPayClient.Result<SelfPayClient.PayResponse> pr;
        try {
            pr = selfPayClient.pay(new SelfPayClient.PayRequest(
                    qr, amount, currency, partner, "e2e-" + runId));
        } catch (RuntimeException ex) {
            steps.add(step(3, STEP_PAY, FAIL, "pay call failed: " + ex.getMessage(),
                    millis(t0), null));
            skipRemaining(steps, 4, STEP_VERIFY);
            return steps;
        }
        SelfPayClient.PayResponse p = pr.body();
        boolean payOk = pr.httpStatus() == 201 && p != null && "APPROVED".equalsIgnoreCase(p.status());
        if (payOk) {
            steps.add(step(3, STEP_PAY, PASS,
                    "APPROVED (HTTP 201), schemeTxnRef=" + p.schemeTxnRef(),
                    millis(t0), pr.httpStatus()));
        } else {
            String reason = p != null ? p.declineReason() : null;
            steps.add(step(3, STEP_PAY, FAIL,
                    "DECLINED: reason=" + reason + ", httpStatus=" + pr.httpStatus()
                            + (p == null ? " body=" + pr.rawBody() : ""),
                    millis(t0), pr.httpStatus()));
            skipRemaining(steps, 4, STEP_VERIFY);
            return steps;
        }

        // ---- Step 4: Verify receipt ----
        t0 = System.nanoTime();
        String schemeTxnRef = p.schemeTxnRef();
        if (schemeTxnRef != null && !schemeTxnRef.isBlank()) {
            steps.add(step(4, STEP_VERIFY, PASS, "schemeTxnRef=" + schemeTxnRef, millis(t0), null));
        } else {
            steps.add(step(4, STEP_VERIFY, FAIL,
                    "APPROVED but no schemeTxnRef on the Pay response", millis(t0), null));
        }
        return steps;
    }

    private static void skipRemaining(List<SandboxE2eStepEntity> steps, int startSeq, String... names) {
        int seq = startSeq;
        for (String name : names) {
            steps.add(step(seq++, name, SKIP, "skipped (a prior step failed)", null, null));
        }
    }

    private static SandboxE2eStepEntity step(int seq, String name, String status, String detail,
                                             Long latencyMs, Integer httpStatus) {
        return new SandboxE2eStepEntity(seq, name, status, detail, latencyMs, httpStatus);
    }

    private static long millis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
