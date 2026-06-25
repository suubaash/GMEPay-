package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.web.dto.ComplianceRow;
import com.gme.pay.bff.web.dto.RegulatoryConfigSummary;
import com.gme.pay.contracts.KybView;
import com.gme.pay.contracts.PartnerView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * #77 Slice 3 — the admin /compliance regulatory-readiness board. Orchestrates per-partner config-registry
 * reads (no new downstream endpoints) into a {@link ComplianceRow} list: the partner set + name + lifecycle
 * status from {@link ConfigRegistryClient#listPartnerViews()}, the KYB sanctions-screening verdict from
 * {@code getKyb}, and the four regulatory "configured?" flags from {@code getRegulatory}.
 *
 * <p><b>Partial-failure isolation.</b> {@code getKyb}/{@code getRegulatory} THROW (404
 * {@link org.springframework.web.server.ResponseStatusException}) when a partner has no KYB / no step-8
 * regulatory save yet — and the stub client throws {@link UnsupportedOperationException}. Both are caught
 * per-partner and degrade that partner to {@code kybStatus=PENDING} / all-flags-false, so one unconfigured
 * partner never 500s the whole board.
 *
 * <p>Reflects only what was CONFIGURED, not that any real filing channel exists — the BOK/Hometax/KoFIU
 * submission lanes remain OI-02/OI-03 gated (gov file formats, SFTP endpoints, mTLS certs).
 */
@RestController
@RequestMapping("/v1/admin/compliance")
public class ComplianceOverviewController {

    private static final Logger log = LoggerFactory.getLogger(ComplianceOverviewController.class);

    private final ConfigRegistryClient configRegistry;

    public ComplianceOverviewController(ConfigRegistryClient configRegistry) {
        this.configRegistry = configRegistry;
    }

    /** Per-partner compliance readiness; empty array (200) when no partners exist. */
    @GetMapping("/overview")
    public List<ComplianceRow> overview() {
        List<PartnerView> partners = configRegistry.listPartnerViews();
        if (partners == null || partners.isEmpty()) {
            return List.of();
        }
        List<ComplianceRow> rows = new ArrayList<>(partners.size());
        for (PartnerView p : partners) {
            rows.add(toRow(p));
        }
        return rows;
    }

    private ComplianceRow toRow(PartnerView p) {
        String code = p.partnerCode();
        String lifecycle = p.status() == null ? null : p.status().name();

        // KYB sanctions screening — PENDING/null when no KYB row yet (404) or unsupported (stub).
        String screening = null;
        try {
            KybView kyb = configRegistry.getKyb(code);
            screening = kyb == null ? null : kyb.screeningStatus();
        } catch (Exception e) {
            log.debug("compliance overview: KYB unavailable for {} — defaulting PENDING ({})", code, e.toString());
        }

        // Regulatory config flags — all-false when no step-8 save yet (404) or unsupported (stub).
        RegulatoryConfigSummary reg = RegulatoryConfigSummary.NONE;
        try {
            reg = RegulatoryConfigSummary.from(configRegistry.getRegulatory(code));
        } catch (Exception e) {
            log.debug("compliance overview: regulatory config unavailable for {} — defaulting all-false ({})",
                    code, e.toString());
        }

        return new ComplianceRow(code, partnerName(p), kybStatus(screening), screening, reg, lifecycle);
    }

    private static String partnerName(PartnerView p) {
        if (p.legalNameRomanized() != null && !p.legalNameRomanized().isBlank()) {
            return p.legalNameRomanized();
        }
        if (p.legalNameLocal() != null && !p.legalNameLocal().isBlank()) {
            return p.legalNameLocal();
        }
        return p.partnerCode();
    }

    /** Map the KYB sanctions-screening verdict (CLEAR/NEEDS_REVIEW/HIT/null) to the UI kybStatus enum. */
    private static String kybStatus(String screeningStatus) {
        if (screeningStatus == null) {
            return "PENDING";
        }
        return switch (screeningStatus) {
            case "CLEAR" -> "APPROVED";
            case "NEEDS_REVIEW" -> "REVIEW";
            case "HIT" -> "HIT";
            default -> "PENDING";
        };
    }
}
