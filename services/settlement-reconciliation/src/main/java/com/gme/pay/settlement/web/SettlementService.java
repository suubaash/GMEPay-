package com.gme.pay.settlement.web;

import com.gme.pay.settlement.calculator.GrossSettlementAmountCalculator;
import com.gme.pay.settlement.calculator.GrossSettlementSummary;
import com.gme.pay.settlement.calculator.NetSettlementAmountCalculator;
import com.gme.pay.settlement.calculator.NetSettlementSummary;
import com.gme.pay.settlement.model.TransactionRecord;
import com.gme.pay.settlement.port.TransactionQueryPort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Application service for GET /v1/settlements.
 * Fetches unbatched approved transactions for the requested date, groups them by merchant
 * and settlement type, then delegates to the appropriate calculator.
 */
@Service
public class SettlementService {

    private final TransactionQueryPort transactionQueryPort;
    private final NetSettlementAmountCalculator netCalculator;
    private final GrossSettlementAmountCalculator grossCalculator;

    public SettlementService(
            TransactionQueryPort transactionQueryPort,
            NetSettlementAmountCalculator netCalculator,
            GrossSettlementAmountCalculator grossCalculator) {
        this.transactionQueryPort = transactionQueryPort;
        this.netCalculator = netCalculator;
        this.grossCalculator = grossCalculator;
    }

    /**
     * Calculate settlement summaries for all merchants on the given date.
     * Applies optional filters from the query request.
     */
    public List<SettlementResponse> getSettlements(SettlementQueryRequest query) {
        LocalDate date = query.settlementDate() != null ? query.settlementDate() : LocalDate.now();

        List<TransactionRecord> transactions = transactionQueryPort.findUnbatchedApproved(date);

        // Apply optional merchantId filter
        if (query.merchantId() != null) {
            transactions = transactions.stream()
                    .filter(t -> query.merchantId().equals(t.merchantId()))
                    .toList();
        }
        // Apply optional settlementType filter
        if (query.settlementType() != null) {
            char typeFilter = query.settlementType();
            transactions = transactions.stream()
                    .filter(t -> t.settlementType() == typeFilter)
                    .toList();
        }

        // Group by (merchantId, settlementType)
        Map<String, List<TransactionRecord>> byMerchantNet = transactions.stream()
                .filter(TransactionRecord::isNet)
                .collect(Collectors.groupingBy(TransactionRecord::merchantId));

        Map<String, List<TransactionRecord>> byMerchantGross = transactions.stream()
                .filter(TransactionRecord::isGross)
                .collect(Collectors.groupingBy(TransactionRecord::merchantId));

        List<SettlementResponse> results = new ArrayList<>();

        // NET calculations
        for (Map.Entry<String, List<TransactionRecord>> entry : byMerchantNet.entrySet()) {
            NetSettlementSummary summary = netCalculator.calculate(
                    entry.getKey(), date, entry.getValue());
            results.add(toResponse(summary));
        }

        // GROSS calculations
        for (Map.Entry<String, List<TransactionRecord>> entry : byMerchantGross.entrySet()) {
            GrossSettlementSummary summary = grossCalculator.calculate(
                    entry.getKey(), date, entry.getValue());
            results.add(toResponse(summary));
        }

        return results;
    }

    private SettlementResponse toResponse(NetSettlementSummary s) {
        return new SettlementResponse(
                s.merchantId(), s.settlementDate(), s.settlementType(),
                s.grossTxnCount(), s.grossTxnAmount(), s.merchantFeeTotal(), s.netSettlementAmount());
    }

    private SettlementResponse toResponse(GrossSettlementSummary s) {
        return new SettlementResponse(
                s.merchantId(), s.settlementDate(), s.settlementType(),
                s.grossTxnCount(), s.grossTxnAmount(), s.merchantFeeTotal(), s.netSettlementAmount());
    }
}
