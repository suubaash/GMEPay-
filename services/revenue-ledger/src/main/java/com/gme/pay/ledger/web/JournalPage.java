package com.gme.pay.ledger.web;

import java.util.List;

/**
 * Paged envelope for {@code GET /v1/journals}.
 *
 * <pre>
 *   { "items": [ JournalView, … ], "page": 0, "size": 50, "total": 137 }
 * </pre>
 *
 * @param items rows on this page (already sliced), newest-first by {@code createdAt}
 * @param page  zero-based page index returned
 * @param size  effective page size (after the 200 cap)
 * @param total total journals matching the filter across all pages
 */
public record JournalPage(List<JournalView> items, int page, int size, long total) {}
