package com.gme.pay.scheme.zeropay.sftp;

import com.gme.pay.scheme.zeropay.adapter.model.FetchResult;
import com.gme.pay.scheme.zeropay.adapter.model.TransferResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Local-directory stub implementation of {@link SftpTransport}.
 *
 * <p>This implementation writes outbound files to {@code gmepay.batch.outbound-dir} and reads
 * inbound files from {@code gmepay.batch.inbound-dir}. No real SFTP connection is made.</p>
 *
 * <p>It is the default (and only) active implementation. A real JSch/MINA SFTP implementation
 * should be introduced as a separate {@code @Profile("production")} or
 * {@code @ConditionalOnProperty} bean once ZeroPay PPF SFTP credentials are available.</p>
 */
@Component
public class LocalDirSftpTransport implements SftpTransport {

    private static final Logger log = LoggerFactory.getLogger(LocalDirSftpTransport.class);

    private final String outboundDir;
    private final String inboundDir;

    /**
     * Spring 6 / @Component with 2+ constructors: @Autowired is required on the
     * @Value-injecting constructor so Spring picks the right one.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public LocalDirSftpTransport(
            @Value("${gmepay.batch.outbound-dir:${java.io.tmpdir}/gmepay/outbound}") String outboundDir,
            @Value("${gmepay.batch.inbound-dir:${java.io.tmpdir}/gmepay/inbound}") String inboundDir) {
        this.outboundDir = outboundDir;
        this.inboundDir  = inboundDir;
    }

    @Override
    public TransferResult put(String remotePath, byte[] content) {
        Path target = Paths.get(outboundDir).resolve(remotePath);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            String sha256 = sha256Hex(content);
            log.info("[LocalDirSftp] PUT {} ({} bytes, sha256={})", target, content.length, sha256);
            return new TransferResult(true, target.toString(), content.length, sha256, 0);
        } catch (IOException e) {
            throw new SftpTransportException("Failed to write outbound file: " + target, e);
        }
    }

    @Override
    public FetchResult get(String remotePath) {
        Path source = Paths.get(inboundDir).resolve(remotePath);
        try {
            byte[] content = Files.readAllBytes(source);
            String sha256 = sha256Hex(content);
            log.info("[LocalDirSftp] GET {} ({} bytes, sha256={})", source, content.length, sha256);
            return new FetchResult(content, source.toString(), content.length, sha256);
        } catch (IOException e) {
            throw new SftpTransportException("Failed to read inbound file: " + source, e);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "sha256-unavailable";
        }
    }
}
