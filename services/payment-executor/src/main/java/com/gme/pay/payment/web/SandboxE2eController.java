package com.gme.pay.payment.web;

import com.gme.pay.payment.persistence.SandboxE2eRunEntity;
import com.gme.pay.payment.persistence.SandboxE2eRunRepository;
import com.gme.pay.payment.sandbox.E2eRunner;
import com.gme.pay.payment.sandbox.SandboxE2eMapper;
import com.gme.pay.payment.sandbox.dto.E2eOptions;
import com.gme.pay.payment.sandbox.dto.E2eRunDetail;
import com.gme.pay.payment.sandbox.dto.E2eRunRequest;
import com.gme.pay.payment.sandbox.dto.E2eRunSummary;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Sandbox End-to-End (E2E) payment test runner API, base path {@code /v1/sandbox/e2e}.
 *
 * <p>Additive to the service — it drives the existing {@code /v1/pay/classify} + {@code /v1/pay}
 * endpoints over loopback HTTP (see {@link E2eRunner}) and stores each run + steps for later review.
 *
 * <ul>
 *   <li>{@code GET  /options}    — the country / partner / MPM-type choices the UI offers.
 *   <li>{@code POST /run}        — execute a run and persist it; returns the run detail.
 *   <li>{@code GET  /runs}       — newest-first run summaries (limit via {@code ?limit=}).
 *   <li>{@code GET  /runs/{id}}  — a single run's detail incl. steps.
 * </ul>
 */
@RestController
@RequestMapping("/v1/sandbox/e2e")
public class SandboxE2eController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private final E2eRunner runner;
    private final SandboxE2eRunRepository runRepository;

    public SandboxE2eController(E2eRunner runner, SandboxE2eRunRepository runRepository) {
        this.runner = runner;
        this.runRepository = runRepository;
    }

    @GetMapping("/options")
    public E2eOptions options() {
        return new E2eOptions(
                List.of(
                        new E2eOptions.Country("NP", "Nepal", "NPR"),
                        new E2eOptions.Country("KR", "Korea", "KRW")),
                List.of(
                        new E2eOptions.Partner("GMEREMIT", "GMEREMIT"),
                        new E2eOptions.Partner("SENDMN", "SENDMN")),
                List.of("STATIC", "DYNAMIC"));
    }

    @PostMapping("/run")
    public E2eRunDetail run(@RequestBody E2eRunRequest req) {
        return runner.run(req.country(), req.partner(), req.amount(), req.mpmType());
    }

    @GetMapping("/runs")
    public List<E2eRunSummary> runs(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        int effective = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        return runRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, effective))
                .stream()
                .map(SandboxE2eMapper::toSummary)
                .toList();
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<E2eRunDetail> runDetail(@PathVariable("id") long id) {
        return runRepository.findByIdWithSteps(id)
                .map(SandboxE2eMapper::toDetail)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
