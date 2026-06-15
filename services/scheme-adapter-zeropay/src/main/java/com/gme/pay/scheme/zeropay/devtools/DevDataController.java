package com.gme.pay.scheme.zeropay.devtools;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only developer/diagnostic endpoint that introspects the service database.
 *
 * <p><strong>Disabled by default.</strong> Only registered when
 * {@code gmepay.devtools.enabled=true}. This is intended for local development and
 * trace-console tooling only — it must never be enabled in production.</p>
 *
 * <p>Exposes the PUBLIC schema base tables and a 500-row sample of any table.</p>
 */
@RestController
@RequestMapping("/__data")
@ConditionalOnProperty(name = "gmepay.devtools.enabled", havingValue = "true")
public class DevDataController {

    /** Hard cap on rows returned by the per-table endpoint. */
    private static final int ROW_LIMIT = 500;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final JdbcTemplate jdbcTemplate;

    public DevDataController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Lists every base table in the PUBLIC schema with its row count.
     *
     * <p>GET /__data/tables</p>
     *
     * @return list of {@code {"name": <table>, "rowCount": <long>}} ordered by table name
     */
    @GetMapping("/tables")
    public ResponseEntity<List<Map<String, Object>>> tables() {
        List<String> tableNames = listPublicTables();
        List<Map<String, Object>> result = new ArrayList<>(tableNames.size());
        for (String name : tableNames) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            try {
                Long count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM \"" + name + "\"", Long.class);
                entry.put("rowCount", count == null ? 0L : count);
            } catch (Exception ex) {
                entry.put("error", String.valueOf(ex.getMessage()));
            }
            result.add(entry);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Returns the columns and up to {@value #ROW_LIMIT} rows of a single table.
     *
     * <p>GET /__data/tables/{table}</p>
     *
     * <p>The {@code table} path variable is resolved case-insensitively against the
     * information_schema table list (404 if unknown), then the exact resolved identifier
     * is double-quoted into the query. No user input is interpolated into SQL — this
     * guards against injection.</p>
     *
     * @param table requested table name (any case)
     * @return {@code {"table", "columns", "rows", "rowCount", "truncated"}}, 404 if unknown,
     *         or 500 with an {@code {"error": ...}} map on failure
     */
    @GetMapping("/tables/{table}")
    public ResponseEntity<Map<String, Object>> table(@PathVariable("table") String table) {
        // INJECTION GUARD: resolve the requested name against the real table list and
        // only ever quote an exact name we read from information_schema.
        String resolved = resolveTableName(table);
        if (resolved == null) {
            Map<String, Object> notFound = new LinkedHashMap<>();
            notFound.put("error", "Unknown table: " + table);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(notFound);
        }

        try {
            Map<String, Object> body = jdbcTemplate.query(
                    "SELECT * FROM \"" + resolved + "\" FETCH FIRST " + ROW_LIMIT + " ROWS ONLY",
                    new TableExtractor(resolved));
            return ResponseEntity.ok(body);
        } catch (Exception ex) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("table", resolved);
            error.put("error", String.valueOf(ex.getMessage()));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Reads the base-table names from the PUBLIC schema (includes flyway_schema_history).
     */
    private List<String> listPublicTables() {
        return jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE UPPER(table_schema) = 'PUBLIC' "
                        + "AND UPPER(table_type) IN ('BASE TABLE', 'TABLE') "
                        + "ORDER BY table_name",
                String.class);
    }

    /**
     * Resolves a requested table name case-insensitively to its exact information_schema name.
     *
     * @return the exact stored table name, or {@code null} if no match exists
     */
    private String resolveTableName(String requested) {
        if (requested == null) {
            return null;
        }
        for (String name : listPublicTables()) {
            if (name.equalsIgnoreCase(requested)) {
                return name;
            }
        }
        return null;
    }

    /**
     * Extracts column metadata and JSON-safe row values from a result set.
     */
    private final class TableExtractor implements ResultSetExtractor<Map<String, Object>> {

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
                if (rows.size() >= ROW_LIMIT) {
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
    }

    /**
     * Converts a raw JDBC value into a JSON-serialisable representation.
     *
     * <ul>
     *   <li>numbers (incl. {@link BigDecimal}), strings and booleans are passed through as-is</li>
     *   <li>{@code byte[]} becomes {@code "0x"} + lowercase hex</li>
     *   <li>{@link Timestamp}, {@link java.sql.Date} and anything else becomes {@code String.valueOf(...)}</li>
     * </ul>
     */
    private static Object toJsonSafe(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof String) {
            // Number covers BigDecimal/BigInteger/Integer/Long/Double/etc. — pass through as-is.
            return value;
        }
        if (value instanceof byte[] bytes) {
            return toHex(bytes);
        }
        // Timestamp, java.sql.Date, java.util.Date, UUID, arrays, and any other type.
        return String.valueOf(value);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(2 + bytes.length * 2);
        sb.append("0x");
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0xF]);
            sb.append(HEX[b & 0xF]);
        }
        return sb.toString();
    }
}
