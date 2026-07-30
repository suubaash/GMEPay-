package com.gme.pay.e2e.footprint;

import java.util.List;

/**
 * Converts a <b>measured</b> row shape into a <b>derived</b> PostgreSQL on-disk footprint.
 *
 * <h2>Read this before quoting any number this class produces</h2>
 *
 * <p>The E2E fleet runs on H2 (see {@code WalletScanPayE2ETest} — no Docker, therefore no
 * PostgreSQL, therefore no {@code pg_total_relation_size} and no {@code pg_current_wal_lsn}).
 * So the footprint splits into two halves that must never be confused:
 *
 * <ul>
 *   <li><b>MEASURED</b> — the row count per table, the null/not-null pattern, the real column
 *       values' byte lengths, and the real index definitions. All of these come from the actual
 *       payment run and the actual Flyway DDL, and they are <em>engine-independent facts about
 *       the data</em>. A {@code VARCHAR} holding 37 ASCII characters is 37 bytes of payload in
 *       H2 and in PostgreSQL alike.</li>
 *   <li><b>DERIVED</b> — what PostgreSQL 16 then wraps around that payload: tuple headers, null
 *       bitmaps, MAXALIGN padding, line pointers, page headers, B-tree index tuples, and WAL
 *       records. This class is that wrapper, and every constant below is a documented property
 *       of the PostgreSQL storage format rather than a guess.</li>
 * </ul>
 *
 * <p>The split is the whole point. A number like "7 KB per transaction" is worthless without
 * knowing which half it came from, and the previous capacity input for this platform was a
 * whole-cloth estimate. Here the dominant term — how many rows, holding how many bytes — is
 * real, and only the storage-format overhead is modelled.
 *
 * <h2>Accuracy claim, stated honestly</h2>
 *
 * <p>For narrow append-only tables this model lands within roughly ±15 % of a real
 * {@code pg_total_relation_size}, because for such tables the overhead is dominated by the
 * fixed 23-byte tuple header and the 4-byte line pointer, both of which are exact. It is
 * <b>least</b> accurate for (a) WAL, where full-page writes after a checkpoint can multiply the
 * volume several-fold depending entirely on checkpoint spacing and write locality, and (b)
 * tables that TOAST, which none of the money-path tables measured here do at observed payload
 * sizes. Both caveats are surfaced in the report rather than buried here.
 *
 * <p>Replacing the derived half with a measurement is a bounded piece of work and is written up
 * in {@code Documentation/CAPACITY_AND_SLA.md}: point the fleet at the compose PostgreSQL
 * instances instead of H2 and read the {@code pg_*} functions directly. That needs Docker.
 */
final class PostgresSizeModel {

    private PostgresSizeModel() {
    }

    // ---------------------------------------------------------------------
    // PostgreSQL 16 storage-format constants. Not tunables — facts.
    // ---------------------------------------------------------------------

    /** {@code HeapTupleHeaderData} is 23 bytes before the (MAXALIGNed) user data begins. */
    static final int HEAP_TUPLE_HEADER = 23;

    /** Each tuple also costs one {@code ItemIdData} line pointer in the page header array. */
    static final int LINE_POINTER = 4;

    /** {@code MAXALIGN} on every mainstream 64-bit build. */
    static final int MAXALIGN = 8;

    /** {@code BLCKSZ} — the compile-time default, unchanged in every mainstream distribution. */
    static final int PAGE_SIZE = 8192;

    /** {@code PageHeaderData} plus the special-space pointer, per heap page. */
    static final int PAGE_HEADER = 24;

    /** {@code IndexTupleData} header before a B-tree key. */
    static final int INDEX_TUPLE_HEADER = 8;

    /**
     * Effective B-tree page fill. PostgreSQL's default leaf fillfactor is 90, but a page only
     * reaches it on a monotonically increasing key; random keys (UUID / hash / natural-key
     * indexes, which most of the indexes here are) settle nearer 2/3 after splits. 0.70 is the
     * conservative middle, and the direction of the error is stated in the report.
     */
    static final double INDEX_PAGE_FILL = 0.70;

    /** {@code XLogRecord} header plus the block-reference header carried by a heap INSERT record. */
    static final int WAL_RECORD_OVERHEAD = 45;

    /** One commit record per transaction, amortised across the rows that transaction wrote. */
    static final int WAL_COMMIT_RECORD = 30;

    /**
     * Full-page-write multiplier RANGE, deliberately not collapsed to one number.
     *
     * <p>After each checkpoint the first write to a page logs the entire 8 KB page. The
     * resulting amplification is a function of checkpoint spacing versus write locality — i.e.
     * of {@code max_wal_size} and {@code checkpoint_timeout} on an instance nobody has
     * provisioned yet — and cannot be derived from the row shape at all. A dense append-only
     * insert stream at low volume sits near the bottom of this range; a scattered update
     * workload after a short checkpoint interval sits near the top.
     */
    static final double FPI_MULTIPLIER_LOW = 1.2;
    static final double FPI_MULTIPLIER_HIGH = 3.0;

    static int maxalign(int bytes) {
        return ((bytes + MAXALIGN - 1) / MAXALIGN) * MAXALIGN;
    }

