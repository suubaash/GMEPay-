package com.gme.pay.scheme.zeropay.batch;

import java.time.format.DateTimeFormatter;

/**
 * Shared formatting helpers for all ZP00xx fixed-width file formatters.
 *
 * <p>Rules (SCH-06 §5.1):
 * <ul>
 *   <li>CHAR fields: left-aligned, space-padded on the right, truncated if too long.</li>
 *   <li>NUM fields: right-aligned, zero-padded on the left.</li>
 *   <li>Line separator: {@code \n} (LF). No trailing separator after the last line.</li>
 *   <li>Charset: UTF-8.</li>
 * </ul>
 * </p>
 */
final class ZpFileFormatUtils {

    static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HHmmss");

    private ZpFileFormatUtils() {}

    /**
     * Left-aligns {@code value} in a field of {@code width}, space-padded on the right.
     * Truncates if the value exceeds the field width.
     */
    static String padRight(String value, int width) {
        if (value == null) value = "";
        if (value.length() >= width) return value.substring(0, width);
        return String.format("%-" + width + "s", value);
    }

    /**
     * Right-aligns {@code value} in a field of {@code width}, zero-padded on the left.
     * Throws {@link IllegalArgumentException} if the value exceeds the field width.
     */
    static String padLeftZero(String value, int width) {
        if (value == null) value = "0";
        if (value.length() > width) {
            throw new IllegalArgumentException(
                    "Numeric value '" + value + "' exceeds field width " + width);
        }
        return String.format("%0" + width + "d", Long.parseLong(value));
    }
}
