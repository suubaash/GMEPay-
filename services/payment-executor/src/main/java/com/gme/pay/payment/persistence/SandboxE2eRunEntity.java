package com.gme.pay.payment.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One row per sandbox E2E runner execution (Flyway V004, table {@code sandbox_e2e_run}).
 *
 * <p>Records the parameters a run was launched with (country / partner / amount / currency /
 * MPM type) and its overall outcome (status PASS/FAIL, first failed step, number of steps).
 * The ordered {@link SandboxE2eStepEntity steps} are owned by this aggregate and cascade-persisted.
 * Schema is owned by Flyway (V004), never by Hibernate.
 */
@Entity
@Table(name = "sandbox_e2e_run")
public class SandboxE2eRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "country")
    private String country;

    @Column(name = "partner")
    private String partner;

    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "currency")
    private String currency;

    @Column(name = "mpm_type")
    private String mpmType;

    @Column(name = "status")
    private String status;

    @Column(name = "failed_step")
    private String failedStep;

    @Column(name = "step_count")
    private Integer stepCount;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seq ASC")
    private List<SandboxE2eStepEntity> steps = new ArrayList<>();

    /** JPA only. */
    protected SandboxE2eRunEntity() {
    }

    /** Creates a run with its launch parameters; outcome fields are filled after execution. */
    public SandboxE2eRunEntity(Instant createdAt, String country, String partner,
                               BigDecimal amount, String currency, String mpmType) {
        this.createdAt = createdAt;
        this.country = country;
        this.partner = partner;
        this.amount = amount;
        this.currency = currency;
        this.mpmType = mpmType;
    }

    /** Appends a step and wires the back-reference so the cascade persists it. */
    public void addStep(SandboxE2eStepEntity step) {
        step.setRun(this);
        this.steps.add(step);
    }

    // ---- getters ----

    public Long getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCountry() {
        return country;
    }

    public String getPartner() {
        return partner;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getMpmType() {
        return mpmType;
    }

    public String getStatus() {
        return status;
    }

    public String getFailedStep() {
        return failedStep;
    }

    public Integer getStepCount() {
        return stepCount;
    }

    public List<SandboxE2eStepEntity> getSteps() {
        return steps;
    }

    // ---- setters for outcome fields learned after execution ----

    public void setStatus(String status) {
        this.status = status;
    }

    public void setFailedStep(String failedStep) {
        this.failedStep = failedStep;
    }

    public void setStepCount(Integer stepCount) {
        this.stepCount = stepCount;
    }
}
