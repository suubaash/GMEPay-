package com.gme.pay.vault;

import java.io.InputStream;

/**
 * One retrieved vault object: the content stream plus the metadata recorded at
 * store time. Returned by {@link VaultClient#retrieve}.
 *
 * <p><b>Caller owns the stream</b> — close it (or wrap in try-with-resources /
 * hand to a streaming HTTP response) when done. For the MinIO backend the stream
 * is the live S3 GET body; leaving it open leaks a connection.
 *
 * <ul>
 *   <li>{@code content} — the object bytes, streamed (never fully buffered by
 *       the vault client).</li>
 *   <li>{@code filename} — the original upload filename (user metadata).</li>
 *   <li>{@code contentType} — the MIME type recorded at store time.</li>
 *   <li>{@code size} — object size in bytes, or {@code -1} when the backend
 *       cannot cheaply report it.</li>
 *   <li>{@code sha256} — lowercase hex SHA-256 recorded at store time (the same
 *       value the matching {@link VaultObjectRef} carried).</li>
 * </ul>
 */
public record VaultObject(
        InputStream content,
        String filename,
        String contentType,
        long size,
        String sha256) {
}
