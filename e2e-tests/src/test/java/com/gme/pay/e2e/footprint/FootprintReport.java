package com.gme.pay.e2e.footprint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the measured footprint as the single table the capacity conversation needs.
 *
 * <p>The report leads with per-transaction figures rather than run totals, because that is the
 * unit every downstream projection multiplies. It labels each column MEASURED or DERIVED
 * in-line: a capacity input that loses that distinction is how the platform ended up quoting an
 * estimate as a measurement in the first place.
 */
final class FootprintReport {

    private final int transactions;
    private final List<TableFootprint> tables;
    private final Map<String, Long> logBytesByService;
    private final List<String> notes = new ArrayList<>();

    FootprintReport(int transactions, List<TableFootprint> tables, Map<String, Long> logBytesByService) {
        this.transactions = Math.max(1, transactions);
        this.tables = tables;
        this.logBytesByService = logBytesByService;
    }

    void note(String note) {
        notes.add(note);
    }

    PostgresSizeModel.Totals totals() {
        return PostgresSizeModel.total(tables);
    }

    long totalLogBytes() {
        return logBytesByService.values().stream().mapToLong(Long::longValue).sum();
    }

    /** Per-transaction rows across every measured table — the figure the estimate got wrong or right. */
    double rowsPerTxn() {
        return totals().rows() / (double) transactions;
    }

    double dbBytesPerTxn() {
        return totals().dbBytes() / (double) transactions;
    }

    double walBytesPerTxn() {
        return totals().walBytes() / (double) transactions;
    }

    double logBytesPerTxn() {
        return totalLogBytes() / (double) transactions;
    }

    String render() {
        PostgresSizeModel.Totals t = totals();
        StringBuilder sb = new StringBuilder();

        sb.append("# Measured per-transaction footprint\n\n");
        sb.append("Generated ").append(Instant.now()).append(" by `PerTxnFootprintE2ETest` ")
                .append("(`gradlew :e2e-tests:e2eTest --tests *PerTxnFootprintE2ETest*`).\n\n");
        sb.append("Successful payments driven through the real fleet: **")
                .append(transactions).append("**\n\n");

        sb.append("## Headline\n\n");
        sb.append("| Quantity | Per transaction | Basis |\n");
        sb.append("|---|---:|---|\n");
        sb.append(row("DB rows written", fmt(rowsPerTxn()), "**MEASURED** — exact count deltas"));
        sb.append(row("Row payload (logical)", bytes(t.logicalBytes() / (double) transactions),
                "**MEASURED** — real column values, PostgreSQL varlena headers"));
        sb.append(row("DB retained (heap + indexes)", bytes(dbBytesPerTxn()),
                "DERIVED — PostgreSQL 16 layout over measured rows/widths/index defs"));
        sb.append(row("WAL, excluding full-page writes", bytes(walBytesPerTxn()),
                "DERIVED — heap + per-index insert records + commit record"));
        sb.append(row("WAL, with full-page writes",
                bytes(walBytesPerTxn() * PostgresSizeModel.FPI_MULTIPLIER_LOW) + " – "
                        + bytes(walBytesPerTxn() * PostgresSizeModel.FPI_MULTIPLIER_HIGH),
                "DERIVED range — FPI depends on checkpoint spacing, not on row shape"));
        sb.append(row("Application logs", bytes(logBytesPerTxn()),
                "**MEASURED** — real stdout bytes emitted by the fleet during the run"));
        sb.append(row("Kafka", "not measured",
                "no broker in this fleet — see the caveats below"));
        sb.append("\n");

        sb.append("## Rows and bytes per table\n\n");
        sb.append("`rows/txn`, `payload/txn` and `idx` are MEASURED; `heap/txn`, `index/txn` and ")
                .append("`WAL/txn` are DERIVED from them.\n\n");
        sb.append("| Database | Table | rows/txn | payload/txn | cols | idx | heap/txn | index/txn | WAL/txn |\n");
        sb.append("|---|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (TableFootprint f : tables) {
            sb.append("| `").append(f.database()).append("` | `").append(f.table()).append("` | ")
                    .append(fmt(f.rowsAdded() / (double) transactions)).append(" | ")
                    .append(bytes(f.logicalBytesAdded() / (double) transactions)).append(" | ")
                    .append(f.columnCount()).append(" | ")
                    .append(f.indexCount()).append(" | ")
                    .append(bytes(f.heapBytes() / (double) transactions)).append(" | ")
                    .append(bytes(f.indexBytes() / (double) transactions)).append(" | ")
                    .append(bytes(f.walBytesExcludingFpi() / (double) transactions)).append(" |\n");
        }
        sb.append("| | **TOTAL** | **").append(fmt(rowsPerTxn())).append("** | **")
                .append(bytes(t.logicalBytes() / (double) transactions)).append("** | | | **")
                .append(bytes(t.heapBytes() / (double) transactions)).append("** | **")
                .append(bytes(t.indexBytes() / (double) transactions)).append("** | **")
                .append(bytes(walBytesPerTxn())).append("** |\n\n");

        sb.append("## Log bytes per service (MEASURED)\n\n");
        sb.append("| Service | Total bytes in run | Per transaction |\n|---|---:|---:|\n");
        logBytesByService.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> sb.append("| `").append(e.getKey()).append("` | ")
                        .append(bytes(e.getValue())).append(" | ")
                        .append(bytes(e.getValue() / (double) transactions)).append(" |\n"));
        sb.append("| **TOTAL** | **").append(bytes(totalLogBytes())).append("** | **")
                .append(bytes(logBytesPerTxn())).append("** |\n\n");

