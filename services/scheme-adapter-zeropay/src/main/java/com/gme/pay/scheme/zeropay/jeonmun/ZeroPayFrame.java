package com.gme.pay.scheme.zeropay.jeonmun;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The 7-byte TCP/IP transport frame (field No.0) that wraps a ZeroPay online 전문 body on the wire
 * (KFTC ZeroPay API §3.5). {@link ZeroPayMessages}/{@link ZeroPayMpm420000} encode only the
 * 1,000-byte BODY (fields 1..50); the socket layer is responsible for the length-prefixed frame —
 * this class is that frame.
 *
 * <p>The frame is {@code [7-byte ASCII zero-padded body length] + [body]}. A 1,000-byte body
 * therefore goes on the wire as {@code "0001000" + <1000 bytes>}. Reading is symmetric: read the
 * 7-byte header, parse the length, then read exactly that many body bytes.
 *
 * <p><b>Spec caveat:</b> the real KFTC 7-byte header may carry routing/message-class subfields in
 * addition to length; this implements the length-prefix framing the codec's "field 0" note refers
 * to and is finalised against the production header layout at integration time (same discipline as
 * the response-code mapping caveat in {@link ZeroPayMpm420000}).
 */
public final class ZeroPayFrame {

    private ZeroPayFrame() {}

    /** Length of the fixed transport header (field No.0). */
    public static final int HEADER_LENGTH = 7;

    /** Largest body the 7-digit header can frame (9,999,999 bytes) — guards against bad lengths. */
    private static final int MAX_BODY_LENGTH = 9_999_999;

    /** Wrap a 전문 body in its 7-byte length-prefixed transport frame. */
    public static byte[] frame(byte[] body) {
        if (body == null) {
            throw new IllegalArgumentException("body required");
        }
        if (body.length > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("body too large to frame: " + body.length);
        }
        byte[] header = String.format("%0" + HEADER_LENGTH + "d", body.length)
                .getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[HEADER_LENGTH + body.length];
        System.arraycopy(header, 0, out, 0, HEADER_LENGTH);
        System.arraycopy(body, 0, out, HEADER_LENGTH, body.length);
        return out;
    }

    /**
     * Read one framed 전문 body from {@code in}: read the 7-byte length header, then exactly that
     * many body bytes. Throws {@link EOFException} if the stream ends mid-frame and
     * {@link IOException} on a malformed (non-numeric) header.
     */
    public static byte[] readBody(InputStream in) throws IOException {
        byte[] header = readExactly(in, HEADER_LENGTH);
        String lenStr = new String(header, StandardCharsets.US_ASCII).trim();
        final int len;
        try {
            len = Integer.parseInt(lenStr);
        } catch (NumberFormatException e) {
            throw new IOException("malformed ZeroPay frame header: '" + lenStr + "'", e);
        }
        if (len < 0 || len > MAX_BODY_LENGTH) {
            throw new IOException("invalid ZeroPay frame body length: " + len);
        }
        return readExactly(in, len);
    }

    /** Read exactly {@code n} bytes or throw {@link EOFException} if the stream ends first. */
    private static byte[] readExactly(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) {
                throw new EOFException("stream ended after " + off + " of " + n + " bytes");
            }
            off += r;
        }
        return buf;
    }
}
