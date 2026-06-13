package com.gme.pay.registry.credential;

import com.gme.pay.contracts.PartnerIpAllowlistCommand;
import com.gme.pay.contracts.PartnerIpAllowlistView;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 8 Lane B — owns the {@code partner_ip_allowlist} child aggregate
 * (V026) behind the wizard's step-8 ip-allowlist endpoints.
 *
 * <h2>Bulk-replace semantics</h2>
 *
 * <p>The wizard's contract is "send the full allowlist on every save", so a
 * PATCH is a <b>bulk replace</b>: inside one transaction every existing
 * {@code partner_ip_allowlist} row of the partner is DELETEd and the new set
 * INSERTed (the table is non-bitemporal — its history is the ADR-007 audit
 * trail, see the V026 header). Sending an empty list clears the allowlist;
 * {@code null} is a 400.
 *
 * <h2>Invariants</h2>
 *
 * <ul>
 *   <li>CIDR shape (service-enforced — the V026 CHECK pins only the trivial
 *       shape): IPv4 {@code a.b.c.d/0..32} with in-range octets, or IPv6
 *       hex-groups {@code /0..128} (one optional {@code ::} elision). The
 *       explicit {@code /prefix} is mandatory — a bare address is a 400 (the
 *       UI canonicalises host entries to {@code /32} / {@code /128}).</li>
 *   <li>Hard ceiling: 10 CIDRs per (partner, environment) — a payload
 *       exceeding it is a <b>409 CIDR_LIMIT_EXCEEDED</b> per the Slice 8
 *       contract.</li>
 *   <li>Duplicate (environment, cidr) pairs in the payload are a 400 (the
 *       V026 UNIQUE constraint is the storage-level backstop).</li>
 * </ul>
 *
 * <h2>Audit (ADR-007)</h2>
 *
 * <p>One audit row per write, {@code aggregateType="partner_ip_allowlist"},
 * keyed by the partner business code, BEFORE/AFTER = {@link CredentialJson}
 * canonical snapshots, published inside the same transaction through the
 * {@link ObjectProvider}-resolved {@link AuditLogService} — the same wiring
 * contract as {@code RuleService}.
 */
@Service
public class PartnerIpAllowlistService {

    /** Aggregate-type discriminator on audit rows for allowlist mutations. */
    public static final String AGGREGATE_TYPE = "partner_ip_allowlist";

    /** Audit verb for the step-8 bulk replace. */
    public static final String EVENT_TYPE_REPLACED = "PARTNER_IP_ALLOWLIST_REPLACED";

    /** Slice 8 contract: hard ceiling of CIDRs per (partner, environment). */
    public static final int MAX_CIDRS_PER_ENVIRONMENT = 10;

    /** Machine-readable 409 discriminator required by the Slice 8 contract. */
    public static final String CIDR_LIMIT_EXCEEDED = "CIDR_LIMIT_EXCEEDED";

    /** V026 CHECK roster for environment. */
    static final Set<String> ENVIRONMENTS = Set.of("SANDBOX", "PRODUCTION");

    /** Default actor until the Keycloak {@code sub} claim is threaded through. */
    private static final String DEFAULT_ACTOR = "system";

    private final PartnerIpAllowlistRepository allowlistRepository;
    private final PartnerRepository partnerRepository;
    private final ObjectProvider<AuditLogService> auditLogProvider;

    public PartnerIpAllowlistService(PartnerIpAllowlistRepository allowlistRepository,
                                     PartnerRepository partnerRepository,
                                     ObjectProvider<AuditLogService> auditLogProvider) {
        this.allowlistRepository = allowlistRepository;
        this.partnerRepository = partnerRepository;
        this.auditLogProvider = auditLogProvider;
    }

