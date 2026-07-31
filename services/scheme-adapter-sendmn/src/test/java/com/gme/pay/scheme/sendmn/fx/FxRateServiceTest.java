package com.gme.pay.scheme.sendmn.fx;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.scheme.sendmn.dto.FxRateRegistrationRequest;
import com.gme.pay.scheme.sendmn.dto.FxRateRegistrationResponse;
import com.gme.pay.scheme.sendmn.persistence.SmnFxRateEntity;
import com.gme.pay.scheme.sendmn.persistence.SmnFxRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FxRateService} contract: SendMN rate registration (idempotent on FX_TICKER_NO)
 * and the SETTLEMENT_AMOUNT computation/verification that pre-empts SendMN's server-side
 * re-check (error 307).
 */
class FxRateServiceTest {

    private SmnFxRateRepository repository;
    private FxRateService service;

    @BeforeEach
    void setUp() {
        repository = mock(SmnFxRateRepository.class);
        service = new FxRateService(repository);
    }

    private static FxRateRegistrationRequest request(String ticker, String rate) {
        return new FxRateRegistrationRequest(ticker, "20260727", new BigDecimal(rate), "MNT", "USD");
    }

    // ------------------------------------------------------------------ registration

    @Test
    @DisplayName("register: persists a new rate with pair defaults and acks code 0")
    void register_newRate() {
        when(repository.findByFxTickerNo("TICKER-1")).thenReturn(Optional.empty());

        FxRateRegistrationResponse resp = service.register(request("TICKER-1", "3373.00"));

        assertEquals("0", resp.code());
        assertFalse(resp.duplicate());
        ArgumentCaptor<SmnFxRateEntity> saved = ArgumentCaptor.forClass(SmnFxRateEntity.class);
        verify(repository).save(saved.capture());
        assertEquals("TICKER-1", saved.getValue().getFxTickerNo());
        assertEquals(new BigDecimal("3373.00"), saved.getValue().getRate());
        assertEquals("MNT", saved.getValue().getLocalCurCode());
        assertEquals("USD", saved.getValue().getSettlementCurCode());
    }

    @Test
    @DisplayName("register: replayed FX_TICKER_NO is idempotent — acked, not duplicated")
    void register_duplicateTickerIdempotent() {
        when(repository.findByFxTickerNo("TICKER-1")).thenReturn(Optional.of(
                new SmnFxRateEntity("TICKER-1", "20260726", new BigDecimal("3370.00"), "MNT", "USD")));

        FxRateRegistrationResponse resp = service.register(request("TICKER-1", "3373.00"));

        assertEquals("0", resp.code());
        assertTrue(resp.duplicate());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("register: missing ticker / non-positive rate / missing notice date → VALIDATION_ERROR")
    void register_validation() {
        assertEquals(ErrorCode.VALIDATION_ERROR, assertThrows(ApiException.class,
                () -> service.register(request(null, "3373.00"))).errorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, assertThrows(ApiException.class,
                () -> service.register(request("T", "0"))).errorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, assertThrows(ApiException.class,
                () -> service.register(new FxRateRegistrationRequest(
                        "T", null, new BigDecimal("3373.00"), "MNT", "USD"))).errorCode());
    }

    // ------------------------------------------------------------------ settlement math

    @Test
    @DisplayName("settlementAmount: LOCAL / RATE at scale 4 HALF_UP (the value SendMN re-verifies)")
    void settlementAmount_scale4HalfUp() {
        // 10000.00 MNT / 3373 MNT-per-USD = 2.96472... → 2.9647
        assertEquals(new BigDecimal("2.9647"),
                service.settlementAmount(new BigDecimal("10000.00"), new BigDecimal("3373.00")));
        // HALF_UP boundary: 5.00 / 3.00 = 1.66666... → 1.6667
        assertEquals(new BigDecimal("1.6667"),
                service.settlementAmount(new BigDecimal("5.00"), new BigDecimal("3.00")));
        // exact division keeps scale 4
        assertEquals(new BigDecimal("2.0000"),
                service.settlementAmount(new BigDecimal("6746.00"), new BigDecimal("3373.00")));
    }

    @Test
    @DisplayName("verifySettlementAmount: matches our recomputation exactly; mismatch pre-empts 307")
    void verifySettlementAmount() {
        BigDecimal local = new BigDecimal("10000.00");
        BigDecimal rate = new BigDecimal("3373.00");

        assertTrue(service.verifySettlementAmount(local, rate, new BigDecimal("2.9647")));
        assertTrue(service.verifySettlementAmount(local, rate, new BigDecimal("2.96470")),
                "comparison is numeric, not textual");
        assertFalse(service.verifySettlementAmount(local, rate, new BigDecimal("2.9648")));
        assertFalse(service.verifySettlementAmount(local, rate, null));
    }

    @Test
    @DisplayName("settlementAmount: null/zero inputs → VALIDATION_ERROR")
    void settlementAmount_invalidInputs() {
        assertThrows(ApiException.class,
                () -> service.settlementAmount(null, new BigDecimal("3373.00")));
        assertThrows(ApiException.class,
                () -> service.settlementAmount(new BigDecimal("1.00"), BigDecimal.ZERO));
    }

    // ------------------------------------------------------------------ latest rate

    @Test
    @DisplayName("latestRate: delegates to the newest-notice-date lookup for the pair")
    void latestRate() {
        SmnFxRateEntity latest = new SmnFxRateEntity(
                "TICKER-2", "20260727", new BigDecimal("3375.50"), "MNT", "USD");
        when(repository.findFirstByLocalCurCodeAndSettlementCurCodeOrderByNoticeDateDescIdDesc("MNT", "USD"))
                .thenReturn(Optional.of(latest));

        assertEquals("TICKER-2", service.latestRate("MNT", "USD").orElseThrow().getFxTickerNo());
    }
}
