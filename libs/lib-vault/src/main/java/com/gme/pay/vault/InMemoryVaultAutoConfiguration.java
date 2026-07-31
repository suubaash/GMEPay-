package com.gme.pay.vault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Dev/test fallback wiring of the document vault: when nothing else provided a
 * {@link VaultClient} — i.e. {@code gmepay.vault.endpoint} is unset so
 * {@link MinioVaultAutoConfiguration} backed off — a heap-backed
 * {@link InMemoryVaultClient} is registered so services depending on the vault
 * boot with zero infrastructure.
 *
 * <p>Ordered {@code after} the MinIO auto-configuration so the
 * {@code @ConditionalOnMissingBean} check observes the production bean when the
 * endpoint property is present.
 */
@AutoConfiguration(after = MinioVaultAutoConfiguration.class)
@EnableConfigurationProperties(VaultProperties.class)
public class InMemoryVaultAutoConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(InMemoryVaultAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(VaultClient.class)
    public InMemoryVaultClient inMemoryVaultClient(VaultProperties properties) {
        // T1-4: this fallback used to be silent, and the consequence was invisible —
        // docker-compose ran MinIO but never set GMEPAY_VAULT_ENDPOINT for
        // config-registry, so uploaded KYB documents (business registration, AOA,
        // UBO declaration, Wolfsberg CBDDQ) went to the heap and vanished on
        // restart while their partner_document rows survived, pointing at objects
        // that no longer existed. One WARN at startup makes that state observable.
        //
        // The WARN also names the MULTI-REPLICA consequence, which the original did not. The heap
        // map is per-JVM, so at N>1 it is not merely volatile, it is INCONSISTENT: a document
        // stored through pod A is a 404 from pod B, and — the worse half — the version number is
        // derived by counting the keys THIS JVM holds under the (partnerCode, docType) prefix, so
        // two pods both mint "v1" for two different uploads of the same document type. Version
        // numbers are what a reviewer uses to tell the superseded KYB document from the current
        // one. Restart loss is recoverable by re-uploading; two different v1s is not obviously
        // wrong to anyone looking at it.
        //
        // Since MinioVaultClient learned to CLAIM its version (conditional PUT against an
        // append-only ledger, VaultVersionConflictException on a lost race), the two clients no
        // longer share a concurrency contract: the production one fails a colliding write loudly,
        // this one cannot detect the collision at all across JVMs. The WARN says so, because a
        // reader who knows the port "fails closed on version conflicts" would otherwise assume it
        // of whichever implementation is wired.
        log.warn("DOCUMENT VAULT IS IN-MEMORY: gmepay.vault.endpoint is not set, so every"
                + " uploaded document is held on the heap and LOST ON RESTART (the metadata rows"
                + " that reference it are not), and — above ONE REPLICA — a document stored on one"
                + " pod is a 404 from every other, while the (partnerCode, docType) version counter"
                + " is computed per-JVM so two pods MINT THE SAME VERSION NUMBER for different"
                + " documents SILENTLY: unlike the MinIO-backed client, this fallback has nothing"
                + " to compare-and-set against and can NEVER raise the port's"
                + " VaultVersionConflictException. Acceptable for unit slices and throwaway"
                + " single-process dev only. Set GMEPAY_VAULT_ENDPOINT (+ GMEPAY_VAULT_ACCESS_KEY /"
                + " GMEPAY_VAULT_SECRET_KEY) to use the S3/MinIO-backed vault. Bucket would be"
                + " '{}'.", properties.getBucket());
        return new InMemoryVaultClient(properties.getBucket());
    }
}
