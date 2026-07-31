package com.gme.pay.e2e.footprint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the DERIVED half of the T3-5 footprint measurement.
 *
 * <p>Deliberately <b>untagged</b>, so it runs in the ordinary {@code test} task and every
 * {@code gradlew build} re-proves it. The measured half needs a running fleet and can only be
 * checked by running it; the model that turns measurements into PostgreSQL bytes is pure
 * arithmetic and there is no excuse for it to be unverified. If someone "simplifies" the varlena
 * header or drops the per-index WAL term, the capacity projection silently changes by a large
 * factor — these assertions are what stops that being invisible.
 */
@DisplayName("PostgresSizeModel — the derived half of the capacity measurement")
class PostgresSizeModelTest {

    @Nested
    @DisplayName("varlena headers")
    class Varlena {

        @Test
        @DisplayName("short values get the 1-byte header, which is most of the money path")
        void shortValuesUseShortHeader() {
            assertEquals(1, PostgresSizeModel.varlenaHeader(1));
            assertEquals(1, PostgresSizeModel.varlenaHeader(37), "a typical txnRef");
            assertEquals(1, PostgresSizeModel.varlenaHeader(126), "the boundary, inclusive");
        }

        @Test
        @DisplayName("values over 126 bytes cross to the 4-byte header")
        void longValuesUseLongHeader() {
            assertEquals(4, PostgresSizeModel.varlenaHeader(127));
            assertEquals(4, PostgresSizeModel.varlenaHeader(4096), "an outbox payload");
        }
    }

    @Nested
    @DisplayName("heap tuples")
    class Heap {

        @Test
        @DisplayName("a tuple with no NULLs carries no null bitmap")
        void noNullsMeansNoBitmap() {
            int withoutNulls = PostgresSizeModel.heapTupleBytes(100, 20, false);
            int withNulls = PostgresSizeModel.heapTupleBytes(100, 20, true);
            assertTrue(withNulls > withoutNulls,
                    "a 20-column tuple with a NULL must pay for a 3-byte bitmap (MAXALIGNed)");
            assertEquals(PostgresSizeModel.maxalign(PostgresSizeModel.HEAP_TUPLE_HEADER)
                            + PostgresSizeModel.maxalign(100) + PostgresSizeModel.LINE_POINTER,
                    withoutNulls);
        }

        @Test
        @DisplayName("user data is MAXALIGNed, never counted raw")
        void userDataIsAligned() {
            // 97 bytes of payload occupies 104 on disk.
            int tuple = PostgresSizeModel.heapTupleBytes(97, 8, false);
            assertEquals(24 + 104 + 4, tuple);
        }

        @Test
        @DisplayName("page overhead is AMORTISED per row, not charged whole to the sample")
        void heapAmortisesPageOverhead() {
            // 40 tuples of 200 bytes fit in one 8 KB page (8168 usable / 200 = 40), so each row
            // costs 8192/40 = 204.8 bytes: its 200 plus a ~5-byte share of the page header and
            // the unusable tail.
            assertEquals(205, PostgresSizeModel.heapBytes(1, 200));
            assertEquals(8192, PostgresSizeModel.heapBytes(40, 200));
        }

        @Test
        @DisplayName("the per-row cost does not depend on the sample size — the projection bug")
        void perRowCostIsSampleSizeInvariant() {
            // This is the property that matters. Rounding up to whole pages made a 5-row sample
            // report ~30x the per-row cost of a 5000-row sample, and the projection multiplies
            // that figure by 365 000.
            double perRowSmall = PostgresSizeModel.heapBytes(5, 200) / 5.0;
            double perRowLarge = PostgresSizeModel.heapBytes(5_000, 200) / 5_000.0;
            assertEquals(perRowLarge, perRowSmall, 0.5,
                    "per-row heap cost must be independent of how many rows were sampled");
        }

        @Test
        @DisplayName("an empty table contributes nothing")
        void emptyTableIsZero() {
            assertEquals(0, PostgresSizeModel.heapBytes(0, 200));
            assertEquals(0, PostgresSizeModel.heapBytes(100, 0));
        }
    }

