package com.gme.pay.ratefx.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.ratefx.RateInput;
import com.gme.pay.ratefx.client.PartnerConfigPort;
import com.gme.pay.ratefx.client.PartnerConfigPort.PartnerCurrencies;
import com.gme.pay.ratefx.client.PartnerConfigPort.PartnerRule;
import com.gme.pay.ratefx.partnerb.PartnerBQuote;
import com.gme.pay.ratefx.partnerb.PartnerBQuotePort;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests the PARTNER-source wiring (WBS 4.6): a pay leg whose rule {@code ratePaySource=PARTNER} is
 * priced by {@link PartnerBQuotePort}, not the treasury {@link CostRateResolver}; and the commit-time
 * deviation guard.
 */
@ExtendWith(MockitoExtension.class)
class QuoteIssuePartnerSourceTest {

    @Mock
    private CostRateResolver costRateResolver;
    @Mock
    private PartnerBQuotePort partnerBQuotePort;

    private static PartnerConfigPort port(PartnerRule rule) {
        return new PartnerConfigPort() {
            @Override
            public PartnerCurrencies getPartnerCurrencies(String partnerCode) {
                return new PartnerCurrencies("USD", "USD", "USD");
            }
            @Override
            public List<PartnerRule> getRules(String partnerCode) {
                return List.of(rule);
            }
        };
    }

    private QuoteIssueService service(PartnerRule rule) {
        return new QuoteIssueService(port(rule), costRateResolver, partnerBQuotePort, null);
    }

    @Test
    void partnerPayLeg_usesPartnerBQuote_notTreasury() {
        PartnerRule rule = new PartnerRule("zeropay", "INBOUND",
                new BigDecimal("0.01"), new BigDecimal("0.01"), BigDecimal.ZERO, "IDENTITY", "PARTNER");
        when(partnerBQuotePort.fetchQuote("zeropay", "KRW"))
                .thenReturn(new PartnerBQuote(new BigDecimal("1395.00"), "Q-001", null));

        QuoteIssueService svc = service(rule);
        RateInput in = svc.buildRateInput(new PartnerQuoteRequest(
                "GMEREMIT", "zeropay", "INBOUND", new BigDecimal("50000"), "KRW"));

        assertThat(in.costRatePay()).isEqualByComparingTo("1395.00");
        // the pay leg never touched the treasury snapshot store
        verify(costRateResolver, never()).resolve("KRW");
    }

    @Test
    void partnerQuoteUnavailable_propagates() {
        PartnerRule rule = new PartnerRule("zeropay", "INBOUND",
                new BigDecimal("0.01"), new BigDecimal("0.01"), BigDecimal.ZERO, "IDENTITY", "PARTNER");
        when(partnerBQuotePort.fetchQuote("zeropay", "KRW"))
                .thenThrow(new ApiException(ErrorCode.PARTNER_B_QUOTE_UNAVAILABLE, "down"));

        QuoteIssueService svc = service(rule);
        assertThatThrownBy(() -> svc.buildRateInput(new PartnerQuoteRequest(
                "GMEREMIT", "zeropay", "INBOUND", new BigDecimal("50000"), "KRW")))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PARTNER_B_QUOTE_UNAVAILABLE);
    }

    @Test
    void commitDeviation_withinTolerance_returnsCommitRate() {
        // quoted 1380, commit 1390 → 0.72% < 1%
        when(partnerBQuotePort.fetchQuote("zeropay", "KRW"))
                .thenReturn(new PartnerBQuote(new BigDecimal("1390"), "Q-002", null));
        QuoteIssueService svc = service(anyRule());

        BigDecimal rate = svc.resolvePartnerCommitRate("zeropay", "KRW",
                new BigDecimal("1380"), new BigDecimal("0.01"));

        assertThat(rate).isEqualByComparingTo("1390");
    }

    @Test
    void commitDeviation_overTolerance_throwsDeviation() {
        // quoted 1380, commit 1394.8 → 1.07% > 1%
        when(partnerBQuotePort.fetchQuote("zeropay", "KRW"))
                .thenReturn(new PartnerBQuote(new BigDecimal("1394.8"), "Q-003", null));
        QuoteIssueService svc = service(anyRule());

        assertThatThrownBy(() -> svc.resolvePartnerCommitRate("zeropay", "KRW",
                new BigDecimal("1380"), new BigDecimal("0.01")))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PARTNER_B_QUOTE_DEVIATION);
    }

    private PartnerRule anyRule() {
        lenient().when(costRateResolver.resolve("KRW")).thenReturn(new BigDecimal("1380"));
        return new PartnerRule("zeropay", "INBOUND",
                new BigDecimal("0.01"), new BigDecimal("0.01"), BigDecimal.ZERO);
    }
}
