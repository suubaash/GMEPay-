package com.gme.pay.scheme.nepal.sign;

/**
 * Signing seam for the Khalti Issuance-Extension (Scan&amp;Pay) signed API.
 *
 * <p>Per {@code API-DOCS/issuance-extension.txt}, every {@code /pay/} and {@code /status/}
 * request is sent as a two-field envelope:
 * <pre>{ "data": &lt;base64(json)&gt;, "signature": &lt;base64(sig)&gt; }</pre>
 * where {@code json} carries the request fields plus a {@code nonce} (Nepal-time UNIX
 * seconds) that MUST equal the {@code X-KhaltiNonce} header. The signature is
 * {@code base64( RSA-2048 / PKCS#1 / SHA-256 sign( base64(json) ) )}.
 *
 * <p>This interface isolates that construction so the real RSA key material can be wired
 * later without touching the REST client. The default {@link StubNepalSigner} produces a
 * valid envelope with a placeholder signature (the sim accepts any signature); a
 * production implementation performs the real RSA signing with the Khalti-provided
 * private key.
 */
public interface NepalRequestSigner {

    /**
     * Build the signed envelope for a request payload.
     *
     * @param jsonPayload the request JSON <b>without</b> the {@code nonce} field; the signer
     *                    injects {@code nonce} = current epoch seconds and returns it so the
     *                    caller can set the matching {@code X-KhaltiNonce} header.
     * @return the envelope ({@code data}, {@code signature}) plus the {@code nonce} used.
     */
    SignedEnvelope sign(String jsonPayload);

    /** The {"data","signature"} envelope plus the nonce that was embedded in {@code data}. */
    record SignedEnvelope(String data, String signature, long nonce) {}
}
