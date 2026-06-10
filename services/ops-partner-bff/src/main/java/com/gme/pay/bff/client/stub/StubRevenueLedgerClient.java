package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.RevenueLedgerClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Phase-1 in-memory stub of {@link RevenueLedgerClient}. Returns a fixed daily
 * total so the Admin dashboard renders a non-zero value.
 */
@Component
public class StubRevenueLedgerClient implements RevenueLedgerClient {

    @Override
    public RevenueSummary getSummary(LocalDate date) {
        return new RevenueSummary(
                date == null ? LocalDate.now() : date,
                new BigDecimal("1234.56"),
                new BigDecimal("420.10"),
                new BigDecimal("814.46"));
    }
}
