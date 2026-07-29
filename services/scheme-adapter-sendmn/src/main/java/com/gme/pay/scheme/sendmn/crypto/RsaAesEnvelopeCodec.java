package com.gme.pay.scheme.sendmn.crypto;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;

/**
 * Best-effort RSA-4096 hybrid {@link SendmnEnvelopeCodec} ({@code sendmn.envelope.mode=rsa}).
 *
 * <p><b>WIRE SPEC PENDING (open issue O2)</b> — the SendMN doc says "RSA 4096, PKCS#8,
 * hybrid mode" but the C# sample is an image and the response key ownership is
 * contradictory. Until SendMN supplies the authoritative sample, this codec implements a
 * defensible layout and stays isolated so ONLY this class changes:</p>
 *
 * <pre>
 * encryptedData = base64(
 *     [2-byte big-endian wrappedKeyLen]
 *     [wrappedKey    = RSA/ECB/OAEPWithSHA-256AndMGF1Padding( AES-256 key ), 512 bytes for RSA-4096]
 *     [iv            = 12 random bytes]
 *     [ciphertext    = AES/GCM/NoPadding(plainJson), 128-bit tag appended]
 * )
 * </pre>
 *
 * <p>Requests encrypt toward <b>SendMN's public key</b>; responses decrypt with the
 * <b>partner's (our) private key</b> — the doc's "decrypt with SendMN's private key" is
 * assumed to be an error (a partner cannot hold their private key).</p>
 */
public class RsaAesEnvelopeCodec implements SendmnEnvelopeCodec {

    private static final String RSA_TRANSFORM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final OAEPParameterSpec OAEP_SHA256 = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    private static final String AES_TRANSFORM = "AES/GCM/NoPadding";
    private static final int AES_KEY_BITS = 256;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final PublicKey encryptKey;   // SendMN's RSA-4096 public key (request direction)
    private final PrivateKey decryptKey;  // our RSA-4096 private key (response direction)
    private final SecureRandom random = new SecureRandom();

    public RsaAesEnvelopeCodec(PublicKey encryptKey, PrivateKey decryptKey) {
        this.encryptKey = encryptKey;
        this.decryptKey = decryptKey;
    }

    @Override
    public String mode() {
        return "rsa";
    }

    @Override
    public String encrypt(String plainJson) {
        if (plainJson == null) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "sendmn rsa envelope: null payload");
        }
        if (encryptKey == null) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "sendmn rsa envelope: no SendMN public key configured (sendmn.envelope.rsa.sendmn-public-key)");
        }
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(AES_KEY_BITS, random);
            SecretKey aesKey = keyGen.generateKey();

            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            Cipher aes = Cipher.getInstance(AES_TRANSFORM);
            aes.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = aes.doFinal(plainJson.getBytes(StandardCharsets.UTF_8));

            Cipher rsa = Cipher.getInstance(RSA_TRANSFORM);
            rsa.init(Cipher.WRAP_MODE, encryptKey, OAEP_SHA256);
            byte[] wrappedKey = rsa.wrap(aesKey);

            ByteBuffer buf = ByteBuffer.allocate(2 + wrappedKey.length + iv.length + ciphertext.length);
            buf.putShort((short) wrappedKey.length);
            buf.put(wrappedKey);
            buf.put(iv);
            buf.put(ciphertext);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "sendmn rsa envelope: encryption failed: " + e.getMessage());
        }
    }

    @Override
    public String decrypt(String encryptedData) {
        if (encryptedData == null || encryptedData.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "sendmn rsa envelope: empty encryptedData");
        }
        if (decryptKey == null) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "sendmn rsa envelope: no partner private key configured (sendmn.envelope.rsa.partner-private-key)");
        }
        try {
            ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode(encryptedData));
            int wrappedLen = Short.toUnsignedInt(buf.getShort());
            byte[] wrappedKey = new byte[wrappedLen];
            buf.get(wrappedKey);
            byte[] iv = new byte[GCM_IV_BYTES];
            buf.get(iv);
            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);

            Cipher rsa = Cipher.getInstance(RSA_TRANSFORM);
            rsa.init(Cipher.UNWRAP_MODE, decryptKey, OAEP_SHA256);
            SecretKey aesKey = new SecretKeySpec(
                    ((SecretKey) rsa.unwrap(wrappedKey, "AES", Cipher.SECRET_KEY)).getEncoded(), "AES");

            Cipher aes = Cipher.getInstance(AES_TRANSFORM);
            aes.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(aes.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "sendmn rsa envelope: decryption failed: " + e.getMessage());
        }
    }
}
