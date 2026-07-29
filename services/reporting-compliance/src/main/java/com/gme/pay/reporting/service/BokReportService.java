package com.gme.pay.reporting.service;

import com.gme.pay.reporting.domain.BokFxMapper;
import com.gme.pay.reporting.domain.BokFxRecord;
import com.gme.pay.reporting.domain.BokReportType;
import com.gme.pay.reporting.domain.CommittedTransaction;
import com.gme.pay.reporting.domain.TransactionDirection;
import com.gme.pay.reporting.dto.BokFxRecordDto;
import com.gme.pay.reporting.dto.ReportRequest;
import com.gme.pay.reporting.dto.ReportResponse;
import com.gme.pay.reporting.dto.ReportType;
import com.gme.pay.reporting.channel.FilingChannelRegistry;
import com.gme.pay.reporting.channel.FilingChannelStatus;
import com.gme.pay.reporting.persistence.ReportFiling;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Application service: fetches committed transactions from transaction-mgmt,
 * maps them to BOK FX records, applies filters, and returns the report response.
 */
@Service
public class BokReportService {

    private final TransactionClient transactionClient;
    private final BokFxMapper mapper;
    private final FilingChannelRegistry channelRegistry;

    /**
     * Spring constructor. {@code @Autowired} declared explicitly because this
     * {@code @Service} has more than one constructor (Spring 6 requires it).
     */
    @Autowired
    public BokReportService(TransactionClient transactionClient,
                            FilingChannelRegistry channelRegistry) {
        this.transactionClient = Objects.requireNonNull(transactionClient);
        this.channelRegistry = Objects.requireNonNull(channelRegistry);
        this.mapper = new BokFxMapper();
    }

    /**
     * Convenience constructor defaulting to "no lane has a transmission channel" — the
     * platform's actual state today.
     */
    public BokReportService(TransactionClient transactionClient) {
        this(transactionClient, FilingChannelRegistry.noChannelsConfigured());
    }

    /**
     * Builds the BOK FX report for the requested period and filters.
     *
     * @param request validated report request (from, to, reportType, partnerId)
     * @return {@link ReportResponse} containing mapped BOK FX records
     */
    public ReportResponse buildReport(ReportRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(request.getFrom(), "from date is required");
        Objects.requireNonNull(request.getTo(), "to date is required");
        if (request.getFrom().isAfter(request.getTo())) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }

        List<CommittedTransaction> transactions = transactionClient.fetchCommitted(
                request.getFrom(), request.getTo(), request.getPartnerId());

        List<BokFxRecordDto> dtos = new ArrayList<>();
        for (CommittedTransaction txn : transactions) {
            // Domestic/same-currency transactions are BOK-exempt
            if (txn.isSameCcyShortcircuit()
                    || txn.getDirection() == TransactionDirection.DOMESTIC) {
                continue;
            }

            BokFxRecord record = mapper.toRecord(txn);

            // Apply report-type filter
            if (!matchesFilter(record.getReportType(), request.getReportType())) {
                continue;
            }

            dtos.add(toDto(record));
        }

        // Carry the channel board with the payload: these records are generated, not filed.
        return new ReportResponse(dtos,
                channelRegistry.statusOf(ReportFiling.Lane.BOK),
                channelRegistry.statuses());
    }

    /** Channel availability per regulatory lane — backs GET /v1/reports/filing-channels. */
    public List<FilingChannelStatus> filingChannels() {
        return channelRegistry.statuses();
    }

    private boolean matchesFilter(BokReportType actual, ReportType requested) {
        if (requested == null || requested == ReportType.BOK_FX_ALL) {
            return true;
        }
        return switch (requested) {
            case BOK_FX1014 -> actual == BokReportType.FX1014;
            case BOK_FX1015 -> actual == BokReportType.FX1015;
            case BOK_FX_ALL -> true;
        };
    }

    private BokFxRecordDto toDto(BokFxRecord r) {
        BokFxRecordDto dto = new BokFxRecordDto();
        dto.setTxnId(r.getTxnId());
        dto.setTxnRef(r.getTxnRef());
        dto.setReportType(r.getReportType().name());
        dto.setReportDate(r.getReportDate());
        dto.setPartnerId(r.getPartnerId());
        dto.setCollectionAmount(r.getCollectionAmount());
        dto.setCollectionCcy(r.getCollectionCcy());
        dto.setPayoutAmount(r.getPayoutAmount());
        dto.setPayoutCcy(r.getPayoutCcy());
        dto.setOfferRateColl(r.getOfferRateColl());
        dto.setCrossRate(r.getCrossRate());
        dto.setUsdAmount(r.getUsdAmount());
        dto.setSubmissionStatus(r.getSubmissionStatus());
        return dto;
    }
}
