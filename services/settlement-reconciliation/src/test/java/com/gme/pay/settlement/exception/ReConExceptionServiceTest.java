package com.gme.pay.settlement.exception;

import com.gme.pay.settlement.persistence.ReconExceptionEntity;
import com.gme.pay.settlement.persistence.ReconExceptionRepository;
import com.gme.pay.settlement.recon.MatchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * H2 slice test for {@link ReConExceptionService}.
 *
 * <p>Uses {@link DataJpaTest} + H2 (PostgreSQL mode) — no Docker required.
 * Verifies the full resolve and re-run lifecycle against the real JPA layer.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import(ReConExceptionService.class)
class ReConExceptionServiceTest {

    @Autowired
    private ReconExceptionRepository repository;

    @Autowired
    private ReConExceptionService service;

    private ReconExceptionEntity openException;

    @BeforeEach
    void setUp() {
        openException = new ReconExceptionEntity(
                "BATCH-RECON-001",
                "MRC001",
                new BigDecimal("34720"),
                new BigDecimal("34000"),
                new BigDecimal("720"),
                MatchStatus.DISCREPANCY,
                Instant.parse("2026-06-15T01:05:00Z"));
        openException = repository.save(openException);
    }

    @Test
    @DisplayName("listExceptions returns all when no filters applied")
    void listAll_returnsAll() {
        List<ReconExceptionResponse> all = service.listExceptions(null, null, null);
        assertThat(all).isNotEmpty();
        assertThat(all).anyMatch(r -> r.merchantId().equals("MRC001"));
    }

    @Test
    @DisplayName("listExceptions filtered by OPEN status")
    void listByStatus_OPEN() {
        List<ReconExceptionResponse> open = service.listExceptions(null, ExceptionStatus.OPEN, null);
        assertThat(open).hasSize(1);
        assertThat(open.get(0).exceptionStatus()).isEqualTo(ExceptionStatus.OPEN);
    }

    @Test
    @DisplayName("listExceptions filtered by batchId")
    void listByBatchId() {
        List<ReconExceptionResponse> result = service.listExceptions("BATCH-RECON-001", null, null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).batchId()).isEqualTo("BATCH-RECON-001");
    }

    @Test
    @DisplayName("resolve: sets RESOLVED status, operator, note, action, resolvedAt")
    void resolve_setsAllFields() {
        ResolveExceptionRequest req = new ResolveExceptionRequest(
                "ops@gmeremit.com",
                "Verified with ZeroPay ops team — amount confirmed correct",
                "MANUAL_OVERRIDE");

        ReconExceptionResponse response = service.resolve(openException.getId(), req);

        assertThat(response.exceptionStatus()).isEqualTo(ExceptionStatus.RESOLVED);
        assertThat(response.operatorId()).isEqualTo("ops@gmeremit.com");
        assertThat(response.resolutionNote()).isEqualTo("Verified with ZeroPay ops team — amount confirmed correct");
        assertThat(response.resolutionAction()).isEqualTo("MANUAL_OVERRIDE");
        assertThat(response.resolvedAt()).isNotNull();
        assertThat(response.id()).isEqualTo(openException.getId());
    }

    @Test
    @DisplayName("resolve twice throws IllegalStateException")
    void resolveTwice_throws() {
        ResolveExceptionRequest req = new ResolveExceptionRequest(
                "ops@gmeremit.com", "note", "WAIVED");
        service.resolve(openException.getId(), req);

        assertThatThrownBy(() -> service.resolve(openException.getId(), req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already RESOLVED");
    }

    @Test
    @DisplayName("resolve unknown id throws NoSuchElementException")
    void resolveUnknown_throws() {
        ResolveExceptionRequest req = new ResolveExceptionRequest(
                "ops@gmeremit.com", "note", "WAIVED");
        assertThatThrownBy(() -> service.resolve(99999L, req))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("requestReRun: sets RE_RUN status and operatorId")
    void requestReRun_setsStatus() {
        ReconExceptionResponse response = service.requestReRun(openException.getId(), "ops@gmeremit.com");

        assertThat(response.exceptionStatus()).isEqualTo(ExceptionStatus.RE_RUN);
        assertThat(response.operatorId()).isEqualTo("ops@gmeremit.com");
    }

    @Test
    @DisplayName("requestReRun on RESOLVED exception throws IllegalStateException")
    void reRunResolved_throws() {
        ResolveExceptionRequest req = new ResolveExceptionRequest(
                "ops@gmeremit.com", "note", "WAIVED");
        service.resolve(openException.getId(), req);

        assertThatThrownBy(() -> service.requestReRun(openException.getId(), "ops@gmeremit.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already RESOLVED");
    }

    @Test
    @DisplayName("listExceptions filtered by exceptionStatus=RESOLVED after resolve")
    void listResolved_afterResolve() {
        ResolveExceptionRequest req = new ResolveExceptionRequest(
                "ops@gmeremit.com", "done", "MANUAL_OVERRIDE");
        service.resolve(openException.getId(), req);

        List<ReconExceptionResponse> resolved = service.listExceptions(null, ExceptionStatus.RESOLVED, null);
        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).exceptionStatus()).isEqualTo(ExceptionStatus.RESOLVED);

        List<ReconExceptionResponse> open = service.listExceptions(null, ExceptionStatus.OPEN, null);
        assertThat(open).isEmpty();
    }
}
