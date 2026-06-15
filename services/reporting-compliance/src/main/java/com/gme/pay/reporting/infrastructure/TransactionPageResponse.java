package com.gme.pay.reporting.infrastructure;

import java.util.List;

/**
 * Paged response from {@code GET /v1/transactions} on the transaction-mgmt service.
 *
 * <p>Field names match transaction-mgmt's {@code TransactionQueryPageResponse} wire shape exactly
 * (camelCase — Jackson binds by name; any mismatch silently produces null):
 * <pre>
 * {
 *   "content":       [ ...TransactionRecord... ],
 *   "page":          0,
 *   "size":          20,
 *   "totalElements": 142
 * }
 * </pre>
 *
 * <p>Note: the canonical contract does NOT include {@code totalPages}.
 * Pagination termination uses {@code totalElements / size} instead.
 */
public class TransactionPageResponse {

    private List<TransactionRecord> content;
    private int page;
    private int size;
    private long totalElements;

    public TransactionPageResponse() {}

    public List<TransactionRecord> getContent() { return content; }
    public void setContent(List<TransactionRecord> content) { this.content = content; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }

    public long getTotalElements() { return totalElements; }
    public void setTotalElements(long totalElements) { this.totalElements = totalElements; }

    /**
     * Computes the total number of pages from {@code totalElements} and {@code size}.
     * Returns 1 when {@code size} is 0 to avoid division-by-zero.
     */
    public int computeTotalPages() {
        if (size <= 0) return 1;
        return (int) Math.ceil((double) totalElements / size);
    }
}
