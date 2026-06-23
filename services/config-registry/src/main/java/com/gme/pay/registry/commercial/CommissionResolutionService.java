package com.gme.pay.registry.commercial;

import com.gme.pay.contracts.EffectiveCommissionView;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import com.gme.pay.registry.scheme.SchemeCommissionShareEntity;
import com.gme.pay.registry.scheme.SchemeCommissionShareRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the EFFECTIVE commission split for a concrete (scheme × partner ×
 * direction) by combining the two configurable sides (V031) under a documented
 * most-specific-wins precedence. The single source of truth for the wildcard
 * precedence — settlement / revenue-ledger / payment-executor call this rather
 * than re-implementing it (the gap the UI-review flagged).
 *
 * <p>Lenient by design: an unknown partner or a side with no configured rows
 * yields a {@code null} share on that side (not a 404), so a caller can still
 * use whichever side resolved. {@link EffectiveCommissionView#resolved()} is
 * true only when BOTH sides resolved.
 *
 * @see EffectiveCommissionView for the precedence definition.
 */
@Service
public class CommissionResolutionService {

    private final SchemeCommissionShareRepository schemeRepo;
    private final PartnerCommissionShareRepository partnerRepo;
    private final PartnerRepository partnerRepository;

    public CommissionResolutionService(SchemeCommissionShareRepository schemeRepo,
                                       PartnerCommissionShareRepository partnerRepo,
                                       PartnerRepository partnerRepository) {
        this.schemeRepo = schemeRepo;
        this.partnerRepo = partnerRepo;
        this.partnerRepository = partnerRepository;
    }

    /**
     * Resolve the effective split. {@code direction} may be null/blank (matches
     * only the wildcard rows on each side).
     */
    @Transactional(readOnly = true)
    public EffectiveCommissionView resolve(String schemeId, String partnerCode, String direction) {
        String dir = blankToNull(direction);

        // --- Scheme side: exact-direction row wins over the direction=NULL wildcard. ---
        SchemeCommissionShareEntity scheme = pickScheme(
                schemeId == null ? List.of() : schemeRepo.findCurrentBySchemeId(schemeId), dir);

        // --- Partner side: most-specific applicable row wins. ---
        PartnerCommissionShareEntity partner = null;
        Optional<PartnerEntity> partnerEntity = partnerCode == null
                ? Optional.empty()
                : partnerRepository.findCurrentByPartnerCode(partnerCode);
        if (partnerEntity.isPresent()) {
            partner = pickPartner(
                    partnerRepo.findCurrentByPartnerId(partnerEntity.get().getId()), schemeId, dir);
        }

        boolean resolved = scheme != null && partner != null;
        return new EffectiveCommissionView(
                schemeId,
                partnerCode,
                dir,
                scheme == null ? null : scheme.getGmeSharePct(),
                scheme == null ? null : scheme.getVanFeePct(),
                partner == null ? null : partner.getPartnerSharePct(),
                resolved,
                scheme == null ? "none"
                        : schemeId + ":" + (scheme.getDirection() == null ? "*" : scheme.getDirection()),
                partner == null ? "none"
                        : partnerCode + ":" + (partner.getSchemeId() == null ? "*" : partner.getSchemeId())
                                + ":" + (partner.getDirection() == null ? "*" : partner.getDirection()));
    }

    /** Exact-direction match beats the direction=NULL wildcard; null if neither. */
    private static SchemeCommissionShareEntity pickScheme(
            List<SchemeCommissionShareEntity> rows, String dir) {
        SchemeCommissionShareEntity wildcard = null;
        for (SchemeCommissionShareEntity r : rows) {
            if (r.getDirection() == null) {
                wildcard = r;
            } else if (r.getDirection().equals(dir)) {
                return r; // exact direction — most specific, done
            }
        }
        return wildcard;
    }

    /**
     * Most-specific applicable partner row. A row applies when its non-null
     * schemeId/direction match the query; specificity = (schemeId ? 2 : 0) +
     * (direction ? 1 : 0). Per-key dedup (bulk-replace) means scores are
     * distinct, so the max is unambiguous.
     */
    private static PartnerCommissionShareEntity pickPartner(
            List<PartnerCommissionShareEntity> rows, String schemeId, String dir) {
        PartnerCommissionShareEntity best = null;
        int bestScore = -1;
        for (PartnerCommissionShareEntity r : rows) {
            if (r.getSchemeId() != null && !r.getSchemeId().equals(schemeId)) {
                continue; // scheme-specific row that doesn't match the query
            }
            if (r.getDirection() != null && !r.getDirection().equals(dir)) {
                continue; // direction-specific row that doesn't match the query
            }
            int score = (r.getSchemeId() != null ? 2 : 0) + (r.getDirection() != null ? 1 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = r;
            }
        }
        return best;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
