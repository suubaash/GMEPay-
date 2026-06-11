package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.PartnerView;
import com.gme.pay.domain.PartnerType;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase-1 in-memory stub of {@link ConfigRegistryClient}. Lets the BFF boot and be
 * exercised end-to-end without booting config-registry. A future
 * {@code RestConfigRegistryClient} marked {@code @Primary} will take over without
 * removing this bean.
 *
 * <p>The seed dataset matches the partners used by other services' tests so the
 * Admin UI shows consistent IDs across the stack during local development. The
 * store is mutable so the Admin UI partner-form happy path can round-trip a
 * create or rounding-mode update without booting config-registry.
 */
/**
 * Default unless {@code gmepay.config-registry.client=rest} (then
 * {@link com.gme.pay.bff.client.rest.RestConfigRegistryClient} wins). Keeping
 * the stub on the classpath lets the BFF and its unit slices boot without
 * config-registry being up.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "gmepay.config-registry.client",
        havingValue = "stub",
        matchIfMissing = true)
public class StubConfigRegistryClient implements ConfigRegistryClient {

    private final Map<String, PartnerSummary> store = new LinkedHashMap<>();
    private final List<SchemeSummary> schemes;
    /** Slice 1 draft view — keyed by partner_code, mirrors what config-registry returns. */
    private final Map<String, PartnerView> draftStore = new LinkedHashMap<>();
    /** Stand-in for {@code partners_id_seq} so the stub-issued surrogate ids look real. */
    private final AtomicLong surrogateSeq = new AtomicLong(900_000L);

    public StubConfigRegistryClient() {
        store.put("partner_test_001", new PartnerSummary(
                "partner_test_001", "OVERSEAS", "USD", RoundingMode.HALF_UP));
        store.put("partner_test_002", new PartnerSummary(
                "partner_test_002", "LOCAL", "KRW", RoundingMode.DOWN));
        store.put("partner_test_003", new PartnerSummary(
                "partner_test_003", "OVERSEAS", "JPY", RoundingMode.HALF_EVEN));
        schemes = List.of(
                new SchemeSummary("zeropay_kr", "ZeroPay KR", "KR", "KRW", "DOMESTIC", "ACTIVE"),
                new SchemeSummary("paynow_sg",  "PayNow SG",  "SG", "SGD", "OVERSEAS", "ACTIVE"),
                new SchemeSummary("upi_in",     "UPI IN",     "IN", "INR", "OVERSEAS", "PILOT"));
    }

    @Override
    public PartnerSummary getPartner(String partnerId) {
        return store.get(partnerId);
    }

    @Override
    public List<PartnerSummary> listPartners() {
        return List.copyOf(store.values());
    }

    @Override
    public synchronized PartnerSummary createPartner(PartnerCreateRequest request) {
        RoundingMode mode = parseMode(request.settlementRoundingMode());
        PartnerSummary created = new PartnerSummary(
                request.partnerId(), request.type(), request.settlementCurrency(), mode);
        store.put(created.partnerId(), created);
        return created;
    }

    @Override
    public synchronized PartnerSummary updateRoundingMode(String partnerId, String mode) {
        PartnerSummary existing = store.get(partnerId);
        if (existing == null) {
            return null;
        }
        PartnerSummary updated = new PartnerSummary(
                existing.partnerId(),
                existing.type(),
                existing.settlementCurrency(),
                parseMode(mode));
        store.put(partnerId, updated);
        return updated;
    }

    @Override
    public List<SchemeSummary> listSchemes() {
        return new ArrayList<>(schemes);
    }

    // -------- Slice 1 (1C.2) draft endpoints (ADR-012) -----------------------

    @Override
    public synchronized PartnerView createDraft(PartnerCommand.CreateDraft request) {
        if (request == null || request.partnerCode() == null || request.partnerCode().isBlank()) {
            throw new IllegalArgumentException("partnerCode is required");
        }
        if (draftStore.containsKey(request.partnerCode())
                || store.containsKey(request.partnerCode())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "partner '" + request.partnerCode() + "' already exists");
        }
        PartnerView view = buildView(surrogateSeq.getAndIncrement(), request);
        draftStore.put(request.partnerCode(), view);
        return view;
    }

    @Override
    public synchronized PartnerView patchDraftStep1(String partnerCode,
                                                    PartnerCommand.UpdateStep1 request) {
        PartnerView prior = draftStore.get(partnerCode);
        if (prior == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no partner '" + partnerCode + "'");
        }
        PartnerView merged = mergeStep1(prior, request);
        draftStore.put(partnerCode, merged);
        return merged;
    }

    @Override
    public synchronized PartnerView getDraft(String partnerCode) {
        return draftStore.get(partnerCode);
    }

    @Override
    public synchronized List<PartnerView> listDrafts() {
        return new ArrayList<>(draftStore.values());
    }

    private static PartnerView buildView(Long id, PartnerCommand.CreateDraft req) {
        return new PartnerView(
                id,
                req.partnerCode(),
                req.type(),
                req.settlementCurrency(),
                req.settlementRoundingMode() == null ? RoundingMode.HALF_UP : req.settlementRoundingMode(),
                req.legalNameLocal(),
                req.legalNameRomanized(),
                req.taxId(),
                req.taxIdType(),
                req.countryOfIncorporation(),
                req.legalForm(),
                req.registeredAddress() == null ? null : req.registeredAddress().toView(),
                req.operatingAddress() == null ? null : req.operatingAddress().toView(),
                req.lei(),
                PartnerStatus.ONBOARDING,
                Instant.EPOCH,
                null,
                Instant.now());
    }

    /** Apply non-null Step-1 fields from the request onto the prior view, returning a new PartnerView. */
    private static PartnerView mergeStep1(PartnerView prior, PartnerCommand.UpdateStep1 req) {
        PartnerType type = req.type() != null ? req.type() : prior.type();
        String settlementCurrency = req.settlementCurrency() != null
                ? req.settlementCurrency() : prior.settlementCurrency();
        RoundingMode roundingMode = req.settlementRoundingMode() != null
                ? req.settlementRoundingMode() : prior.settlementRoundingMode();
        return new PartnerView(
                prior.id(),
                prior.partnerCode(),
                type,
                settlementCurrency,
                roundingMode,
                req.legalNameLocal() != null ? req.legalNameLocal() : prior.legalNameLocal(),
                req.legalNameRomanized() != null ? req.legalNameRomanized() : prior.legalNameRomanized(),
                req.taxId() != null ? req.taxId() : prior.taxId(),
                req.taxIdType() != null ? req.taxIdType() : prior.taxIdType(),
                req.countryOfIncorporation() != null ? req.countryOfIncorporation() : prior.countryOfIncorporation(),
                req.legalForm() != null ? req.legalForm() : prior.legalForm(),
                req.registeredAddress() != null ? req.registeredAddress().toView() : prior.registeredAddress(),
                req.operatingAddress() != null ? req.operatingAddress().toView() : prior.operatingAddress(),
                req.lei() != null ? req.lei() : prior.lei(),
                PartnerStatus.ONBOARDING,
                prior.validFrom(),
                prior.validTo(),
                Instant.now());
    }

    private static RoundingMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return RoundingMode.HALF_UP;
        }
        return RoundingMode.valueOf(raw);
    }
}
