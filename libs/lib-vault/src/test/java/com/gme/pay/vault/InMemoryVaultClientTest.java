package com.gme.pay.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * Contract test of the {@link VaultClient} port against the dev/test
 * {@link InMemoryVaultClient}. What is pinned here is the behaviour
 * config-registry's document service builds on: ADR-006 path layout in the URI,
 * per-{@code (partnerCode, docType)} version monotony, streamed SHA-256, and
 * full metadata round-trip. The same expectations run against MinIO in the
 * docker-tagged {@code MinioVaultClientIT}.
 */
class InMemoryVaultClientTest {

    private final InMemoryVaultClient vault = new InMemoryVaultClient();

    private static InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void storeThenRetrieve_roundTripsContentAndMetadata() throws Exception {
        VaultObjectRef ref = vault.store("GMEREMIT", "LICENSE", "license-2026.pdf",
                "application/pdf", stream("PDF-BYTES"));

        assertThat(ref.uri())
                .startsWith("mem://gmepay-partner-vault/GMEREMIT/LICENSE/")
                .endsWith(".pdf");
        assertThat(ref.version()).isEqualTo(1);
        assertThat(ref.sha256())
                .hasSize(64)
                .isEqualTo(sha256("PDF-BYTES"));

        VaultObject retrieved = vault.retrieve(ref.uri());
        try (InputStream in = retrieved.content()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("PDF-BYTES");
        }
        assertThat(retrieved.filename()).isEqualTo("license-2026.pdf");
        assertThat(retrieved.contentType()).isEqualTo("application/pdf");
        assertThat(retrieved.size()).isEqualTo("PDF-BYTES".length());
        assertThat(retrieved.sha256()).isEqualTo(ref.sha256());
    }

    @Test
    void versions_incrementPerPartnerAndDocType_independently() {
        VaultObjectRef licenseV1 = vault.store("GMEREMIT", "LICENSE", "a.pdf", null, stream("v1"));
        VaultObjectRef licenseV2 = vault.store("GMEREMIT", "LICENSE", "b.pdf", null, stream("v2"));
        VaultObjectRef aoaV1 = vault.store("GMEREMIT", "AOA", "aoa.pdf", null, stream("aoa"));
        VaultObjectRef otherPartner = vault.store("OTHERCO", "LICENSE", "c.pdf", null, stream("x"));

        assertThat(licenseV1.version()).isEqualTo(1);
        assertThat(licenseV2.version()).isEqualTo(2);
        assertThat(licenseV2.uri()).contains("/LICENSE/").endsWith("/v2.pdf");
        assertThat(aoaV1.version()).as("doc types version independently").isEqualTo(1);
        assertThat(otherPartner.version()).as("partners version independently").isEqualTo(1);

        // No overwrite ever happened: all four objects remain retrievable.
        assertThat(vault.size()).isEqualTo(4);
        assertThat(vault.retrieve(licenseV1.uri()).sha256()).isEqualTo(licenseV1.sha256());
    }

    @Test
    void contentTypeDefaults_whenAbsent() throws IOException {
        VaultObjectRef ref = vault.store("GMEREMIT", "OTHER", "blob.bin", " ", stream("data"));
        VaultObject retrieved = vault.retrieve(ref.uri());
        retrieved.content().close();
        assertThat(retrieved.contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void filenameWithoutExtension_yieldsBareVersionSegment() {
        VaultObjectRef ref = vault.store("GMEREMIT", "OTHER", "README", null, stream("x"));
        assertThat(ref.uri()).endsWith("/v1");
    }

    @Test
    void retrieve_unknownUri_throwsVaultException() {
        assertThatThrownBy(() -> vault.retrieve("mem://gmepay-partner-vault/GHOST/LICENSE/x/v1"))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("no vault object");
        assertThatThrownBy(() -> vault.retrieve("s3://gmepay-partner-vault/foreign/uri/v1"))
                .isInstanceOf(VaultException.class);
        assertThatThrownBy(() -> vault.retrieve(null))
                .isInstanceOf(VaultException.class);
    }

    @Test
    void store_rejectsPathCorruptingTokens() {
        assertThatThrownBy(() -> vault.store("a/b", "LICENSE", "f.pdf", null, stream("x")))
                .isInstanceOf(VaultException.class);
        assertThatThrownBy(() -> vault.store("..", "LICENSE", "f.pdf", null, stream("x")))
                .isInstanceOf(VaultException.class);
        assertThatThrownBy(() -> vault.store("GMEREMIT", "LIC/ENSE", "f.pdf", null, stream("x")))
                .isInstanceOf(VaultException.class);
        assertThatThrownBy(() -> vault.store("GMEREMIT", "LICENSE", " ", null, stream("x")))
                .isInstanceOf(VaultException.class);
    }

    @Test
    void port_exposesNoDeleteMethod_objectLockContract() {
        // ADR-006: object-lock compliance mode — the port must not even offer a
        // delete. Guard against someone "helpfully" adding one later.
        assertThat(VaultClient.class.getMethods())
                .extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder("store", "retrieve");
    }
}