    /**
     * Bulk-replace the IP allowlist of a draft partner (wizard step-8 save).
     *
     * @param partnerCode the human-facing business code routing the PATCH.
     * @param entries     the FULL desired set across both environments; empty
     *                    clears, {@code null} is a 400.
     * @param actor       the operator (X-Actor header); {@code "system"} when absent.
     * @return the freshly-inserted set as canonical {@link PartnerIpAllowlistView}s.
     * @throws ResponseStatusException 404 when no current partner row matches;
     *         409 when the partner is no longer in ONBOARDING, or with
     *         {@code CIDR_LIMIT_EXCEEDED} when an environment exceeds the
     *         10-CIDR ceiling; 400 on shape/duplicate validation failure with
     *         the offending {@code ipAllowlist[i]} index in the message.
     */
    @Transactional
    public List<PartnerIpAllowlistView> replaceAllowlist(String partnerCode,
                                                         List<PartnerIpAllowlistCommand> entries,
                                                         String actor) {
        if (entries == null) {
            throw badRequest(
                    "ipAllowlist is required (send an empty list to clear the allowlist)");
        }
        PartnerEntity partner = requirePartner(partnerCode);
        if (partner.getStatus() != PartnerStatus.ONBOARDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "partner '" + partnerCode + "' is in status " + partner.getStatus()
                            + ", step-8 allowlist edits are only permitted while ONBOARDING"
                            + " (post-activation changes require the change_request"
                            + " approval flow)");
        }
        // Validate the WHOLE payload before touching any row (fail fast).
        Map<String, Integer> perEnvironment = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            PartnerIpAllowlistCommand cmd = validate(entries.get(i), i);
            if (!seen.add(cmd.environment() + "|" + cmd.cidr())) {
                throw badRequest("ipAllowlist[" + i + "]: duplicate CIDR " + cmd.cidr()
                        + " for environment " + cmd.environment());
            }
            perEnvironment.merge(cmd.environment(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> count : perEnvironment.entrySet()) {
            if (count.getValue() > MAX_CIDRS_PER_ENVIRONMENT) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        CIDR_LIMIT_EXCEEDED + ": environment " + count.getKey() + " carries "
                                + count.getValue() + " CIDRs, the ceiling is "
                                + MAX_CIDRS_PER_ENVIRONMENT + " per (partner, environment)");
            }
        }

        List<PartnerIpAllowlistEntity> prior =
                allowlistRepository.findByPartnerIdOrderByEnvironmentAscCidrAsc(partner.getId());
        byte[] before = prior.isEmpty() ? null : CredentialJson.allowlist(prior);

        // Non-bitemporal bulk replace: DELETE the whole set, INSERT the new
        // one, one transaction. Flush the deletes out before the inserts so
        // the V026 UNIQUE (partner, environment, cidr) never sees old + new
        // rows for the same key mid-transaction.
        if (!prior.isEmpty()) {
            allowlistRepository.deleteAllInBatch(prior);
            allowlistRepository.flush();
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String stampedActor = actor == null || actor.isBlank() ? DEFAULT_ACTOR : actor;
        List<PartnerIpAllowlistEntity> fresh = new ArrayList<>(entries.size());
        for (PartnerIpAllowlistCommand cmd : entries) {
            PartnerIpAllowlistEntity e = new PartnerIpAllowlistEntity();
            e.setPartnerId(partner.getId());
            e.setCidr(cmd.cidr().trim());
            e.setLabel(cmd.label());
            e.setEnvironment(cmd.environment());
            e.setCreatedAt(now);
            e.setCreatedBy(stampedActor);
            fresh.add(e);
        }
        // saveAllAndFlush: IDENTITY ids are assigned at flush; the RETURNED
        // managed entities carry them for the audit AFTER snapshot + views.
        List<PartnerIpAllowlistEntity> saved = allowlistRepository.saveAllAndFlush(fresh);

        publishAudit(partnerCode, stampedActor, before, CredentialJson.allowlist(saved));

        return saved.stream().map(PartnerIpAllowlistEntity::toView).toList();
    }

    /** The allowlist of the given partner across both environments. */
    @Transactional(readOnly = true)
    public List<PartnerIpAllowlistView> currentAllowlist(String partnerCode) {
        PartnerEntity partner = requirePartner(partnerCode);
        return allowlistRepository
                .findByPartnerIdOrderByEnvironmentAscCidrAsc(partner.getId()).stream()
                .map(PartnerIpAllowlistEntity::toView)
                .toList();
    }

    // -------------------------- Validation -----------------------------------

    /** Field-format validation for one entry; index-qualified 400 messages. */
    private static PartnerIpAllowlistCommand validate(PartnerIpAllowlistCommand cmd, int index) {
        String at = "ipAllowlist[" + index + "]";
        if (cmd == null) {
            throw badRequest(at + " must be an object");
        }
        if (cmd.environment() == null || !ENVIRONMENTS.contains(cmd.environment())) {
            throw badRequest(at + ".environment must be one of " + ENVIRONMENTS
                    + ", was: " + cmd.environment());
        }
        if (cmd.label() != null && cmd.label().length() > 120) {
            throw badRequest(at + ".label must be at most 120 characters");
        }
        if (cmd.cidr() == null || cmd.cidr().isBlank()) {
            throw badRequest(at + ".cidr is required (CIDR notation, e.g. 203.0.113.0/24)");
        }
        String cidr = cmd.cidr().trim();
        if (cidr.length() > 43) {
            throw badRequest(at + ".cidr must be at most 43 characters (V026)");
        }
        String error = cidrShapeError(cidr);
        if (error != null) {
            throw badRequest(at + ".cidr " + error + ", was: " + cidr);
        }
        return cmd;
    }

    /**
     * Validates CIDR notation without any DNS resolution (deliberately NOT
     * {@code InetAddress.getByName}, which falls back to a resolver lookup
     * for malformed literals). IPv4: four in-range octets + prefix 0..32.
     * IPv6: 2..8 hex groups with at most one {@code ::} elision + prefix
     * 0..128 (no embedded-IPv4 mixed notation — canonicalise upstream).
     *
     * @return {@code null} when valid, otherwise a human-readable reason.
     */
    static String cidrShapeError(String cidr) {
        int slash = cidr.indexOf('/');
        if (slash <= 0 || slash != cidr.lastIndexOf('/') || slash == cidr.length() - 1) {
            return "must carry exactly one /prefix (e.g. 203.0.113.0/24)";
        }
        String address = cidr.substring(0, slash);
        int prefix;
        try {
            prefix = Integer.parseInt(cidr.substring(slash + 1));
        } catch (NumberFormatException notANumber) {
            return "prefix must be numeric";
        }
        if (address.indexOf(':') >= 0) {
            String v6 = ipv6Error(address);
            if (v6 != null) {
                return v6;
            }
            if (prefix < 0 || prefix > 128) {
                return "IPv6 prefix must be within 0..128";
            }
            return null;
        }
        String v4 = ipv4Error(address);
        if (v4 != null) {
            return v4;
        }
        if (prefix < 0 || prefix > 32) {
            return "IPv4 prefix must be within 0..32";
        }
        return null;
    }

    private static String ipv4Error(String address) {
        String[] octets = address.split("\\.", -1);
        if (octets.length != 4) {
            return "IPv4 address must have exactly 4 octets";
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3
                    || !octet.chars().allMatch(Character::isDigit)) {
                return "IPv4 octets must be 1-3 digits";
            }
            int value = Integer.parseInt(octet);
            if (value > 255) {
                return "IPv4 octets must be within 0..255";
            }
        }
        return null;
    }

    private static String ipv6Error(String address) {
        if (address.contains(".")) {
            return "mixed IPv6/IPv4 notation is not supported (canonicalise to hex groups)";
        }
        int elisions = countOccurrences(address);
        if (elisions > 1) {
            return "IPv6 address may carry at most one '::'";
        }
        boolean elided = elisions == 1;
        // Split halves around the elision (either side may be empty: "::1", "fe80::").
        String[] halves = elided ? address.split("::", -1) : new String[] {address};
        int groups = 0;
        for (String half : halves) {
            if (half.isEmpty()) {
                continue;
            }
            for (String group : half.split(":", -1)) {
                if (group.isEmpty() || group.length() > 4 || !isHex(group)) {
                    return "IPv6 groups must be 1-4 hex digits";
                }
                groups++;
            }
        }
        if (!elided && groups != 8) {
            return "IPv6 address must have 8 groups (or use '::')";
        }
        if (elided && groups > 7) {
            return "IPv6 address with '::' must have at most 7 explicit groups";
        }
        return null;
    }

    private static int countOccurrences(String s) {
        int count = 0;
        for (int i = 0; i + 1 < s.length(); i++) {
            if (s.charAt(i) == ':' && s.charAt(i + 1) == ':') {
                count++;
                i++; // do not double-count ":::"
            }
        }
        return count;
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    // -------------------------- Helpers --------------------------------------

    private PartnerEntity requirePartner(String partnerCode) {
        return partnerRepository.findCurrentByPartnerCode(partnerCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no partner '" + partnerCode + "'"));
    }

    /** ADR-007 audit row, same-transaction (commits iff the business write commits). */
    private void publishAudit(String partnerCode, String actor, byte[] before, byte[] after) {
        AuditLogService auditLog = auditLogProvider.getIfAvailable();
        if (auditLog != null) {
            auditLog.publish(AGGREGATE_TYPE, partnerCode, actor, null,
                    EVENT_TYPE_REPLACED, before, after);
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