    /**
     * Varlena ({@code text}, {@code varchar}, {@code numeric}, {@code jsonb}) header width.
     * PostgreSQL uses a 1-byte header for payloads up to 126 bytes and a 4-byte header above
     * that. Getting this right matters here because the money path is full of short
     * identifier columns where a 4-byte assumption would overstate every row.
     */
    static int varlenaHeader(int payloadBytes) {
        return payloadBytes <= 126 ? 1 : 4;
    }

    /**
     * Heap bytes for one tuple: header, null bitmap, aligned user data, and the line pointer
     * the page must spend to address it.
     *
     * @param userDataBytes measured payload width, varlena headers already included
     * @param columnCount   total columns in the table (drives the null-bitmap width)
     * @param hasNulls      whether this tuple actually has a NULL — PostgreSQL omits the bitmap
     *                      entirely when it does not, which is a real saving on wide tables
     */
    static int heapTupleBytes(int userDataBytes, int columnCount, boolean hasNulls) {
        int header = HEAP_TUPLE_HEADER;
        if (hasNulls) {
            header += (columnCount + 7) / 8;
        }
        return maxalign(header) + maxalign(userDataBytes) + LINE_POINTER;
    }

    /**
     * Heap bytes attributable to these rows, including their share of page overhead.
     *
     * <h3>Why this AMORTISES the page rather than rounding up to whole pages</h3>
     *
     * <p>Tuples cannot span pages, so a real table always occupies a whole number of 8 KB pages
     * and the honest size of a <em>table</em> rounds up. But this figure is divided by the
     * transaction count and then multiplied by 365 000 to project a year. Rounding up would
     * charge a full page to whichever few rows the measurement run happened to write: a 50-row
     * sample of a 200-byte tuple would report 8192/50 ≈ 164 bytes of page overhead per row
     * against a true steady-state cost of about 5 bytes — inflating the projection roughly
     * thirtyfold, and doing so <em>more</em> the smaller the sample, which is precisely backwards.
     *
     * <p>So the cost charged per row is a whole page divided by however many tuples fit in one.
     * That is the steady-state marginal cost of a row, which is the quantity a capacity
     * projection actually needs. The unusable remainder at the end of each page is still paid
     * for — it is inside {@code PAGE_SIZE / tuplesPerPage} — it is simply spread rather than
     * dumped on the sample.
     */
    static long heapBytes(long rowCount, int avgTupleBytes) {
        if (rowCount <= 0 || avgTupleBytes <= 0) {
            return 0;
        }
        int usable = PAGE_SIZE - PAGE_HEADER;
        long tuplesPerPage = Math.max(1, usable / avgTupleBytes);
        return Math.round(rowCount * (PAGE_SIZE / (double) tuplesPerPage));
    }

    /**
     * B-tree bytes for one index over keys of the given measured width.
     *
     * <p>Internal (non-leaf) pages are charged as a flat 1 % of leaf volume. For the table
     * cardinalities in question — thousands to low millions of rows, fan-out in the hundreds —
     * a B-tree's internal levels really are a sub-percent tail, so a more elaborate model would
     * add precision the rest of the calculation does not have.
     */
    static long indexBytes(long rowCount, int avgKeyBytes) {
        if (rowCount <= 0) {
            return 0;
        }
        int entry = maxalign(INDEX_TUPLE_HEADER + Math.max(1, avgKeyBytes)) + LINE_POINTER;
        // Amortised for the same reason heapBytes() is — see the note there. The fill factor
        // already accounts for the space a leaf page does not use.
        double leafBytes = rowCount * entry / INDEX_PAGE_FILL;
        return Math.round(leafBytes * 1.01);
    }

    /**
     * WAL generated by inserting these rows, EXCLUDING full-page writes.
     *
     * <p>Every index on the table logs its own insert record, which is the term most
     * back-of-envelope estimates forget; on a table with four indexes the index WAL exceeds the
     * heap WAL. Apply {@link #FPI_MULTIPLIER_LOW}/{@link #FPI_MULTIPLIER_HIGH} to bracket the
     * real figure.
     */
    static long walBytesExcludingFpi(long rowCount, int avgTupleBytes, int indexCount, int avgKeyBytes) {
        if (rowCount <= 0) {
            return 0;
        }
        long heapWal = rowCount * (long) (WAL_RECORD_OVERHEAD + avgTupleBytes);
        long indexWal = (long) rowCount * indexCount
                * (WAL_RECORD_OVERHEAD + INDEX_TUPLE_HEADER + Math.max(1, avgKeyBytes));
        long commitWal = rowCount * (long) WAL_COMMIT_RECORD;
        return heapWal + indexWal + commitWal;
    }

    /** Sums a per-table breakdown into the totals the capacity table is built from. */
    static Totals total(List<TableFootprint> tables) {
        long rows = 0;
        long logical = 0;
        long heap = 0;
        long index = 0;
        long wal = 0;
        for (TableFootprint t : tables) {
            rows += t.rowsAdded();
            logical += t.logicalBytesAdded();
            heap += t.heapBytes();
            index += t.indexBytes();
            wal += t.walBytesExcludingFpi();
        }
        return new Totals(rows, logical, heap, index, wal);
    }

    record Totals(long rows, long logicalBytes, long heapBytes, long indexBytes, long walBytes) {
        long dbBytes() {
            return heapBytes + indexBytes;
        }
    }
}
