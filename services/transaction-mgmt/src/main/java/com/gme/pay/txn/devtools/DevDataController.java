package com.gme.pay.txn.devtools;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only DB-introspection endpoints for local development.
 *
 * <p><b>Disabled by default.</b> Only registered when
 * {@code gmepay.devtools.enabled=true}. Never enable in production: it exposes
 * raw table contents.
 *
 * <p>Endpoints (all under {@code /__data}):
 * <ul>
 *   <li>{@code GET /__data/tables}          – list base tables + row counts</li>
 *   <li>{@code GET /__data/tables/{table}}  – dump up to 500 rows of one table</li>
 * </ul>
 *
 * <p>SQL-injection guard: the {@code {table}} path variable is never
 * interpolated into SQL directly. It is resolved (case-insensitively) against
 * the live {@code information_schema} table list; an unknown name yields 404.
 * Only the resolved, double-quoted identifier reaches the query.
 */
@RestController
@RequestMapping("/__data")
@ConditionalOnProperty(name = "gmepay.devtools.enabled", havingValue = "true")
public class DevDataController {

    /** Hard cap on rows returned by the per-table dump. */
    private static final int ROW_LIMIT = 500;

    private final JdbcTemplate jdbcTemplate;

    public DevDataController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // -------------------------------------------------------------------------
    // GET /__data/tables
    // -------------------------------------------------------------------------

    /**
     * Lists every base table in the PUBLIC schema (including
     * {@code flyway_schema_history}) with its row count.
     *
     * @return {@code [{"name":<t>,"rowCount":<long>}, ...]}
     */
    @GetMapping("/tables")
    public List<Map<String, Object>> tables() {
        List<String> names = listTableNames();
        List<Map<String, Object>> result = new ArrayList<>(names.size());
        for (String name : names) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            // Per-table try/catch so one unreadable table doesn't break the whole listing.
            try {
                Long count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM \"" + name + "\"", Long.class);
                entry.put("rowCount", count == null ? 0L : count);
            } catch (RuntimeException ex) {
                entry.put("error", String.valueOf(ex.getMessage()));
            }
            result.add(entry);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // GET /__data/tables/{table}
    // -------------------------------------------------------------------------

    /**
     * Dumps up to {@value #ROW_LIMIT} rows of a single table.
     *
     * @param table table name (case-insensitive); resolved against the live
     *              {@code information_schema} list. Unknown -> 404.
     * @return {@code {"table":name,"columns":[...],"rows":[[...]],"rowCount":n,"truncated":bool}}
     */
    @GetMapping("/tables/{table}")
    public ResponseEntity<Map<String, Object>> table(@PathVariable String table) {
        // INJECTION GUARD: resolve the requested name against the real catalog
        // instead of trusting / interpolating the raw path variable.
        String resolved = resolveTableName(table);
        if (resolved == null) {
            Map<String, Object> notFound = new LinkedHashMap<>();
            notFound.put("error", "Unknown table: " + table);
            return ResponseEntity.status(404).body(notFound);
        }

        // Only the resolved, catalog-verified identifier is double-quoted into SQL.
        String sql = "SELECT * FROM \"" + resolved + "\" FETCH FIRST " + ROW_LIMIT + " ROWS ONLY";

        try {
            Map<String, Object> body = jdbcTemplate.query(sql, new TableDumpExtractor(resolved));
            return ResponseEntity.ok(body);
        } catch (RuntimeException ex) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("table", resolved);
            error.put("error", String.valueOf(ex.getMessage()));
            return ResponseEntity.status(500).body(error);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Base-table names in the PUBLIC schema, ordered by name. */
    private List<String> listTableNames() {
        return jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE UPPER(table_schema) = 'PUBLIC' "
                        + "AND UPPER(table_type) IN ('BASE TABLE', 'TABLE') "
                        + "ORDER BY table_name",
                String.class);
    }

    /**
     * Resolves a requested table name (case-insensitively) to the exact name as
     * stored in the catalog, or {@code null} if no PUBLIC base table matches.
     */
    private String resolveTableName(String requested) {
        if (requested == null) {
            return null;
        }
        for (String name : listTableNames()) {
            if (name.equalsIgnoreCase(requested)) {
                return name;
            }
        }
        return null;
    }

    /** Converts a JDBC value into a JSON-safe representation. */
    private static Object toJsonSafe(Object value) {
        if (value == null) {
            return null;
        }
        // numbers (incl. BigDecimal), strings, booleans pass through as-is.
        if (value instanceof Number || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return "0x" + toHex(bytes);
        }
        // Timestamp / Date / Time / UUID / anything else -> string.
        return String.valueOf(value);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * Reads column names from {@link ResultSetMetaData} and each row's values
     * via {@link #toJsonSafe(Object)}, capping at {@value #ROW_LIMIT} rows and
     * flagging {@code truncated} when the cap is hit.
     */
    private static final class TableDumpExtractor implements ResultSetExtractor<Map<String, Object>> {

        private final String tableName;

        private TableDumpExtractor(String tableName) {
            this.tableName = tableName;
        }

        @Override
        public Map<String, Object> extractData(ResultSet rs) throws SQLException {
            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();

            List<String> columns = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                columns.add(meta.getColumnLabel(i));
            }

            List<List<Object>> rows = new ArrayList<>();
            while (rs.next()) {
                List<Object> row = new ArrayList<>(columnCount);
                for (int i = 1; i <= columnCount; i++) {
                    row.add(toJsonSafe(rs.getObject(i)));
                }
                rows.add(row);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("table", tableName);
            body.put("columns", columns);
            body.put("rows", rows);
            body.put("rowCount", rows.size());
            body.put("truncated", rows.size() >= ROW_LIMIT);
            return body;
        }
    }
}
