package com.gme.pay.bff.web.dto;

import java.util.List;

/**
 * Generic page envelope used by paginated BFF endpoints. Mirrors the upstream
 * {@code TransactionMgmtClient.Page<T>} so the wire shape passes through
 * unchanged. Page numbering is 0-indexed.
 *
 * @param content rows on this page (already sliced)
 * @param page    zero-based page index that was returned
 * @param size    requested page size
 * @param total   total number of rows that matched the filter
 */
public record Page<T>(List<T> content, int page, int size, long total) {}
