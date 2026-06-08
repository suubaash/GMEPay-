package com.gme.pay.prefunding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PrefundingAccountTest {

    private PrefundingAccount account(String balance) {
        return new PrefundingAccount("SENDMN", "USD", new BigDecimal(balance), new BigDecimal("10000"));
    }

    @Test
    void deductReducesBalance() {
        PrefundingAccount a = account("50000");
        assertEquals(0, a.deduct(new BigDecimal("125.50")).compareTo(new BigDecimal("49874.50")));
    }

    @Test
    void deductBeyondBalanceThrowsAndLeavesBalanceUntouched() {
        PrefundingAccount a = account("100");
        ApiException ex = assertThrows(ApiException.class, () -> a.deduct(new BigDecimal("150")));
        assertEquals(ErrorCode.INSUFFICIENT_PREFUNDING, ex.errorCode());
        assertEquals(0, a.balance().compareTo(new BigDecimal("100")));
    }

    @Test
    void nonPositiveAmountIsRejected() {
        PrefundingAccount a = account("100");
        assertThrows(ApiException.class, () -> a.deduct(BigDecimal.ZERO));
    }

    @Test
    void lowBalanceFlagTripsBelowThreshold() {
        PrefundingAccount a = account("12000");
        assertFalse(a.isLowBalance());
        a.deduct(new BigDecimal("3000"));
        assertTrue(a.isLowBalance());
    }

    @Test
    void creditIncreasesBalance() {
        PrefundingAccount a = account("100");
        assertEquals(0, a.credit(new BigDecimal("400")).compareTo(new BigDecimal("500")));
    }
}
