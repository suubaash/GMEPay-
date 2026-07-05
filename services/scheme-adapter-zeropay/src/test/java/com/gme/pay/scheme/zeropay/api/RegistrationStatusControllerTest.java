package com.gme.pay.scheme.zeropay.api;

import com.gme.pay.scheme.zeropay.persistence.ZpBatchFileEntity;
import com.gme.pay.scheme.zeropay.persistence.ZpBatchFileRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The registration-status projection feeding settlement-reconciliation's §8.2 prerequisite gate:
 * ZP0011 counts only once TRANSMITTED; ZP0012 counts only in a received state (PARSE_ERROR is
 * not a result).
 */
class RegistrationStatusControllerTest {

    private final ZpBatchFileRepository repo = mock(ZpBatchFileRepository.class);
    private final RegistrationStatusController controller = new RegistrationStatusController(repo);
    private final LocalDate date = LocalDate.of(2026, 7, 5);

    @Test
    @DisplayName("ZP0011 TRANSMITTED + ZP0012 PARSED -> both legs true")
    void bothLegsComplete() {
        // Rows are built BEFORE the when() calls — creating a stubbed mock inside another
        // when(...) is unfinished-stubbing.
        List<ZpBatchFileEntity> zp0011 = List.of(row("ZP0011", ZpBatchFileEntity.STATUS_TRANSMITTED));
        List<ZpBatchFileEntity> zp0012 = List.of(row("ZP0012", ZpBatchFileEntity.STATUS_PARSED));
        when(repo.findByFileTypeAndBusinessDate(eq("ZP0011"), eq(date))).thenReturn(zp0011);
        when(repo.findByFileTypeAndBusinessDate(eq("ZP0012"), eq(date))).thenReturn(zp0012);

        RegistrationStatusController.RegistrationStatusView view = controller.status(date);
        assertTrue(view.zp0011Succeeded());
        assertTrue(view.zp0012Received());
    }

    @Test
    @DisplayName("ZP0011 only GENERATED (never left) -> zp0011Succeeded false")
    void generatedOnlyDoesNotCount() {
        List<ZpBatchFileEntity> zp0011 = List.of(row("ZP0011", ZpBatchFileEntity.STATUS_GENERATED));
        when(repo.findByFileTypeAndBusinessDate(eq("ZP0011"), eq(date))).thenReturn(zp0011);
        when(repo.findByFileTypeAndBusinessDate(eq("ZP0012"), eq(date))).thenReturn(List.of());

        RegistrationStatusController.RegistrationStatusView view = controller.status(date);
        assertFalse(view.zp0011Succeeded());
        assertFalse(view.zp0012Received());
    }

    @Test
    @DisplayName("ZP0012 PARSE_ERROR -> an unreadable result is not a result")
    void parseErrorDoesNotCountAsReceived() {
        List<ZpBatchFileEntity> zp0011 = List.of(row("ZP0011", ZpBatchFileEntity.STATUS_TRANSMITTED));
        List<ZpBatchFileEntity> zp0012 = List.of(row("ZP0012", ZpBatchFileEntity.STATUS_PARSE_ERROR));
        when(repo.findByFileTypeAndBusinessDate(eq("ZP0011"), eq(date))).thenReturn(zp0011);
        when(repo.findByFileTypeAndBusinessDate(eq("ZP0012"), eq(date))).thenReturn(zp0012);

        RegistrationStatusController.RegistrationStatusView view = controller.status(date);
        assertTrue(view.zp0011Succeeded());
        assertFalse(view.zp0012Received());
    }

    private ZpBatchFileEntity row(String fileType, String status) {
        ZpBatchFileEntity e = mock(ZpBatchFileEntity.class);
        when(e.getStatus()).thenReturn(status);
        return e;
    }
}
