package com.gme.pay.trace;

/**
 * Minimal JSON object builder for trace events — dependency-free so lib-errors stays
 * Jackson-free. Only emits the flat shape the trace-console {@code /ingest} expects.
 */
final class TraceJson {

    private final StringBuilder sb = new StringBuilder(256);
    private boolean first = true;

    TraceJson() { sb.append('{'); }

    TraceJson str(String key, String value) {
        if (value == null) return this;
        sep();
        quote(key).append(':');
        quote(value);
        return this;
    }

    TraceJson num(String key, long value) {
        sep();
        quote(key).append(':').append(value);
        return this;
    }

    String end() {
        sb.append('}');
        return sb.toString();
    }

    private void sep() {
        if (!first) sb.append(',');
        first = false;
    }

    private StringBuilder quote(String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb;
    }
}
