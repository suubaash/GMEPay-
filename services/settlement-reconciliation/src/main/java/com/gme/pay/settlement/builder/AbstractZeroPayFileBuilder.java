package com.gme.pay.settlement.builder;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Base for ZeroPay OUTBOUND settlement-request files (GME → ZeroPay, direction GME_TO_ZP). Mirrors the
 * inbound parser layout (HEADER / DATA / TRAILER) — see {@code ZP0062Parser} — so the request files we
 * emit round-trip against the same conventions the result files use.
 *
 * <p><b>TODO — confirmed placeholder:</b> the real field widths are IDD-pending, exactly like the inbound
 * parsers ({@code ZP0062Parser}). The widths used by the concrete builders come from service-backlog
 * 7.1-T09..T13 and will be corrected against the final ZeroPay IDD; isolating them here makes that a
 * one-place change. Output is EUC-KR for KFTC-cert fidelity; merchant-id/amount fields are ASCII-numeric
 * so the byte round-trip is currently safe.
 */
public abstract class AbstractZeroPayFileBuilder {

    protected static final Charset EUC_KR = Charset.forName("EUC-KR");

    /** File code, e.g. {@code "ZP0061"}. */
    protected abstract String fileCode();

    /** Build the full line list (header + data + trailer) and the checksum. */
    public abstract BuiltFile build(BuildContext ctx);

    /** Numeric field: right-justified, zero-padded to {@code width} (integer scale, no sign). */
    protected static String num(BigDecimal v, int width) {
        if (v.signum() < 0) {
            throw new IllegalStateException("negative value not allowed in numeric field: " + v);
        }
        String s = v.toBigInteger().toString();
        if (s.length() > width) {
            throw new IllegalStateException(fieldOverflow(s, width));
        }
        return "0".repeat(width - s.length()) + s;
    }

    /** Alphanumeric field: left-justified, space-padded to {@code width} BYTES in EUC-KR. */
    protected static String an(String v, int width) {
        if (v == null) {
            v = "";
        }
        int byteLen = v.getBytes(EUC_KR).length;
        if (byteLen > width) {
            throw new IllegalStateException(fieldOverflow(v, width));
        }
        return v + " ".repeat(width - byteLen);
    }

    /** HEADER = fileCode(6) + YYYYMMDD(8) + sequence(3) = 17 chars (mirrors ZP0062). */
    protected static String header(String code, String yyyymmdd, int seq) {
        return code + yyyymmdd + num(BigDecimal.valueOf(seq), 3);
    }

    /** TRAILER = "EOF" + dataRecordCount(10) + total(16) (mirrors ZP0062 EOF). */
    protected static String trailer(int dataCount, BigDecimal total) {
        return "EOF" + num(BigDecimal.valueOf(dataCount), 10) + num(total, 16);
    }

    /** Lowercase-hex SHA-256 over the EUC-KR bytes of the lines (each line + '\n'). */
    protected static String sha256Hex(List<String> lines) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String l : lines) {
                md.update(l.getBytes(EUC_KR));
                md.update((byte) '\n');
            }
            StringBuilder sb = new StringBuilder(64);
            for (byte x : md.digest()) {
                sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Null-safe BigDecimal: {@code null → ZERO}. KRW projections may arrive null on legacy rows. */
    protected static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String fieldOverflow(String v, int width) {
        return "field overflow: '" + v + "' exceeds width " + width;
    }

    /** Rendered lines + checksum + EUC-KR bytes (ready for the out-of-scope SFTP transmission step). */
    public record BuiltFile(String fileType, List<String> lines, String checksum, byte[] bytes,
                            int recordCount, BigDecimal trailerTotal) {}
}
