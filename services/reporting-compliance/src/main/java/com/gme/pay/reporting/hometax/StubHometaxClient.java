package com.gme.pay.reporting.hometax;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stub implementation of {@link HometaxClient} — active by default when no
 * production {@link HometaxClient} bean is present.
 *
 * <p>Returns deterministic fake values suitable for integration testing and
 * local development without NTS credentials. The {@code invoiceId} is a
 * stable counter-based string so test assertions can verify it is non-null
 * and non-empty without caring about exact UUID values.
 *
 * <p>Config keys ({@code gmepay.hometax.base-url} and
 * {@code gmepay.hometax.cert-id}) are read by the production client only;
 * this stub ignores them so the service boots without any Hometax config.
 */
@Component
@ConditionalOnMissingBean(value = HometaxClient.class, ignored = StubHometaxClient.class)
public class StubHometaxClient implements HometaxClient {

    private static final AtomicLong COUNTER = new AtomicLong(1000L);

    @Override
    public HometaxInvoiceResponse submitInvoice(HometaxInvoiceRequest request) {
        long seq = COUNTER.getAndIncrement();
        String invoiceId = "STUB-INV-" + seq;
        // Fake NTS confirmation: 24-character alphanumeric per NTS spec shape
        String ntsConfirmation = "NTS" + UUID.randomUUID().toString().replace("-", "").substring(0, 21).toUpperCase();
        return new HometaxInvoiceResponse(invoiceId, ntsConfirmation, "ACCEPTED");
    }
}
