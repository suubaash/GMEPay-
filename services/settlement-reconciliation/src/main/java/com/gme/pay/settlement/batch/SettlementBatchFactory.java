package com.gme.pay.settlement.batch;

import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.persistence.SettlementBatchRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Idempotent factory for outbound settlement batches, keyed on {@code (file_type, business_date, window)}
 * (the V006 unique index). Returns the existing batch unless it ERRORed (in which case a fresh PENDING
 * row replaces it, allowing a clean re-generation). The only writer that inserts settlement_batches on
 * the outbound path.
 */
@Component
public class SettlementBatchFactory {

    /** Outbound batches settle GME → ZeroPay; the per-line entities carry the real merchant id. */
    static final String COUNTERPARTY = "ZEROPAY";
    static final String DIRECTION = "GME_TO_ZP";

    private final SettlementBatchRepository repository;

    public SettlementBatchFactory(SettlementBatchRepository repository) {
        this.repository = repository;
    }

    /** Idempotent: existing non-ERROR batch is returned as-is; otherwise a fresh PENDING row is inserted. */
    public SettlementBatchEntity createOrGet(String fileType, LocalDate businessDate, String window) {
        return repository.findByFileTypeAndBusinessDateAndSettlementWindow(fileType, businessDate, window)
                .filter(b -> !SettlementBatchStatus.ERROR.name().equals(b.getStatus()))
                .orElseGet(() -> repository.save(newPending(fileType, businessDate, window)));
    }

    private static SettlementBatchEntity newPending(String fileType, LocalDate businessDate, String window) {
        SettlementBatchEntity b = new SettlementBatchEntity();
        b.setBatchId(fileType + "-" + businessDate.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + window);
        b.setPartnerId(COUNTERPARTY);
        b.setBusinessDate(businessDate);
        b.setStatus(SettlementBatchStatus.PENDING.name());
        b.setFileType(fileType);
        b.setDirection(DIRECTION);
        b.setSettlementWindow(window);
        b.setCreatedAt(Instant.now());
        return b;
    }
}
