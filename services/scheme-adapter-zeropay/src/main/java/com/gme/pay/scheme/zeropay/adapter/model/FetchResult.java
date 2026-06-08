package com.gme.pay.scheme.zeropay.adapter.model;

/** Result of fetching an inbound file from the scheme SFTP server. */
public record FetchResult(
        byte[] rawBytes,
        String remoteFilePath,
        long sizeBytes,
        String checksumSha256
) {}
