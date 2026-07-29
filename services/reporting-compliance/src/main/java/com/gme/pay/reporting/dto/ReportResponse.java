package com.gme.pay.reporting.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gme.pay.reporting.channel.FilingChannelStatus;
import com.gme.pay.reporting.persistence.ReportFiling;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Envelope returned by GET /v1/reports.
 *
 * <h2>Filing honesty (GAP T5-2)</h2>
 * The records in this envelope are a <b>locally generated projection</b>. Nothing here has
 * been filed with the Bank of Korea. {@code filing_status} states the most advanced state a
 * BOK filing can currently reach and {@code filing_channels} lists every lane's channel
 * availability with the reason it is unavailable, so a consumer (or auditor reading the raw
 * JSON) cannot mistake a report for a filing.
 */
public class ReportResponse {

    @JsonProperty("generated_at")
    private LocalDateTime generatedAt;

    @JsonProperty("total_count")
    private int totalCount;

    @JsonProperty("records")
    private List<BokFxRecordDto> records;

    /**
     * Most advanced filing state reachable for the BOK lane right now — e.g.
     * {@code NOT_FILED_CHANNEL_UNAVAILABLE} while no BOK SFTP channel is configured.
     */
    @JsonProperty("filing_status")
    private ReportFiling.Status filingStatus;

    /** Why the BOK lane cannot file; null when a live BOK channel is configured. */
    @JsonProperty("filing_channel_unavailable_reason")
    private String filingChannelUnavailableReason;

    /** Channel availability for every regulatory lane (BOK, KOFIU, HOMETAX). */
    @JsonProperty("filing_channels")
    private List<FilingChannelStatus> filingChannels;

    public ReportResponse() {}

    public ReportResponse(List<BokFxRecordDto> records) {
        this.records = records;
        this.totalCount = records.size();
        this.generatedAt = LocalDateTime.now();
    }

    /**
     * @param records       generated BOK records
     * @param bokChannel    the BOK lane's channel status (drives {@code filing_status})
     * @param allChannels   channel status of every lane, for the readiness board
     */
    public ReportResponse(List<BokFxRecordDto> records,
                          FilingChannelStatus bokChannel,
                          List<FilingChannelStatus> allChannels) {
        this(records);
        this.filingStatus = bokChannel.reachableStatus();
        this.filingChannelUnavailableReason = bokChannel.reason();
        this.filingChannels = allChannels;
    }

    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }

    public int getTotalCount() { return totalCount; }
    public void setTotalCount(int totalCount) { this.totalCount = totalCount; }

    public List<BokFxRecordDto> getRecords() { return records; }
    public void setRecords(List<BokFxRecordDto> records) { this.records = records; }

    public ReportFiling.Status getFilingStatus() { return filingStatus; }
    public void setFilingStatus(ReportFiling.Status filingStatus) { this.filingStatus = filingStatus; }

    public String getFilingChannelUnavailableReason() { return filingChannelUnavailableReason; }
    public void setFilingChannelUnavailableReason(String reason) {
        this.filingChannelUnavailableReason = reason;
    }

    public List<FilingChannelStatus> getFilingChannels() { return filingChannels; }
    public void setFilingChannels(List<FilingChannelStatus> filingChannels) {
        this.filingChannels = filingChannels;
    }
}
