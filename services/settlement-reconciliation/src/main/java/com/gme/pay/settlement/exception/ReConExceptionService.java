package com.gme.pay.settlement.exception;

import com.gme.pay.settlement.persistence.ReconExceptionEntity;
import com.gme.pay.settlement.persistence.ReconExceptionRepository;
import com.gme.pay.settlement.recon.MatchStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * Application service for the exception management API (UC-04-03).
 *
 * <p>All mutation methods log the operator action for the audit trail.
 */
@Service
public class ReConExceptionService {

    private static final Logger log = LoggerFactory.getLogger(ReConExceptionService.class);

    private final ReconExceptionRepository repository;

    public ReConExceptionService(ReconExceptionRepository repository) {
        this.repository = repository;
    }

    /**
     * List recon exceptions with optional filters.
     *
     * @param batchId         filter by batch id (null = all batches)
     * @param exceptionStatus filter by ops status (null = all statuses)
     * @param matchStatus     filter by recon match classification (null = all)
     * @return list of {@link ReconExceptionResponse} DTOs
     */
    @Transactional(readOnly = true)
    public List<ReconExceptionResponse> listExceptions(
            String batchId,
            ExceptionStatus exceptionStatus,
            MatchStatus matchStatus) {

        List<ReconExceptionEntity> entities;

        if (batchId != null && exceptionStatus != null) {
            entities = repository.findByBatchIdAndExceptionStatus(batchId, exceptionStatus);
        } else if (batchId != null) {
            entities = repository.findByBatchId(batchId);
        } else if (exceptionStatus != null && matchStatus != null) {
            entities = repository.findByExceptionStatusAndMatchStatus(exceptionStatus, matchStatus);
        } else if (exceptionStatus != null) {
            entities = repository.findByExceptionStatus(exceptionStatus);
        } else {
            entities = repository.findAll();
        }

        // Apply matchStatus post-filter when not already applied in query
        if (matchStatus != null && !(exceptionStatus != null && batchId == null)) {
            MatchStatus finalMatchStatus = matchStatus;
            entities = entities.stream()
                    .filter(e -> e.getMatchStatus() == finalMatchStatus)
                    .collect(Collectors.toList());
        }

        return entities.stream()
                .map(ReconExceptionResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * Resolve a recon exception — mark it RESOLVED with operator audit fields.
     *
     * @param id      PK of the exception
     * @param request resolution details from ops
     * @return updated {@link ReconExceptionResponse}
     * @throws NoSuchElementException   if no exception with that id exists
     * @throws IllegalStateException    if the exception is already RESOLVED
     */
    @Transactional
    public ReconExceptionResponse resolve(Long id, ResolveExceptionRequest request) {
        ReconExceptionEntity entity = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("ReconException not found: id=" + id));

        if (entity.getExceptionStatus() == ExceptionStatus.RESOLVED) {
            throw new IllegalStateException(
                    "ReconException id=" + id + " is already RESOLVED");
        }

        entity.setExceptionStatus(ExceptionStatus.RESOLVED);
        entity.setOperatorId(request.operatorId());
        entity.setResolutionNote(request.note());
        entity.setResolutionAction(request.resolutionAction());
        entity.setResolvedAt(Instant.now());

        ReconExceptionEntity saved = repository.save(entity);

        log.info("ReconException resolved: id={} batchId={} merchantId={} operator={} action={}",
                id, saved.getBatchId(), saved.getMerchantId(),
                request.operatorId(), request.resolutionAction());

        return ReconExceptionResponse.from(saved);
    }

    /**
     * Request a re-run of the recon diff for the batch window that contains this exception.
     *
     * <p>Marks the exception as {@link ExceptionStatus#RE_RUN}. The scheduler will pick this
     * up on the next recon window and re-run the diff for the batch; if the discrepancy is
     * resolved the exception will be auto-updated to RESOLVED.
     *
     * @param id         PK of the exception
     * @param operatorId operator requesting the re-run
     * @return updated {@link ReconExceptionResponse}
     * @throws NoSuchElementException if no exception with that id exists
     * @throws IllegalStateException  if the exception is already RESOLVED
     */
    @Transactional
    public ReconExceptionResponse requestReRun(Long id, String operatorId) {
        ReconExceptionEntity entity = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("ReconException not found: id=" + id));

        if (entity.getExceptionStatus() == ExceptionStatus.RESOLVED) {
            throw new IllegalStateException(
                    "ReconException id=" + id + " is already RESOLVED; cannot request re-run");
        }

        entity.setExceptionStatus(ExceptionStatus.RE_RUN);
        entity.setOperatorId(operatorId);

        ReconExceptionEntity saved = repository.save(entity);

        log.info("ReconException re-run requested: id={} batchId={} merchantId={} operator={}",
                id, saved.getBatchId(), saved.getMerchantId(), operatorId);

        return ReconExceptionResponse.from(saved);
    }
}
