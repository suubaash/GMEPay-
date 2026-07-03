package com.gme.pay.payment.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One row per ordered step within a sandbox E2E run (Flyway V004, table {@code sandbox_e2e_step}).
 *
 * <p>Each step records its sequence, name, outcome ({@code PASS}/{@code FAIL}/{@code SKIP}), a
 * human-readable detail, the wall-clock latency and (when the step made an HTTP call) the response
 * status. Owned by {@link SandboxE2eRunEntity}.
 */
@Entity
@Table(name = "sandbox_e2e_step")
public class SandboxE2eStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "run_id", nullable = false)
    private SandboxE2eRunEntity run;

    @Column(name = "seq")
    private Integer seq;

    @Column(name = "name")
    private String name;

    @Column(name = "status")
    private String status;

    @Column(name = "detail")
    private String detail;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "http_status")
    private Integer httpStatus;

    /** JPA only. */
    protected SandboxE2eStepEntity() {
    }

    public SandboxE2eStepEntity(int seq, String name, String status, String detail,
                                Long latencyMs, Integer httpStatus) {
        this.seq = seq;
        this.name = name;
        this.status = status;
        this.detail = detail;
        this.latencyMs = latencyMs;
        this.httpStatus = httpStatus;
    }

    // ---- getters ----

    public Long getId() {
        return id;
    }

    public SandboxE2eRunEntity getRun() {
        return run;
    }

    public Integer getSeq() {
        return seq;
    }

    public String getName() {
        return name;
    }

    public String getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    void setRun(SandboxE2eRunEntity run) {
        this.run = run;
    }
}
