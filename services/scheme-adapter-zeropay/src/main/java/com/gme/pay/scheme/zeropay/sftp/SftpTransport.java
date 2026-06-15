package com.gme.pay.scheme.zeropay.sftp;

import com.gme.pay.scheme.zeropay.adapter.model.FetchResult;
import com.gme.pay.scheme.zeropay.adapter.model.TransferResult;

/**
 * Anti-corruption port for SFTP file movement.
 *
 * <p>The default implementation ({@link LocalDirSftpTransport}) copies files between
 * local directories so that the batch scheduler can be exercised without real ZeroPay PPF
 * SFTP credentials. A production JSch/Apache MINA SFTP implementation can be swapped in
 * behind this interface once credentials are available.</p>
 *
 * <p>Callers must not catch {@link SftpTransportException} silently — it always indicates
 * a hard transfer failure that should be logged and retried by the scheduler.</p>
 */
public interface SftpTransport {

    /**
     * Writes {@code content} to a local or remote path (for outbound files).
     *
     * @param remotePath target path (relative to the configured outbound root)
     * @param content    file bytes
     * @return transfer metadata
     * @throws SftpTransportException on any I/O error
     */
    TransferResult put(String remotePath, byte[] content);

    /**
     * Reads a file from a local or remote path (for inbound files).
     *
     * @param remotePath source path (relative to the configured inbound root)
     * @return fetch result carrying raw bytes and metadata
     * @throws SftpTransportException if the file does not exist or cannot be read
     */
    FetchResult get(String remotePath);
}
