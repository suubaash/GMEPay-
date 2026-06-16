package com.gme.pay.scheme.zeropay.jeonmun;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Encoder/decoder for ZeroPay fixed-field 전문 (jeonmun) messages.
 *
 * <p>A message is the byte-concatenation of its {@link FieldSpec} layout, padded per
 * {@link FieldType}. Lengths are measured in BYTES of the target charset (EUC-KR for
 * ZeroPay), so multibyte (Korean) values pad correctly. The online ZeroPay message body
 * is a fixed 1,000 bytes (fields 1..50); the 7-byte TCP/IP header (field 0) is the
 * transport frame and is handled by the socket layer, not by this codec.
 */
public final class JeonmunCodec {

    private JeonmunCodec() {}

    /** Sum of all field byte-lengths in a layout. */
    public static int length(List<FieldSpec> layout) {
        int n = 0;
        for (FieldSpec f : layout) n += f.length();
        return n;
    }

    /**
     * Encodes {@code values} (keyed by field No.) into a byte[] per {@code layout}.
     * Missing values default to empty (→ zero/space padding per type).
     *
     * @throws IllegalArgumentException on a value that overflows its field width or a
     *         non-numeric value in an N/NSP field
     * @throws IllegalStateException    when {@code expectedLength > 0} and the encoded
     *         length differs (guards against a mis-specified layout)
     */
    public static byte[] encode(List<FieldSpec> layout, Map<Integer, String> values,
                                Charset cs, int expectedLength) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (FieldSpec f : layout) {
            byte[] field = padField(values.getOrDefault(f.no(), ""), f, cs);
            out.write(field, 0, field.length);
        }
        byte[] msg = out.toByteArray();
        if (expectedLength > 0 && msg.length != expectedLength) {
            throw new IllegalStateException(
                    "jeonmun length " + msg.length + " != expected " + expectedLength);
        }
        return msg;
    }

    /**
     * Decodes a byte[] into a field-No.→value map per {@code layout}. Values are trimmed
     * per type (N: leading zeros stripped; AN/ANY: trailing spaces stripped; NSP: trimmed).
     */
    public static Map<Integer, String> decode(List<FieldSpec> layout, byte[] data,
                                              Charset cs, int expectedLength) {
        if (expectedLength > 0 && data.length != expectedLength) {
            throw new IllegalArgumentException(
                    "jeonmun length " + data.length + " != expected " + expectedLength);
        }
        Map<Integer, String> m = new LinkedHashMap<>();
        int pos = 0;
        for (FieldSpec f : layout) {
            if (pos + f.length() > data.length) {
                throw new IllegalArgumentException("jeonmun truncated at field " + f.no());
            }
            byte[] slice = Arrays.copyOfRange(data, pos, pos + f.length());
            pos += f.length();
            m.put(f.no(), trimByType(new String(slice, cs), f.type()));
        }
        return m;
    }

    // ---- padding ----

    private static byte[] padField(String raw, FieldSpec f, Charset cs) {
        if (raw == null) raw = "";
        switch (f.type()) {
            case N -> {
                String digits = raw.isBlank() ? "0" : raw.trim();
                requireNumeric(digits, f);
                return leftPad(bytesWithinWidth(digits, f, cs), f.length(), (byte) '0');
            }
            case NSP -> {
                if (raw.isBlank()) return fill(f.length(), (byte) ' ');
                String digits = raw.trim();
                requireNumeric(digits, f);
                return leftPad(bytesWithinWidth(digits, f, cs), f.length(), (byte) '0');
            }
            default -> { // A, AN, ANY — left-justified, space-padded
                return rightPad(bytesWithinWidth(raw, f, cs), f.length(), (byte) ' ');
            }
        }
    }

    private static byte[] bytesWithinWidth(String s, FieldSpec f, Charset cs) {
        byte[] b = s.getBytes(cs);
        if (b.length > f.length()) {
            throw new IllegalArgumentException(
                    "field " + f.no() + " (" + f.key() + ") overflow: " + b.length
                            + " bytes > width " + f.length() + " (value='" + s + "')");
        }
        return b;
    }

    private static void requireNumeric(String s, FieldSpec f) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                throw new IllegalArgumentException(
                        "field " + f.no() + " (" + f.key() + ") must be numeric, was '" + s + "'");
            }
        }
    }

    private static String trimByType(String s, FieldType t) {
        switch (t) {
            case N -> {
                // Keep the raw fixed-width digits: leading zeros are significant in code
                // fields (message_type "0200", trace no., dates). Amount fields still parse
                // cleanly via Long.parseLong, which ignores leading zeros.
                return s;
            }
            case NSP -> {
                return s.trim();
            }
            default -> {
                return s.replaceAll("\\s+$", "");
            }
        }
    }

    private static byte[] leftPad(byte[] b, int len, byte pad) {
        if (b.length == len) return b;
        byte[] out = new byte[len];
        int off = len - b.length;
        Arrays.fill(out, 0, off, pad);
        System.arraycopy(b, 0, out, off, b.length);
        return out;
    }

    private static byte[] rightPad(byte[] b, int len, byte pad) {
        if (b.length == len) return b;
        byte[] out = new byte[len];
        System.arraycopy(b, 0, out, 0, b.length);
        Arrays.fill(out, b.length, len, pad);
        return out;
    }

    private static byte[] fill(int len, byte pad) {
        byte[] o = new byte[len];
        Arrays.fill(o, pad);
        return o;
    }
}
