package com.gme.pay.reporting.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Envelope returned by GET /v1/reports.
 */
public class ReportResponse {

    @JsonProperty("generated_at")
    private LocalDateTime generatedAt;

    @JsonProperty("total_count")
    private int totalCount;

    @JsonProperty("records")
    private List<BokFxRecordDto> records;

    public ReportResponse() {}

    public ReportResponse(List<BokFxRecordDto> records) {
        this.records = records;
        this.totalCount = records.size();
        this.generatedAt = LocalDateTime.now();
    }

    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }

    public int getTotalCount() { return totalCount; }
    public void setTotalCount(int totalCount) { this.totalCount = totalCount; }

    public List<BokFxRecordDto> getRecords() { return records; }
    public void setRecords(List<BokFxRecordDto> records) { this.records = records; }
}
