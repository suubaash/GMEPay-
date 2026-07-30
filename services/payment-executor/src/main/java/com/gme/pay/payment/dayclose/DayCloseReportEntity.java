package com.gme.pay.payment.dayclose;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.time.LocalDate;

/**
 * JPA entity mapping {@code day_close_reports} (Flyway V009) — one persisted day-close artifact per business
 * date (gap <b>T2-5</b> / CFO#10, whose "Done =" asked for a close that is "persisted and exportable").
 *
 * <p>The full report rides as JSON in {@link #getReportJson()}; the broken-out columns are the only ones
 * anything queries on. See the migration's header for why that split, and why
 * {@code unresolved_decision_count} is a first-class column.
 */
@Entity
@Table(name = "day_close_reports",
        uniqueConstraints = @UniqueConstraint(name = "uq_day_close_reports_date",
                columnNames = {"business_date"}))
public class DayCloseReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "trigger_source", length = 16, nullable = false)
    private String triggerSource;

    @Column(name = "clean", nullable = false)
    private boolean clean;

    @Column(name = "variance_count", nullable = false)
    private int varianceCount;

    @Column(name = "unresolved_decision_count", nullable = false)
    private int unresolvedDecisionCount;

    @Column(name = "unavailable_leg_count", nullable = false)
    private int unavailableLegCount;

    @Column(name = "report_json", nullable = false)
    private String reportJson;

    public DayCloseReportEntity() {
        // JPA
    }

    public Long getId() {
        return id;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public void setBusinessDate(LocalDate businessDate) {
        this.businessDate = businessDate;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(Instant generatedAt) {
        this.generatedAt = generatedAt;
    }

    public String getTriggerSource() {
        return triggerSource;
    }

    public void setTriggerSource(String triggerSource) {
        this.triggerSource = triggerSource;
    }

    public boolean isClean() {
        return clean;
    }

    public void setClean(boolean clean) {
        this.clean = clean;
    }

    public int getVarianceCount() {
        return varianceCount;
    }

    public void setVarianceCount(int varianceCount) {
        this.varianceCount = varianceCount;
    }

    public int getUnresolvedDecisionCount() {
        return unresolvedDecisionCount;
    }

    public void setUnresolvedDecisionCount(int unresolvedDecisionCount) {
        this.unresolvedDecisionCount = unresolvedDecisionCount;
    }

    public int getUnavailableLegCount() {
        return unavailableLegCount;
    }

    public void setUnavailableLegCount(int unavailableLegCount) {
        this.unavailableLegCount = unavailableLegCount;
    }

    public String getReportJson() {
        return reportJson;
    }

    public void setReportJson(String reportJson) {
        this.reportJson = reportJson;
    }
}