        sb.append("## Projection\n\n");
        sb.append("| Volume | rows/day | DB/day | DB/year | WAL/day (no FPI) | Logs/day |\n");
        sb.append("|---|---:|---:|---:|---:|---:|\n");
        for (int perDay : new int[]{1_000, 10_000, 100_000}) {
            sb.append("| ").append(String.format("%,d", perDay)).append(" txn/day | ")
                    .append(String.format("%,.0f", rowsPerTxn() * perDay)).append(" | ")
                    .append(bytes(dbBytesPerTxn() * perDay)).append(" | ")
                    .append(bytes(dbBytesPerTxn() * perDay * 365)).append(" | ")
                    .append(bytes(walBytesPerTxn() * perDay)).append(" | ")
                    .append(bytes(logBytesPerTxn() * perDay)).append(" |\n");
        }
        sb.append("\n");

        if (!notes.isEmpty()) {
            sb.append("## Caveats recorded by the run\n\n");
            notes.forEach(n -> sb.append("- ").append(n).append("\n"));
            sb.append("\n");
        }
        return sb.toString();
    }

    void writeTo(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("footprint.md"), render(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("footprint.json"), json(), StandardCharsets.UTF_8);
    }

    /** Hand-rolled JSON so the harness keeps its single, already-pinned Jackson dependency free. */
    private String json() {
        PostgresSizeModel.Totals t = totals();
        Map<String, String> top = new LinkedHashMap<>();
        top.put("schemaVersion", "1");
        top.put("generatedAt", "\"" + Instant.now() + "\"");
        top.put("transactions", String.valueOf(transactions));
        top.put("measured_rowsPerTxn", String.format("%.4f", rowsPerTxn()));
        top.put("measured_logicalBytesPerTxn",
                String.format("%.1f", t.logicalBytes() / (double) transactions));
        top.put("measured_logBytesPerTxn", String.format("%.1f", logBytesPerTxn()));
        top.put("derived_dbBytesPerTxn", String.format("%.1f", dbBytesPerTxn()));
        top.put("derived_walBytesPerTxnExcludingFpi", String.format("%.1f", walBytesPerTxn()));

        StringBuilder sb = new StringBuilder("{\n");
        top.forEach((k, v) -> sb.append("  \"").append(k).append("\": ")
                .append(v.startsWith("\"") ? v : v).append(",\n"));
        sb.append("  \"tables\": [\n");
        for (int i = 0; i < tables.size(); i++) {
            TableFootprint f = tables.get(i);
            sb.append("    {\"database\": \"").append(f.database())
                    .append("\", \"table\": \"").append(f.table())
                    .append("\", \"measured_rowsAdded\": ").append(f.rowsAdded())
                    .append(", \"measured_rowsPerTxn\": ")
                    .append(String.format("%.4f", f.rowsAdded() / (double) transactions))
                    .append(", \"measured_logicalBytesAdded\": ").append(f.logicalBytesAdded())
                    .append(", \"measured_columns\": ").append(f.columnCount())
                    .append(", \"measured_indexes\": ").append(f.indexCount())
                    .append(", \"derived_heapBytes\": ").append(f.heapBytes())
                    .append(", \"derived_indexBytes\": ").append(f.indexBytes())
                    .append(", \"derived_walBytes\": ").append(f.walBytesExcludingFpi())
                    .append("}").append(i == tables.size() - 1 ? "\n" : ",\n");
        }
        sb.append("  ],\n  \"measured_logBytesByService\": {\n");
        List<Map.Entry<String, Long>> logs = new ArrayList<>(logBytesByService.entrySet());
        for (int i = 0; i < logs.size(); i++) {
            sb.append("    \"").append(logs.get(i).getKey()).append("\": ")
                    .append(logs.get(i).getValue())
                    .append(i == logs.size() - 1 ? "\n" : ",\n");
        }
        sb.append("  },\n  \"caveats\": [\n");
        for (int i = 0; i < notes.size(); i++) {
            sb.append("    \"").append(notes.get(i).replace("\"", "'").replace("`", ""))
                    .append("\"").append(i == notes.size() - 1 ? "\n" : ",\n");
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    private static String row(String label, String value, String basis) {
        return "| " + label + " | " + value + " | " + basis + " |\n";
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }

    static String bytes(double b) {
        if (b >= 1024L * 1024 * 1024) {
            return String.format("%.2f GB", b / (1024d * 1024 * 1024));
        }
        if (b >= 1024 * 1024) {
            return String.format("%.2f MB", b / (1024d * 1024));
        }
        if (b >= 1024) {
            return String.format("%.2f KB", b / 1024d);
        }
        return String.format("%.0f B", b);
    }
}
