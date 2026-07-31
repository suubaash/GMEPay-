package com.gme.pay.scheme.sendmn.crypto;

/**
 * Codec seam for the SendMN {@code encryptedData} body envelope.
 *
 * <p>Per {@code SMN_QRPayment_1.0.2} every business request/response body is a
 * single-field JSON envelope {@code {"encryptedData": "<base64>"}} whose value is the
 * real payload encrypted in RSA-4096 hybrid mode (AES session key wrapped with RSA).
 * The <b>exact</b> wire layout is NOT nailed down by the doc (the C# sample is an
 * image, and the response-decryption key ownership is contradictory — open issue O2),
 * so all envelope construction is isolated behind this interface:</p>
 *
 * <ul>
 *   <li>{@link PlainJsonEnvelopeCodec} — default ({@code sendmn.envelope.mode=plain}):
 *       base64 of the raw JSON, for local dev / sim-sendmn.</li>
 *   <li>{@link RsaAesEnvelopeCodec} — {@code sendmn.envelope.mode=rsa}: best-effort
 *       RSA-4096-OAEP-wrapped AES-256-GCM hybrid; ONLY this class changes once SendMN
 *       supplies the real sample code.</li>
 * </ul>
 */
public interface SendmnEnvelopeCodec {

    /** The configured mode name ({@code plain} / {@code rsa}) — for logs/diagnostics. */
    String mode();

    /** Encodes a plaintext JSON payload into the {@code encryptedData} value (base64). */
    String encrypt(String plainJson);

    /** Decodes an {@code encryptedData} value (base64) back into plaintext JSON. */
    String decrypt(String encryptedData);
}
