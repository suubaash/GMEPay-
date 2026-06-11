package com.gme.pay.contracts;

import java.util.List;

/**
 * Generic page envelope for paginated read endpoints. Shared across
 * config-registry's audit and other paginated endpoints; the BFF passes this
 * through to the Admin UI unchanged (or wraps it in a BFF-local Page where the
 * BFF already has one).
 *
 * <p>Page numbering is 0-based. {@code total} is the total number of rows that
 * match the filter (across all pages), not just the current page's row count.
 *
 * @param <T>     the element type for this page.
 * @param content rows on this page (already sliced to {@code size} items).
 * @param page    zero-based page index that was returned.
 * @param size    requested (and applied) page size.
 * @param total   total number of rows that matched the filter.
 */
public record PageView<T>(List<T> content, int page, int size, long total) {
}