    @Nested
    @DisplayName("indexes")
    class Indexes {

        @Test
        @DisplayName("index volume accounts for page fill, not just key bytes")
        void indexAccountsForFill() {
            long bytes = PostgresSizeModel.indexBytes(10_000, 16);
            // 10k entries x (MAXALIGN(8+16)=24 + 4 pointer) = 280 000 bytes of keys; at 70 % fill
            // that is ~400 KB, i.e. materially more than a naive key-bytes-only estimate.
            assertTrue(bytes > 380_000 && bytes < 460_000,
                    "expected ~400 KB for 10k 16-byte keys at 70% fill, got " + bytes);
        }

        @Test
        @DisplayName("index cost per row is also sample-size invariant")
        void indexPerRowIsSampleSizeInvariant() {
            double small = PostgresSizeModel.indexBytes(5, 16) / 5.0;
            double large = PostgresSizeModel.indexBytes(50_000, 16) / 50_000.0;
            assertEquals(large, small, 0.5);
        }

        @Test
        @DisplayName("no rows means no index")
        void emptyIndexIsZero() {
            assertEquals(0, PostgresSizeModel.indexBytes(0, 16));
        }
    }

    @Nested
    @DisplayName("WAL")
    class Wal {

        @Test
        @DisplayName("every index logs its own insert record — the term estimates forget")
        void indexesDominateWalOnWideIndexedTables() {
            long noIndexes = PostgresSizeModel.walBytesExcludingFpi(1000, 200, 0, 16);
            long fourIndexes = PostgresSizeModel.walBytesExcludingFpi(1000, 200, 4, 16);
            assertTrue(fourIndexes > noIndexes,
                    "four indexes must add four insert records per row");
            long perRowIndexCost = (fourIndexes - noIndexes) / 1000;
            assertEquals(4L * (PostgresSizeModel.WAL_RECORD_OVERHEAD
                    + PostgresSizeModel.INDEX_TUPLE_HEADER + 16), perRowIndexCost);
        }

        @Test
        @DisplayName("the FPI range is a range, and it is not collapsed to a single number")
        void fpiIsBracketed() {
            assertTrue(PostgresSizeModel.FPI_MULTIPLIER_LOW > 1.0,
                    "full-page writes can only add to WAL, never reduce it");
            assertTrue(PostgresSizeModel.FPI_MULTIPLIER_HIGH > PostgresSizeModel.FPI_MULTIPLIER_LOW,
                    "a single FPI number would imply a checkpoint configuration nobody has chosen");
        }

        @Test
        @DisplayName("no rows means no WAL")
        void emptyWalIsZero() {
            assertEquals(0, PostgresSizeModel.walBytesExcludingFpi(0, 200, 3, 16));
        }
    }

    @Nested
    @DisplayName("totals")
    class Totals {

        @Test
        @DisplayName("totals sum the per-table breakdown the report prints")
        void totalsSum() {
            TableFootprint a = new TableFootprint("txndb", "transactions",
                    0, 10, 3000, 25, 10, 3, 20);
            TableFootprint b = new TableFootprint("txndb", "outbox",
                    5, 15, 8000, 6, 10, 2, 8);
            PostgresSizeModel.Totals totals = PostgresSizeModel.total(List.of(a, b));

            assertEquals(20, totals.rows(), "10 new transactions + 10 new outbox rows");
            assertEquals(11_000, totals.logicalBytes());
            assertEquals(a.heapBytes() + b.heapBytes(), totals.heapBytes());
            assertEquals(a.indexBytes() + b.indexBytes(), totals.indexBytes());
            assertEquals(totals.heapBytes() + totals.indexBytes(), totals.dbBytes());
        }

        @Test
        @DisplayName("a table that shrank never contributes negative rows")
        void shrinkingTableIsClamped() {
            TableFootprint pruned = new TableFootprint("txndb", "outbox",
                    100, 10, 0, 6, 0, 2, 8);
            assertEquals(0, pruned.rowsAdded(),
                    "a retention job running mid-measurement must not subtract from the footprint");
            assertEquals(0, pruned.avgUserDataBytes());
            assertEquals(0, pruned.dbBytes());
        }
    }
}
