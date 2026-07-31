package com.gme.pay.registry.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.vault.InMemoryVaultAutoConfiguration;
import com.gme.pay.vault.InMemoryVaultClient;
import com.gme.pay.vault.MinioVaultAutoConfiguration;
import com.gme.pay.vault.VaultClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.yaml.snakeyaml.Yaml;

/**
 * Gap T1-4, part 2: config-registry must resolve the MinIO-backed document vault
 * from the values {@code docker-compose.yml} actually sets.
 *
 * <p>The defect was a wiring hole, not a code bug: {@code GMEPAY_VAULT_ENDPOINT}
 * was set in the Helm values but in NO compose service, so lib-vault's
 * {@code @ConditionalOnProperty(prefix = "gmepay.vault", name = "endpoint")}
 * backed off and {@link InMemoryVaultClient} won — while compose was running
 * MinIO the whole time. Uploaded KYB documents lived on the heap and disappeared
 * on restart, leaving {@code partner_document} rows pointing at objects that no
 * longer existed.
 *
 * <p>This test reads the REAL compose file (no Docker, no server: the file is
 * parsed as data) and drives lib-vault's auto-configuration with exactly those
 * values, so it fails if either the compose entry or the property names drift.
 */
class ComposeVaultWiringTest {

    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MinioVaultAutoConfiguration.class, InMemoryVaultAutoConfiguration.class));

    /** Walk up from the module dir to the repo root that owns docker-compose.yml. */
    private static Path composeFile() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("docker-compose.yml");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("docker-compose.yml not found above " + Path.of("").toAbsolutePath());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> configRegistryEnv() throws IOException {
        String text = Files.readString(composeFile(), StandardCharsets.UTF_8);
        Map<String, Object> root = new Yaml().load(text);
        Map<String, Object> services = (Map<String, Object>) root.get("services");
        Map<String, Object> configRegistry = (Map<String, Object>) services.get("config-registry");
        return (Map<String, Object>) configRegistry.get("environment");
    }

    /** Compose interpolation ${VAR:-default} — resolve to the default, as CI does. */
    private static String resolved(Object raw) {
        String value = String.valueOf(raw);
        if (value.startsWith("${") && value.endsWith("}")) {
            int dash = value.indexOf(":-");
            return dash < 0 ? "" : value.substring(dash + 2, value.length() - 1);
        }
        return value;
    }

    @Test
    @DisplayName("compose sets the three keys lib-vault binds for config-registry")
    void composeCarriesTheVaultKeys() throws Exception {
        Map<String, Object> env = configRegistryEnv();

        assertThat(env).containsKey("GMEPAY_VAULT_ENDPOINT");
        assertThat(String.valueOf(env.get("GMEPAY_VAULT_ENDPOINT")))
                .as("the master switch must point at the MinIO service compose already runs")
                .isEqualTo("http://minio:9000");
        // Credentials must match the minio service's own defaults, or every PUT 403s.
        assertThat(resolved(env.get("GMEPAY_VAULT_ACCESS_KEY"))).isEqualTo("gmepay");
        assertThat(resolved(env.get("GMEPAY_VAULT_SECRET_KEY"))).isEqualTo("gmepay-minio");
    }

    @Test
    @DisplayName("those values resolve the MinIO-backed vault, not the heap-backed one")
    void composeValuesResolveTheMinioVault() throws Exception {
        Map<String, Object> env = configRegistryEnv();
        RUNNER.withPropertyValues(
                        "gmepay.vault.endpoint=" + env.get("GMEPAY_VAULT_ENDPOINT"),
                        "gmepay.vault.access-key=" + resolved(env.get("GMEPAY_VAULT_ACCESS_KEY")),
                        "gmepay.vault.secret-key=" + resolved(env.get("GMEPAY_VAULT_SECRET_KEY")))
                .run(context -> {
                    assertThat(context).hasSingleBean(VaultClient.class);
                    assertThat(context.getBean(VaultClient.class))
                            .as("documents must go to MinIO, not the heap")
                            .isNotInstanceOf(InMemoryVaultClient.class);
                    assertThat(context.getBean(VaultClient.class).getClass().getSimpleName())
                            .isEqualTo("MinioVaultClient");
                });
    }

    @Test
    @DisplayName("without the endpoint the vault is in-memory — the state this gap was about")
    void withoutTheEndpointTheVaultIsInMemory() {
        RUNNER.run(context -> assertThat(context.getBean(VaultClient.class))
                .isInstanceOf(InMemoryVaultClient.class));
    }
}
