package com.gme.pay.e2e.footprint;

/**
 * One table's contribution to the per-transaction footprint.
 *
 * <p>The field names carry the measured/derived split deliberately, so that a reader of the
 * report — or of a future change to it — cannot lose track of which is which:
 *
 * <ul>
 *   <li>{@code rowsBefore} / {@code rowsAfter} / {@code logicalBytesAdded} / {@code columnCount}
 *       / {@code indexCount} / {@code avgKeyBytes} are <b>MEASURED</b> against the running
 *       fleet and its real Flyway schema.</li>
 *   <li>{@code heapBytes} / {@code indexBytes} / {@code walBytesExcludingFpi} are
 *       <b>DERIVED</b> by {@link PostgresSizeModel} from those measurements.</li>
 * </ul>
 *
 * @param database           owning service's logical DB name (one DB per service)
 * @param table              table name as the schema declares it
 * @param rowsBefore         MEASURED row count before the payment run
 * @param rowsAfter          MEASURED row count after it
 * @param logicalBytesAdded  MEASURED user-data bytes the new rows hold, varlena headers included
 * @param columnCount        MEASURED column count (drives the null-bitmap width)
 * @param nullBearingRows    MEASURED count of added rows carrying at least one NULL
 * @param indexCount         MEASURED number of indexes, read from the live schema
 * @param avgKeyBytes        MEASURED average width of the indexed columns
 */
record TableFootprint(
        String database,
        String table,
        long rowsBefore,
        long rowsAfter,
        long logicalBytesAdded,
        int columnCount,
        long nullBearingRows,
        int indexCount,
        int avgKeyBytes) {

    long rowsAdded() {
        return Math.max(0, rowsAfter - rowsBefore);
    }

    /** Average measured user-data width of the rows this run added. */
    int avgUserDataBytes() {
        long added = rowsAdded();
        return added == 0 ? 0 : (int) (logicalBytesAdded / added);
    }

    /** Average DERIVED PostgreSQL heap tuple width, including header, bitmap and line pointer. */
    int avgTupleBytes() {
        if (rowsAdded() == 0) {
            return 0;
        }
        boolean hasNulls = nullBearingRows > 0;
        return PostgresSizeModel.heapTupleBytes(avgUserDataBytes(), columnCount, hasNulls);
    }

    long heapBytes() {
        return PostgresSizeModel.heapBytes(rowsAdded(), avgTupleBytes());
    }

    long indexBytes() {
        return (long) indexCount * PostgresSizeModel.indexBytes(rowsAdded(), avgKeyBytes);
    }

    long walBytesExcludingFpi() {
        return PostgresSizeModel.walBytesExcludingFpi(
                rowsAdded(), avgTupleBytes(), indexCount, avgKeyBytes);
    }

    /** Total DERIVED retained bytes (heap + all indexes) for this table's added rows. */
    long dbBytes() {
        return heapBytes() + indexBytes();
    }
}
