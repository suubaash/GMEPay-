package com.gme.pay.scheme.zeropay.api;

import com.gme.pay.scheme.zeropay.persistence.ZpBatchFileEntity;
import com.gme.pay.scheme.zeropay.persistence.ZpBatchFileRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Registration-status projection for the settlement prerequisite gate (spec §8.2, tickets
 * 9.1-T18/T19): the ZP0061/ZP0063 settlement request for a business date may only be generated
 * once the ZP0011 payment-registration file for that date went out successfully AND the ZP0012
 * registration result came back from the scheme. settlement-reconciliation consults this
 * endpoint through its {@code RegistrationStatusPort} — it never reads this service's tables.
 *
 * <p>Derivation from {@code zp_batch_files} lifecycle rows:
 * <ul>
 *   <li>{@code zp0011Succeeded} — an OUTBOUND ZP0011 row for the date has advanced to
 *       {@code TRANSMITTED} (GENERATED alone means the bytes never left).</li>
 *   <li>{@code zp0012Received} — an INBOUND ZP0012 row for the date exists in any of the
 *       received states ({@code RECEIVED}/{@code PARSED}/{@code PROCESSED}); a
 *       {@code PARSE_ERROR} row does NOT count — an unreadable result is not a result.</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/scheme/zeropay/registration-status")
public class RegistrationStatusController {

    private final ZpBatchFileRepository batchFiles;

    public RegistrationStatusController(ZpBatchFileRepository batchFiles) {
        this.batchFiles = batchFiles;
    }

    @GetMapping
    public RegistrationStatusView status(
            @RequestParam("businessDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        boolean zp0011Succeeded = batchFiles.findByFileTypeAndBusinessDate("ZP0011", businessDate).stream()
                .anyMatch(f -> ZpBatchFileEntity.STATUS_TRANSMITTED.equals(f.getStatus()));
        boolean zp0012Received = batchFiles.findByFileTypeAndBusinessDate("ZP0012", businessDate).stream()
                .anyMatch(RegistrationStatusController::receivedState);
        return new RegistrationStatusView(businessDate, zp0011Succeeded, zp0012Received);
    }

    private static boolean receivedState(ZpBatchFileEntity f) {
        return List.of(ZpBatchFileEntity.STATUS_RECEIVED,
                        ZpBatchFileEntity.STATUS_PARSED,
                        ZpBatchFileEntity.STATUS_PROCESSED)
                .contains(f.getStatus());
    }

    /** Wire shape consumed by settlement-reconciliation's RestRegistrationStatusClient. */
    public record RegistrationStatusView(LocalDate businessDate, boolean zp0011Succeeded, boolean zp0012Received) {}
}
