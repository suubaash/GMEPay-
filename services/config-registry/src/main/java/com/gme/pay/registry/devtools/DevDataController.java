package com.gme.pay.registry.devtools;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only DB-introspection endpoint for local development only.
 *
 * <p>Disabled by default. Enable with {@code gmepay.devtools.enabled=true}. This
 * is intended purely for the local dev trace-console and must never be enabled
 * in shared or production environments.
 *
 * <p>The {@link JdbcTemplate} is an auto-configured bean (Spring Data JPA + H2).
 */
@RestController
@RequestMapping("/__data")
@ConditionalOnProperty(name = "gmepay.devtools.enabled", havingValue = "true")
public class DevDataController {

    /** Hard cap on rows returned by the per-table endpoint. */
    private static final int ROW_LIMIT = 500;

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public DevDataController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Lists every base table in the PUBLIC schema with its row count.
     *
     * @return {@code [{"name": <tableName>, "rowCount": <long>}, ...]}
     */
    @GetMapping("/tables")
    public ResponseEntity<?> tables() {
        try {
            List<String> tableNames = listTableNames();
            List<Map<String, Object>> result = new ArrayList<>();
            for (String name : tableNames) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", name);
                try {
                    Long count = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM \"" + name + "\"", Long.class);
                    entry.put("rowCount", count == null ? 0L : count);
                } catch (DataAccessException ex) {
                    // One unreadable table must not break the whole listing.
                    entry.put("rowCount", -1L);
                    entry.put("error", ex.getMessage());
                }
                result.add(entry);
            }
            return ResponseEntity.ok(result);
        } catch (DataAccessException ex) {
            return ResponseEntity.status(500).body(errorMap(ex));
        }
    }

    /**
     * Dumps up to {@value #ROW_LIMIT} rows of a single table.
     *
     * @param table the table name (resolved case-insensitively against
     *              information_schema; never interpolated raw into SQL)
     * @return {@code {"table": name, "columns": [...], "rows": [[...]],
     *         "rowCount": n, "truncated": bool}} or 404 if unknown
     */
    @GetMapping("/tables/{table}")
    public ResponseEntity<?> table(@PathVariable("table") String table) {
        String resolved;
        try {
            resolved = resolveTableName(table);
        } catch (DataAccessException ex) {
            return ResponseEntity.status(500).body(errorMap(ex));
        }
        if (resolved == null) {
            return ResponseEntity.notFound().build();
        }

        // Safe: 'resolved' is the EXACT name returned by information_schema,
        // not user-controlled input. Double-quoted to preserve case/keywords.
        String sql = "SELECT * FROM \"" + resolved + "\" FETCH FIRST " + ROW_LIMIT + " ROWS ONLY";
        try {
            Map<String, Object> body = jdbcTemplate.query(sql, new TableExtractor(resolved));
            return ResponseEntity.ok(body);
        } catch (DataAccessException ex) {
            return ResponseEntity.status(500).body(errorMap(ex));
        }
    }

    /** Lists all base/view-less table names in the PUBLIC schema, sorted. */
    private List<String> listTableNames() {
        return jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE UPPER(table_schema)='PUBLIC' "
                        + "AND UPPER(table_type) IN ('BASE TABLE','TABLE') "
                        + "ORDER BY table_name",
                String.class);
    }

    /**
     * Resolves a requested name case-insensitively against the real table list.
     *
     * @return the exact stored table name, or {@code null} if no match
     */
    private String resolveTableName(String requested) {
        for (String name : listTableNames()) {
            if (name.equalsIgnoreCase(requested)) {
                return name;
            }
        }
        return null;
    }

    private static Map<String, Object> errorMap(Exception ex) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", ex.getMessage());
        return error;
    }

    /** Reads metadata + rows into a JSON-safe map. */
    private static final class TableExtractor implements ResultSetExtractor<Map<String, Object>> {

        private final String tableName;

        private TableExtractor(String tableName) {
            this.tableName = tableName;
        }

        @Override
        public Map<String, Object> extractData(ResultSet rs) throws SQLException, DataAccessException {
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

            boolean truncated = rows.size() == ROW_LIMIT;

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("table", tableName);
            body.put("columns", columns);
            body.put("rows", rows);
            body.put("rowCount", rows.size());
            body.put("truncated", truncated);
            return body;
        }

        /**
         * Coerces a JDBC value into something Jackson renders cleanly.
         * Numbers (incl. BigDecimal), strings and booleans pass through
         * untouched; byte[] becomes a 0x-prefixed hex string (audit hash
         * columns); everything else (Timestamp/Date/Time/UUID/...) is
         * stringified.
         */
        private static Object toJsonSafe(Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof Number || value instanceof String || value instanceof Boolean) {
                return value;
            }
            if (value instanceof byte[]) {
                return toHex((byte[]) value);
            }
            return String.valueOf(value);
        }

        private static String toHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder(2 + bytes.length * 2);
            sb.append("0x");
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        }
    }
}
