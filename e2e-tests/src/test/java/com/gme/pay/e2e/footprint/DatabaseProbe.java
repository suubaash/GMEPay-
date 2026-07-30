package com.gme.pay.e2e.footprint;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Measures what a payment actually writes, by reading a service's own database directly.
 *
 * <h2>How it can read the fleet's databases at all</h2>
 *
 * <p>Every GMEPay+ service resolves its datasource from {@code SPRING_DATASOURCE_URL} and falls
 * back to a private in-memory H2 (see any service's {@code application.properties}). An
 * in-memory database lives and dies inside the service JVM and is unreachable from the test
 * process, so this harness starts the fleet with each service pointed at a <b>file-backed</b> H2
 * carrying {@code AUTO_SERVER=TRUE}. That flag makes the owning JVM accept a second connection
 * without any separate database server process being started — the test JVM opens the same URL
 * and reads the same tables the service is writing.
 *
 * <p>Two properties of that arrangement are worth stating because they are what make the
 * resulting numbers trustworthy. The service is completely unaware: nothing in its
 * configuration changes except a JDBC URL, the same Flyway migrations run, the same JPA
 * mappings write the same columns. And <b>the schema is the real one</b> — each service's
 * {@code src/main/resources/db/migration} holds PostgreSQL DDL, so the tables, columns and
 * indexes this probe reflects over are the same objects a production PostgreSQL would hold.
 *
 * <h2>What it measures, and what it does not</h2>
 *
 * <p>Row counts and payload widths are exact. Index <em>definitions</em> are exact. What H2
 * cannot supply is PostgreSQL's physical layout, so tuple headers, page overhead, B-tree
 * structure and WAL are handed to {@link PostgresSizeModel} to derive. See that class for the
 * accuracy claim and for what it would take to measure the second half too.
 */
final class DatabaseProbe implements AutoCloseable {

    /** Tables that exist for schema bookkeeping and would only add noise to a per-payment delta. */
    private static final Set<String> IGNORED = Set.of("flyway_schema_history", "shedlock");

    private final String database;
    private final Connection connection;

    /** Snapshot taken before the payment run: table -> (rowCount, per-column total bytes). */
    private Map<String, Scan> before = Map.of();

    /** Resolved once — the schema/case JDBC reports, not what the DDL looks like. */
    private List<TableRef> cachedTables;

    DatabaseProbe(String database, String jdbcUrl) throws SQLException {
        this.database = database;
        this.connection = DriverManager.getConnection(jdbcUrl, "sa", "");
    }

    String database() {
        return database;
    }

    /** Captures the pre-run state of every user table. */
    void snapshot() throws SQLException {
        before = scanAll();
    }

    /**
     * Re-scans and returns the per-table delta. Only tables that actually grew appear — a
     * payment path's footprint is the set of tables it writes, and listing the eighty it does
     * not would bury that.
     */
    List<TableFootprint> measure() throws SQLException {
        Map<String, Scan> after = scanAll();
        Map<String, Integer> indexCounts = indexCounts();
        Map<String, Set<String>> indexedColumns = indexedColumns();

        List<TableFootprint> out = new ArrayList<>();
        for (Map.Entry<String, Scan> entry : after.entrySet()) {
            String table = entry.getKey();
            Scan post = entry.getValue();
            Scan pre = before.getOrDefault(table, Scan.empty(post.columnCount));
            long rowsAdded = post.rowCount - pre.rowCount;
            if (rowsAdded <= 0) {
                continue;
            }
            long bytesAdded = post.totalBytes() - pre.totalBytes();
            long nullRowsAdded = Math.max(0, post.nullBearingRows - pre.nullBearingRows);
            int avgKey = averageIndexedColumnWidth(post, pre, rowsAdded,
                    indexedColumns.getOrDefault(table, Set.of()));
            out.add(new TableFootprint(
                    database,
                    table,
                    pre.rowCount,
                    post.rowCount,
                    Math.max(0, bytesAdded),
                    post.columnCount,
                    nullRowsAdded,
                    indexCounts.getOrDefault(table, 1),
                    avgKey));
        }
        out.sort((a, b) -> Long.compare(b.dbBytes(), a.dbBytes()));
        return out;
    }

    /**
     * Average width of the columns that are actually indexed, over the rows this run added.
     * Falls back to a plain {@code bigint} key when a table's indexes reference nothing we
     * scanned (a functional or expression index), which is the smallest honest assumption.
     */
    private static int averageIndexedColumnWidth(Scan post, Scan pre, long rowsAdded, Set<String> columns) {
        if (columns.isEmpty() || rowsAdded <= 0) {
            return 8;
        }
        long total = 0;
        int counted = 0;
        for (String column : columns) {
            Long postBytes = post.bytesByColumn.get(column);
            if (postBytes == null) {
                continue;
            }
            long preBytes = pre.bytesByColumn.getOrDefault(column, 0L);
            total += Math.max(0, postBytes - preBytes);
            counted++;
        }
        if (counted == 0) {
            return 8;
        }
        return Math.max(1, (int) (total / rowsAdded));
    }

    // -----------------------------------------------------------------------
    // Scanning
    // -----------------------------------------------------------------------

    private Map<String, Scan> scanAll() throws SQLException {
        Map<String, Scan> out = new LinkedHashMap<>();
        for (TableRef table : userTables()) {
            out.put(table.key(), scan(table));
        }
        return out;
    }

    /**
     * The application's tables, with the schema JDBC actually reports them under.
     *
     * <p>The schema is resolved from the metadata rather than assumed. The fleet runs H2 with
     * {@code DATABASE_TO_LOWER=TRUE} (the services' own setting, mirrored here so Flyway and
     * Hibernate behave identically), which makes the schema {@code public} in lower case — and a
     * hard-coded {@code "PUBLIC"} silently matches <em>nothing</em>, producing a table list of
     * zero and therefore a footprint of zero. A measurement harness that can fail to zero
     * quietly is worse than no harness, so the caller also asserts that some table grew.
     */
    private List<TableRef> userTables() throws SQLException {
        if (cachedTables != null) {
            return cachedTables;
        }
        List<TableRef> tables = new ArrayList<>();
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                String name = rs.getString("TABLE_NAME");
                if (name == null || isSystemSchema(schema)) {
                    continue;
                }
                if (!IGNORED.contains(name.toLowerCase(Locale.ROOT))) {
                    tables.add(new TableRef(schema, name));
                }
            }
        }
        cachedTables = tables;
        return tables;
    }

    private static boolean isSystemSchema(String schema) {
        if (schema == null) {
            return false;
        }
        String s = schema.toUpperCase(Locale.ROOT);
        return s.equals("INFORMATION_SCHEMA") || s.startsWith("SYS") || s.equals("PG_CATALOG");
    }

    /** A table as JDBC reports it — the exact schema and case needed to query it back. */
    private record TableRef(String schema, String name) {
        String key() {
            return name.toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Full scan of one table, accumulating PostgreSQL-equivalent payload width per column.
     *
     * <p>A full scan rather than an aggregate query because the widths wanted are per-value
     * (a {@code VARCHAR(200)} holding 12 characters costs 13 bytes in PostgreSQL, not 200), and
     * because the null pattern per row has to be observed to know whether the tuple carries a
     * null bitmap at all. At the row counts a footprint run produces this is trivially cheap.
     */
    private Scan scan(TableRef table) throws SQLException {
        Map<String, Long> bytesByColumn = new HashMap<>();
        long rows = 0;
        long nullBearingRows = 0;
        int columnCount;
        String qualified = (table.schema() == null ? "" : "\"" + table.schema() + "\".")
                + "\"" + table.name() + "\"";
        try (var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM " + qualified)) {
            ResultSetMetaData meta = rs.getMetaData();
            columnCount = meta.getColumnCount();
            while (rs.next()) {
                rows++;
                boolean rowHasNull = false;
                for (int i = 1; i <= columnCount; i++) {
                    String column = meta.getColumnName(i).toLowerCase(Locale.ROOT);
                    int width = valueBytes(rs, i, meta.getColumnType(i));
                    if (width < 0) {
                        rowHasNull = true;
                        width = 0;
                    }
                    bytesByColumn.merge(column, (long) width, Long::sum);
                }
                if (rowHasNull) {
                    nullBearingRows++;
                }
            }
        }
        return new Scan(rows, nullBearingRows, columnCount, bytesByColumn);
    }

    /**
     * PostgreSQL payload width of one value, or {@code -1} for SQL NULL.
     *
     * <p>Fixed-width types use their PostgreSQL widths. Variable-width types are measured and
     * given the varlena header PostgreSQL would actually choose for that length
     * ({@link PostgresSizeModel#varlenaHeader}) — a detail that matters because the money path
     * is dominated by short identifier columns where assuming the 4-byte header would overstate
     * every row.
     */
    private static int valueBytes(ResultSet rs, int index, int sqlType) throws SQLException {
        switch (sqlType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
                 Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB, Types.SQLXML -> {
                String value = rs.getString(index);
                if (value == null) {
                    return -1;
                }
                int payload = value.getBytes(StandardCharsets.UTF_8).length;
                return payload + PostgresSizeModel.varlenaHeader(payload);
            }
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> {
                byte[] value = rs.getBytes(index);
                if (value == null) {
                    return -1;
                }
                return value.length + PostgresSizeModel.varlenaHeader(value.length);
            }
            case Types.NUMERIC, Types.DECIMAL -> {
                java.math.BigDecimal value = rs.getBigDecimal(index);
                if (value == null) {
                    return -1;
                }
                // PostgreSQL numeric: 4-byte sign/weight/dscale struct + 2 bytes per 4 digits.
                int digits = Math.max(1, value.unscaledValue().abs().toString().length());
                int payload = 4 + 2 * ((digits + 3) / 4);
                return payload + PostgresSizeModel.varlenaHeader(payload);
            }
            case Types.BIGINT, Types.DOUBLE, Types.FLOAT, Types.TIMESTAMP,
                 Types.TIMESTAMP_WITH_TIMEZONE, Types.TIME, Types.TIME_WITH_TIMEZONE -> {
                return nullOr(rs, index, 8);
            }
            case Types.INTEGER, Types.REAL, Types.DATE -> {
                return nullOr(rs, index, 4);
            }
            case Types.SMALLINT -> {
                return nullOr(rs, index, 2);
            }
            case Types.TINYINT, Types.BIT, Types.BOOLEAN -> {
                return nullOr(rs, index, 1);
            }
            default -> {
                // uuid and anything else H2 reports opaquely: measure its text form, which is
                // an over-estimate for uuid (16 bytes native) and therefore never flatters.
                Object value = rs.getObject(index);
                if (value == null) {
                    return -1;
                }
                if (value instanceof java.util.UUID) {
                    return 16;
                }
                int payload = String.valueOf(value).getBytes(StandardCharsets.UTF_8).length;
                return payload + PostgresSizeModel.varlenaHeader(payload);
            }
        }
    }

    private static int nullOr(ResultSet rs, int index, int width) throws SQLException {
        rs.getObject(index);
        return rs.wasNull() ? -1 : width;
    }

    // -----------------------------------------------------------------------
    // Index reflection — the real Flyway-created definitions
    // -----------------------------------------------------------------------

    private Map<String, Integer> indexCounts() throws SQLException {
        Map<String, Set<String>> names = new HashMap<>();
        forEachIndex((table, indexName, column) ->
                names.computeIfAbsent(table, t -> new HashSet<>()).add(indexName));
        Map<String, Integer> counts = new HashMap<>();
        names.forEach((table, set) -> counts.put(table, set.size()));
        return counts;
    }

    private Map<String, Set<String>> indexedColumns() throws SQLException {
        Map<String, Set<String>> columns = new HashMap<>();
        forEachIndex((table, indexName, column) ->
                columns.computeIfAbsent(table, t -> new HashSet<>()).add(column));
        return columns;
    }

    private void forEachIndex(IndexVisitor visitor) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        for (TableRef table : userTables()) {
            try (ResultSet rs = meta.getIndexInfo(null, table.schema(), table.name(), false, true)) {
                while (rs.next()) {
                    String indexName = rs.getString("INDEX_NAME");
                    String column = rs.getString("COLUMN_NAME");
                    if (indexName == null || column == null) {
                        continue;
                    }
                    visitor.visit(table.key(), indexName.toLowerCase(Locale.ROOT),
                            column.toLowerCase(Locale.ROOT));
                }
            } catch (SQLException e) {
                // A table whose index metadata cannot be read still contributes its heap rows;
                // losing an index count understates, and understating loudly beats aborting.
                System.out.println("[footprint] index metadata unavailable for " + database
                        + "." + table.name() + ": " + e.getMessage());
            }
        }
    }

    @FunctionalInterface
    private interface IndexVisitor {
        void visit(String table, String indexName, String column);
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // best-effort: the fleet teardown is what actually matters
        }
    }

    /** One table's scanned state. */
    private record Scan(long rowCount, long nullBearingRows, int columnCount,
                        Map<String, Long> bytesByColumn) {

        static Scan empty(int columnCount) {
            return new Scan(0, 0, columnCount, Map.of());
        }

        long totalBytes() {
            long sum = 0;
            for (long v : bytesByColumn.values()) {
                sum += v;
            }
            return sum;
        }
    }
}
