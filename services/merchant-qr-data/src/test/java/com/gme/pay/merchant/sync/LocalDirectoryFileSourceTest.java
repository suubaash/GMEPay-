package com.gme.pay.merchant.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LocalDirectoryFileSource} — the default {@link MerchantFileSource}
 * transport. No Spring context, no Docker.
 */
class LocalDirectoryFileSourceTest {

    @Test
    void listAvailableFiles_returnsRecognisedFilesSortedAndIgnoresOthers(@TempDir Path inbound)
            throws IOException {
        Files.writeString(inbound.resolve("ZP0043_20260615.dat"), "QR|Q1|M1|ACTIVE\n");
        Files.writeString(inbound.resolve("ZP0041_20260615.dat"), "MN|M1|Shop|RETAIL|DOMESTIC|ACTIVE|KRW|ZEROPAY|Seoul|5411\n");
        Files.writeString(inbound.resolve("README.md"), "ignore me\n");          // wrong extension
        Files.writeString(inbound.resolve("UNKNOWN_20260615.dat"), "x\n");        // unknown prefix

        LocalDirectoryFileSource source =
                new LocalDirectoryFileSource(inbound.toString(), "");

        List<Path> files = source.listAvailableFiles();

        assertEquals(2, files.size(), "only recognised ZeroPay files returned: " + files);
        assertEquals("ZP0041_20260615.dat", files.get(0).getFileName().toString(),
                "files must be sorted (ZP0041 before ZP0043)");
        assertEquals("ZP0043_20260615.dat", files.get(1).getFileName().toString());
    }

    @Test
    void listAvailableFiles_missingDir_returnsEmpty() throws IOException {
        LocalDirectoryFileSource source =
                new LocalDirectoryFileSource("./does-not-exist-" + System.nanoTime(), "");
        assertTrue(source.listAvailableFiles().isEmpty());
    }

    @Test
    void markProcessed_withArchiveDir_movesFileIntoDatedArchive(@TempDir Path root)
            throws IOException {
        Path inbound = Files.createDirectories(root.resolve("inbound"));
        Path archive = root.resolve("archive");
        Path file = inbound.resolve("ZP0041_20260615.dat");
        Files.writeString(file, "MN|M1|Shop|RETAIL|DOMESTIC|ACTIVE|KRW|ZEROPAY|Seoul|5411\n");

        LocalDirectoryFileSource source =
                new LocalDirectoryFileSource(inbound.toString(), archive.toString());

        source.markProcessed(file);

        assertFalse(Files.exists(file), "file must be moved out of inbound");
        Path expected = archive.resolve(LocalDate.now().toString()).resolve("ZP0041_20260615.dat");
        assertTrue(Files.exists(expected), "file must be archived to dated dir: " + expected);
    }

    @Test
    void markProcessed_archivingDisabled_leavesFileInPlace(@TempDir Path inbound)
            throws IOException {
        Path file = inbound.resolve("ZP0041_20260615.dat");
        Files.writeString(file, "MN|M1|Shop|RETAIL|DOMESTIC|ACTIVE|KRW|ZEROPAY|Seoul|5411\n");

        LocalDirectoryFileSource source =
                new LocalDirectoryFileSource(inbound.toString(), "");

        source.markProcessed(file);

        assertTrue(Files.exists(file), "file must remain when archiving is disabled");
    }

    @Test
    void markProcessed_alreadyMovedFile_isIdempotent(@TempDir Path root) {
        Path archive = root.resolve("archive");
        LocalDirectoryFileSource source =
                new LocalDirectoryFileSource(root.toString(), archive.toString());

        // File never existed — must not throw.
        assertDoesNotThrow(() -> source.markProcessed(root.resolve("gone.dat")));
    }
}
