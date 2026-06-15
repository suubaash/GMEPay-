package com.gme.pay.scheme.zeropay.sftp;

import com.gme.pay.scheme.zeropay.adapter.model.FetchResult;
import com.gme.pay.scheme.zeropay.adapter.model.TransferResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LocalDirSftpTransport}.
 *
 * <p>Uses JUnit 5 {@code @TempDir} for clean per-test directories — no filesystem state
 * leaks between tests.</p>
 */
class LocalDirSftpTransportTest {

    @TempDir
    Path outboundDir;

    @TempDir
    Path inboundDir;

    // -----------------------------------------------------------------------
    // put (outbound)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("put: writes bytes to the outbound dir and returns correct metadata")
    void put_writesFileAndReturnsMetadata() {
        LocalDirSftpTransport transport = new LocalDirSftpTransport(
                outboundDir.toString(), inboundDir.toString());
        byte[] content = "ZP0011 file content".getBytes(StandardCharsets.UTF_8);

        TransferResult result = transport.put("ZP0011_20260615_001.dat", content);

        assertTrue(result.success());
        assertEquals(content.length, result.remoteSizeBytes());
        assertNotNull(result.checksumSha256());
        assertTrue(result.checksumSha256().length() == 64, "SHA-256 hex should be 64 chars");
    }

    @Test
    @DisplayName("put: creates parent directories if they do not exist")
    void put_createsParentDirs() {
        LocalDirSftpTransport transport = new LocalDirSftpTransport(
                outboundDir.toString(), inboundDir.toString());
        byte[] content = "data".getBytes();

        // Nested path that doesn't exist yet
        TransferResult result = transport.put("2026/06/15/ZP0011.dat", content);

        assertTrue(result.success());
    }

    @Test
    @DisplayName("put: two puts of the same path produce the same sha256")
    void put_sameBytesProduceSameSha256() {
        LocalDirSftpTransport transport = new LocalDirSftpTransport(
                outboundDir.toString(), inboundDir.toString());
        byte[] content = "consistent content".getBytes(StandardCharsets.UTF_8);

        TransferResult r1 = transport.put("file1.dat", content);
        TransferResult r2 = transport.put("file2.dat", content);

        assertEquals(r1.checksumSha256(), r2.checksumSha256());
    }

    // -----------------------------------------------------------------------
    // get (inbound)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("get: reads back exactly the bytes that were placed in the inbound dir")
    void get_readsCorrectBytes() throws Exception {
        LocalDirSftpTransport transport = new LocalDirSftpTransport(
                outboundDir.toString(), inboundDir.toString());
        byte[] expected = "ZP0012 inbound content".getBytes(StandardCharsets.UTF_8);
        Path file = inboundDir.resolve("ZP0012.dat");
        java.nio.file.Files.write(file, expected);

        FetchResult result = transport.get("ZP0012.dat");

        assertArrayEquals(expected, result.rawBytes());
        assertEquals(expected.length, result.sizeBytes());
        assertNotNull(result.checksumSha256());
    }

    @Test
    @DisplayName("get: throws SftpTransportException when file does not exist")
    void get_missingFile_throwsSftpTransportException() {
        LocalDirSftpTransport transport = new LocalDirSftpTransport(
                outboundDir.toString(), inboundDir.toString());

        assertThrows(SftpTransportException.class,
                () -> transport.get("nonexistent.dat"));
    }

    // -----------------------------------------------------------------------
    // put/get round-trip
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("put then get using the same dir round-trips bytes correctly")
    void putThenGet_roundTrip() {
        // Use inboundDir for both so we can put then get
        LocalDirSftpTransport transport = new LocalDirSftpTransport(
                inboundDir.toString(), inboundDir.toString());
        byte[] original = "Round-trip test content for ZP00xx files".getBytes(StandardCharsets.UTF_8);

        transport.put("roundtrip.dat", original);
        FetchResult result = transport.get("roundtrip.dat");

        assertArrayEquals(original, result.rawBytes());
    }
}
