package com.gme.pay.scheme.zeropay.adapter.model;

/** Result of an outbound SFTP file transfer. */
public record TransferResult(
        boolean success,
        String remoteFilePath,
        long remoteSizeBytes,
        String checksumSha256,
        int sequenceNo
) {}
