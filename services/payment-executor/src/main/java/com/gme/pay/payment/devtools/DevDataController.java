package com.gme.pay.payment.devtools;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
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
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only DB-introspection dev tool (DISABLED by default).
 *
 * <p>Exposes the H2 schema contents for local debugging. Activated only when
 * {@code gmepay.devtools.enabled=true}; otherwise the bean is never registered.</p>
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /__data/tables          — every base table in the PUBLIC schema with its row count
 *   <li>GET /__data/tables/{table}  — column names + up to 500 rows of a single table
 * </ul>
 *
 * <p>SQL-injection guard: the {table} path variable is never interpolated into SQL.
 * It is resolved case-insensitively against the live information_schema table list,
 * and only the exact catalogued identifier (double-quoted) is used in the query.</p>
 */
@RestController
@RequestMapping("/__data")
@ConditionalOnProperty(name = "gmepay.devtools.enabled", havingValue = "true")
public class DevDataController {

    private static final int MAX_ROWS = 500;

    private final JdbcTemplate jdbcTemplate;

    public DevDataController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * GET /__data/tables — list every base table in the PUBLIC schema with its row count.
     * Includes {@code flyway_schema_history}. A per-table try/catch keeps one failing
     * COUNT(*) from sinking the whole listing.
     */
    @GetMapping("/tables")
    public ResponseEntity<List<Map<String, Object>>> listTables() {
        List<String> tableNames = listBaseTableNames();

        List<Map<String, Object>> result = new ArrayList<>(tableNames.size());
        for (String name : tableNames) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            try {
                Long count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM \"" + name + "\"", Long.class);
                entry.put("rowCount", count == null ? 0L : count);
            } catch (RuntimeException ex) {
                entry.put("error", ex.getMessage());
            }
            result.add(entry);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * GET /__data/tables/{table} — dump up to 500 rows of a single table.
     *
     * <p>The supplied name is resolved case-insensitively against the catalogue; an
     * unknown name yields 404. Any SQL/runtime failure yields 500 with an error map.</p>
     */
    @GetMapping("/tables/{table}")
    public ResponseEntity<Map<String, Object>> getTable(@PathVariable("table") String table) {
        String resolved = resolveTableName(table);
        if (resolved == null) {
            Map<String, Object> notFound = new LinkedHashMap<>();
            notFound.put("error", "Unknown table: " + table);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(notFound);
        }

        try {
            // resolved is the exact catalogued identifier — double-quoted, never user text.
            Map<String, Object> body = jdbcTemplate.query(
                    "SELECT * FROM \"" + resolved + "\" FETCH FIRST " + MAX_ROWS + " ROWS ONLY",
                    new TableExtractor(resolved));
            return ResponseEntity.ok(body);
        } catch (RuntimeException ex) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("table", resolved);
            error.put("error", ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    // ---- helpers ----

    /** Base tables in the PUBLIC schema, ordered by name (includes flyway_schema_history). */
    private List<String> listBaseTableNames() {
        return jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE UPPER(table_schema) = 'PUBLIC' "
                        + "AND UPPER(table_type) IN ('BASE TABLE', 'TABLE') "
                        + "ORDER BY table_name",
                String.class);
    }

    /**
     * Resolve a requested table name case-insensitively against the live catalogue.
     * Returns the exact catalogued identifier, or {@code null} if no match exists.
     */
    private String resolveTableName(String requested) {
        if (requested == null) {
            return null;
        }
        for (String name : listBaseTableNames()) {
            if (name.equalsIgnoreCase(requested)) {
                return name;
            }
        }
        return null;
    }

    /**
     * Extracts column metadata + JSON-safe row values from a ResultSet, capping at
     * {@link #MAX_ROWS} rows and flagging truncation.
     */
    private static final class TableExtractor implements ResultSetExtractor<Map<String, Object>> {

        private final String tableName;

        private TableExtractor(String tableName) {
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
            boolean truncated = false;
            while (rs.next()) {
                if (rows.size() >= MAX_ROWS) {
                    truncated = true;
                    break;
                }
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
            body.put("truncated", truncated);
            return body;
        }

        /**
         * Coerce a JDBC value into something Jackson can serialise cleanly.
         * Numbers (incl. BigDecimal), strings and booleans pass through as-is;
         * byte[] becomes a "0x"-prefixed hex string; Timestamp/Date and everything
         * else fall back to String.valueOf.
         */
        private static Object toJsonSafe(Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof Number || value instanceof String || value instanceof Boolean) {
                return value;
            }
            if (value instanceof byte[]) {
                return "0x" + toHex((byte[]) value);
            }
            if (value instanceof Timestamp || value instanceof java.util.Date) {
                return String.valueOf(value);
            }
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
    }
}
