package com.gme.pay.reporting.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Paged response from {@code GET /v1/transactions} on the transaction-mgmt service.
 *
 * <p>Jackson binds by field name; names must match transaction-mgmt's API exactly.
 */
public class TransactionPageResponse {

    @JsonProperty("content")
    private List<TransactionRecord> content;

    @JsonProperty("total_elements")
    private long totalElements;

    @JsonProperty("total_pages")
    private int totalPages;

    @JsonProperty("page")
    private int page;

    public TransactionPageResponse() {}

    public List<TransactionRecord> getContent() { return content; }
    public void setContent(List<TransactionRecord> content) { this.content = content; }

    public long getTotalElements() { return totalElements; }
    public void setTotalElements(long totalElements) { this.totalElements = totalElements; }

    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
}
