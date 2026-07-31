package com.gme.pay.bff.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.contracts.PartnerView;
import com.gme.pay.domain.PartnerType;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gap T1-3: {@link PartnerDirectory} is the bridge between the partner BUSINESS CODE the Portal
 * carries (the token's {@code partner_id} claim is a seeded code like {@code "GMEREMIT"}) and the
 * NUMERIC surrogate that auth-identity, notification-webhook and transaction-mgmt all key on.
 *
 * <p>Its security property is the important one: an unresolvable code must yield EMPTY so callers
 * fail closed, never widen a partner-scoped query into a cross-partner one.
 */
class PartnerDirectoryTest {

    /** Registry fake that counts lookups so caching can be asserted. */
    private static final class CountingRegistry implements ConfigRegistryClient {
        private final java.util.Map<String, Long> ids;
        final List<String> lookups = new ArrayList<>();

        CountingRegistry(java.util.Map<String, Long> ids) {
            this.ids = ids;
        }

        @Override
        public PartnerSummary getPartner(String partnerId) {
            return null;
        }

        @Override
        public List<PartnerSummary> listPartners() {
            return List.of();
        }

        @Override
        public PartnerView getPartnerView(String partnerCode) {
            lookups.add(partnerCode);
            if (!ids.containsKey(partnerCode)) {
                return null;
            }
            return PartnerView.ofCore(ids.get(partnerCode), partnerCode, PartnerType.OVERSEAS,
                    "USD", RoundingMode.HALF_UP);
        }

        @Override
        public PartnerSummary createPartner(PartnerCreateRequest request) {
            return null;
        }

        @Override
        public PartnerSummary updateRoundingMode(String partnerId, String mode) {
            return null;
        }

        @Override
        public List<SchemeSummary> listSchemes() {
            return List.of();
        }
    }

    @Test
    @DisplayName("resolves a partner code to config-registry's numeric surrogate")
    void resolvesCodeToSurrogate() {
        PartnerDirectory directory = new PartnerDirectory(
                new CountingRegistry(java.util.Map.of("GMEREMIT", 4242L)));

        assertThat(directory.numericIdOf("GMEREMIT")).contains(4242L);
    }

    @Test
    @DisplayName("an already-numeric id passes through without touching config-registry")
    void numericIdNeedsNoLookup() {
        CountingRegistry registry = new CountingRegistry(java.util.Map.of());
        PartnerDirectory directory = new PartnerDirectory(registry);

        assertThat(directory.numericIdOf("991")).contains(991L);
        assertThat(registry.lookups).isEmpty();
    }

    @Test
    @DisplayName("an unknown code resolves to EMPTY so callers fail closed")
    void unknownCodeIsEmpty() {
        PartnerDirectory directory = new PartnerDirectory(
                new CountingRegistry(java.util.Map.of("GMEREMIT", 1L)));

        assertThat(directory.numericIdOf("NOPE")).isEmpty();
    }

    @Test
    @DisplayName("a blank or null code resolves to EMPTY without a lookup")
    void blankCodeIsEmpty() {
        CountingRegistry registry = new CountingRegistry(java.util.Map.of());
        PartnerDirectory directory = new PartnerDirectory(registry);

        assertThat(directory.numericIdOf(null)).isEmpty();
        assertThat(directory.numericIdOf("")).isEmpty();
        assertThat(directory.numericIdOf("   ")).isEmpty();
        assertThat(registry.lookups).isEmpty();
    }

    @Test
    @DisplayName("a registry row with a null surrogate id resolves to EMPTY, not to null")
    void nullSurrogateIsEmpty() {
        ConfigRegistryClient registry = new ConfigRegistryClient() {
            @Override
            public PartnerSummary getPartner(String partnerId) {
                return null;
            }

            @Override
            public List<PartnerSummary> listPartners() {
                return List.of();
            }

            @Override
            public PartnerView getPartnerView(String partnerCode) {
                // A row exists but carries no surrogate (e.g. an unflushed insert) — unusable as a
                // join key, so it must not be forwarded to a partner-scoped upstream query.
                return PartnerView.ofCore(null, partnerCode, PartnerType.OVERSEAS, "USD",
                        RoundingMode.HALF_UP);
            }

            @Override
            public PartnerSummary createPartner(PartnerCreateRequest request) {
                return null;
            }

            @Override
            public PartnerSummary updateRoundingMode(String partnerId, String mode) {
                return null;
            }

            @Override
            public List<SchemeSummary> listSchemes() {
                return List.of();
            }
        };

        assertThat(new PartnerDirectory(registry).numericIdOf("GMEREMIT")).isEmpty();
    }

    @Test
    @DisplayName("a successful resolution is cached — the code -> id mapping is immutable")
    void successfulResolutionIsCached() {
        CountingRegistry registry = new CountingRegistry(java.util.Map.of("GMEREMIT", 7L));
        PartnerDirectory directory = new PartnerDirectory(registry);

        for (int i = 0; i < 5; i++) {
            assertThat(directory.numericIdOf("GMEREMIT")).contains(7L);
        }
        assertThat(registry.lookups).containsExactly("GMEREMIT");
    }

    @Test
    @DisplayName("a FAILED resolution is throttled but not cached forever")
    void failedResolutionIsThrottledNotPermanent() {
        CountingRegistry registry = new CountingRegistry(java.util.Map.of());
        PartnerDirectory directory = new PartnerDirectory(registry);

        // A hot loop of misses must not hammer config-registry...
        for (int i = 0; i < 5; i++) {
            assertThat(directory.numericIdOf("LATER")).isEmpty();
        }
        assertThat(registry.lookups).hasSize(1);

        // ...but the negative entry must be discardable, so a partner activated moments ago is
        // picked up without restarting the BFF.
        assertThat(PartnerDirectory.NEGATIVE_TTL).isPositive();
        directory.invalidate();
        assertThat(directory.numericIdOf("LATER")).isEmpty();
        assertThat(registry.lookups).hasSize(2);
    }

    @Test
    @DisplayName("viewOf returns the full row and warms the id cache")
    void viewOfReturnsRowAndWarmsCache() {
        CountingRegistry registry = new CountingRegistry(java.util.Map.of("SENDMN", 88L));
        PartnerDirectory directory = new PartnerDirectory(registry);

        PartnerView view = directory.viewOf("SENDMN");
        assertThat(view).isNotNull();
        assertThat(view.id()).isEqualTo(88L);

        // The id read is now free.
        assertThat(directory.numericIdOf("SENDMN")).contains(88L);
        assertThat(registry.lookups).containsExactly("SENDMN");
    }

    @Test
    @DisplayName("codes are trimmed so a padded path segment still resolves")
    void codesAreTrimmed() {
        PartnerDirectory directory = new PartnerDirectory(
                new CountingRegistry(java.util.Map.of("GMEREMIT", 5L)));

        assertThat(directory.numericIdOf("  GMEREMIT  ")).contains(5L);
    }
}
