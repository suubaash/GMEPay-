package com.gme.pay.registry.partner;

import com.gme.pay.domain.Partner;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.cache.ConfigCache;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Registry-side accessor for {@link Partner}. Backed by {@link PartnerRepository}
 * (PostgreSQL in production, H2 in PostgreSQL mode for unit slices). Converts
 * between the JPA {@link PartnerEntity} and the immutable domain {@link Partner}
 * record at the persistence boundary, since records cannot be JPA entities.
 *
 * <h2>Bitemporal storage (V004 / ADR-010)</h2>
 *
 * <p>Every write is a paired (INSERT new current row) + (UPDATE prior current row
 * SET superseded_at = now()) inside one transaction — rows are never mutated in
 * place. The two operations share the same transaction-time instant {@code now},
 * captured once at the top of {@link #save}, so the prior row's
 * {@code superseded_at} and the new row's {@code recorded_at} line up exactly.
 * As-of reads ({@link #getAsOf}) can then ask "what was current at instant T?"
 * and get a single unambiguous row back.
 *
 * <h2>Identifier contract</h2>
 *
 * <p>All public methods take a {@code partnerCode} (the human-facing business
 * code, e.g. {@code "GMEREMIT"}). The BIGINT surrogate {@link Partner#partnerId()}
 * is the internal join key used by every consuming service ({@code PrincipalEntity},
 * {@code WebhookEndpointEntity}, etc.) but is never exposed on the URL line.
 *
 * <h2>Caching</h2>
 *
 * <p>{@link #get} reads cache-aside through {@link ConfigCache} (17.3-G03): try
 * Redis, fall through to the DB on a miss and write back with a TTL. {@link #save}
 * DELs the affected key after the DB writes, so readers observe updates well
 * within 1s. Without a Redis host configured the cache is a no-op and every call
 * is a plain DB access.
 *
 * <p>{@link #getAsOf} (and the deprecated {@link #getEffectiveAt}) intentionally
 * bypass the cache: they are audit/inspection queries keyed by an arbitrary
 * instant tuple, not the hot path.
 */
@Component
public class PartnerStore {

    private final PartnerRepository repository;
    private final ConfigCache cache;
    private final org.springframework.beans.factory.ObjectProvider<AuditLogService> auditLogProvider;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Production wiring. {@link AuditLogService} is injected via an
     * {@link org.springframework.beans.factory.ObjectProvider} so the existing
     * {@code @DataJpaTest} slice tests — which import {@link PartnerStore} without
     * the audit module on their {@code @Import} list — continue to compile and
     * pass: the provider returns {@code null} when no audit bean is in the
     * context, and {@link #save} treats {@code null} as "no audit pipeline wired,
     * skip publishing". Spring autoconfigures the provider regardless of whether
     * a matching bean exists, so no extra ceremony is needed at the bean-definition
     * site.
     *
     * <p>Marked {@link org.springframework.beans.factory.annotation.Autowired @Autowired}
     * explicitly so Spring picks this constructor over the two-arg back-compat
     * overload (the two-arg form has no {@code ObjectProvider} parameter, so the
     * candidate selection would otherwise be ambiguous).
     */
    @org.springframework.beans.factory.annotation.Autowired
    public PartnerStore(PartnerRepository repository, ConfigCache cache,
                        org.springframework.beans.factory.ObjectProvider<AuditLogService> auditLogProvider) {
        this.repository = repository;
        this.cache = cache;
        this.auditLogProvider = auditLogProvider;
    }

    /**
     * Back-compat overload for {@code PartnerStoreTest}: the existing test
     * constructs {@code new PartnerStore(repository, cache)} directly (no Spring
     * container, no DI). Routing through this overload sets {@link #auditLogProvider}
     * to an always-empty provider so {@link #save} skips publication, exactly the
     * pre-audit behaviour those tests exercise.
     */
    public PartnerStore(PartnerRepository repository, ConfigCache cache) {
        this(repository, cache, emptyAuditProvider());
    }

    /**
     * A trivial {@link org.springframework.beans.factory.ObjectProvider} that
     * always reports "no bean", used by the two-arg test constructor.
     */
    private static org.springframework.beans.factory.ObjectProvider<AuditLogService> emptyAuditProvider() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public AuditLogService getObject(Object... args) {
                throw new org.springframework.beans.factory.NoSuchBeanDefinitionException(AuditLogService.class);
            }

            @Override
            public AuditLogService getObject() {
                throw new org.springframework.beans.factory.NoSuchBeanDefinitionException(AuditLogService.class);
            }

            @Override
            public AuditLogService getIfAvailable() {
                return null;
            }

            @Override
            public AuditLogService getIfUnique() {
                return null;
            }
        };
    }

    /**
     * Persist the given partner as the new current row, superseding any prior
     * current row for the same {@code partnerCode}. Implemented per ADR-010 as a
     * paired (UPDATE prior SET superseded_at) + (INSERT new) inside a single
     * transaction; rows are never modified in place.
     *
     * <p>The new row inherits its {@code valid_from} from the prior row's
     * {@code valid_from} (default policy: business-time window stays open from
     * the original effective date until further notice). Callers wanting a fresh
     * business-time bound on the new row should reach for an enriched API; for
     * Slice 1 the implicit "effective since the prior row's start" matches the
     * pre-bitemporal semantics callers already had.
     */
    @Transactional
    public Partner save(Partner partner) {
        // One transaction-time instant shared by both halves of the paired write —
        // this keeps as-of reads unambiguous (the prior row's superseded_at == the
        // new row's recorded_at, so any T in (prior.recorded_at, new.recorded_at]
        // returns exactly one row). Truncated to MICROS so the stored TIMESTAMP
        // equals the in-memory value on both PostgreSQL and H2 — an un-truncated
        // nanosecond Instant gets ROUNDED by the database and can land later than
        // the value used in bitemporal predicates and audit hashes.
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        Optional<PartnerEntity> priorOpt = repository.findCurrentByPartnerCode(partner.partnerCode());

        PartnerEntity fresh = PartnerEntity.fromDomain(partner);
        // V003 surrogate: every new row gets a freshly-allocated BIGINT from the
        // V004-promoted-PK sequence. We pull explicitly at the application layer
        // (not via Hibernate @GeneratedValue) so the same path works against
        // PostgreSQL and H2 in PostgreSQL mode.
        fresh.setId(nextPartnerSurrogateId());
        fresh.setRecordedAt(now);

        // Capture the pre-write snapshot BEFORE we mutate the prior row, so the
        // BEFORE column in audit_log reflects what callers could observe a moment
        // ago — not what the in-memory entity looks like mid-supersede. A first
        // write (no prior current row) records `null` as BEFORE.
        Partner before = priorOpt.map(PartnerEntity::toDomain).orElse(null);

        if (priorOpt.isPresent()) {
            PartnerEntity prior = priorOpt.get();
            // Slice 8 post-activation immutability (ADR-011): once go_live_at is
            // stamped, the identity-critical attributes are frozen. The guard
            // throws ApiException(IMMUTABLE_AFTER_ACTIVATION) BEFORE any row is
            // touched, so a locked write leaves no half-superseded state.
            com.gme.pay.registry.lifecycle.PartnerImmutabilityGuard
                    .checkFourFieldWrite(prior, partner);
            // Carry forward the business-time start so the bitemporal timeline
            // for this partner_code stays continuous in business time even as
            // transaction-time changes accumulate. A future Slice 2+ payload that
            // wants to back-date a fact will set valid_from explicitly.
            fresh.setValidFrom(prior.getValidFrom());
            fresh.setValidTo(prior.getValidTo());

            // Slice 8 lifecycle carry-forward (V025): the domain Partner record
            // does not carry status or the lifecycle stamps, so a four-field
            // save must not reset a partner's FSM position (pre-V025 behaviour
            // silently re-defaulted status to ONBOARDING) nor erase go_live_at —
            // losing that stamp would silently disengage the immutability lock.
            fresh.setStatus(prior.getStatus());
            fresh.setGoLiveAt(prior.getGoLiveAt());
            fresh.setActivatedBy(prior.getActivatedBy());
            fresh.setSuspensionReason(prior.getSuspensionReason());
            fresh.setSuspensionNotes(prior.getSuspensionNotes());
            fresh.setSuspendedAt(prior.getSuspendedAt());
            fresh.setTerminatedAt(prior.getTerminatedAt());
            fresh.setTerminationReason(prior.getTerminationReason());

            // Slice 6 currency split (V016 Expand): the domain Partner record
            // does not carry collection_ccy / settle_a_ccy yet, so a save through
            // this four-field path must not silently erase a split the
            // commercial-terms step wrote. Carry the prior split forward when it
            // holds information beyond the legacy mirror (either side differs
            // from the prior settlement_currency); when the prior split was just
            // the mirror, leave both sides null so PartnerEntity.onPersist
            // re-mirrors the (possibly updated) settlement_currency.
            boolean priorSplitIsRealConfig =
                    (prior.getCollectionCcy() != null
                            && !prior.getCollectionCcy().equals(prior.getSettlementCurrency()))
                    || (prior.getSettleACcy() != null
                            && !prior.getSettleACcy().equals(prior.getSettlementCurrency()));
            if (priorSplitIsRealConfig) {
                fresh.setCollectionCcy(prior.getCollectionCcy());
                fresh.setSettleACcy(prior.getSettleACcy());
            }

            // Supersede the prior row: SCD-6 says we never overwrite content, only
            // close out the transaction-time interval. flush() forces this UPDATE
            // out before the new INSERT so the partial unique index partners_current
            // (one row per partner_code WHERE superseded_at IS NULL) is satisfied
            // throughout the transaction. Without the explicit flush Hibernate may
            // batch in either order and trip the index mid-write.
            prior.setSupersededAt(now);
            repository.saveAndFlush(prior);
        }

        PartnerEntity saved = repository.saveAndFlush(fresh);
        cache.evict(cacheKey(partner.partnerCode()));

        // ADR-007 audit row. Chained inside the same transaction so the audit row
        // commits iff the partner write commits — the regulator-defensible "if we
        // changed it, an audit row exists; if not, no dangling row" invariant.
        // The auditLog dependency is resolved through an ObjectProvider so slice
        // tests that omit the audit module from their @Import list skip publication
        // silently; production wiring always supplies the bean.
        AuditLogService auditLog = auditLogProvider.getIfAvailable();
        if (auditLog != null) {
            String actorId = currentActorId();
            String actorIp = currentActorIp();
            auditLog.publish(
                    "partner",
                    partner.partnerCode(),
                    actorId,
                    actorIp,
                    "PARTNER_SAVED",
                    before == null ? null : canonicalPartner(before),
                    canonicalPartner(saved.toDomain()));
        }

        return saved.toDomain();
    }

    /**
     * Resolve the actor (the operator who proposed/approved the write) from the
     * Slice 1 auth context. Until Slice 1B.4 wires Keycloak into config-registry
     * the only available principal is the BFF service account, so we record the
     * literal {@code "system"} which the 4-eyes CHECK constraint honours via the
     * ADR-008 carve-out. Slice 1B.4 replaces this with a Spring Security
     * {@code SecurityContextHolder} lookup.
     */
    private static String currentActorId() {
        return "system";
    }

    /**
     * Client IP at the BFF (PROXY-protocol-aware). The same Slice 1B.4 wire-up
     * fills this; for now we record {@code null} so the actor_ip column is
     * deliberately empty rather than carrying a placeholder.
     */
    private static String currentActorIp() {
        return null;
    }

    /**
     * Canonical byte representation of a partner row for the audit hash. Delegates
     * to {@code AuditLogService.canonicalPartnerJson} so the same canonicalisation
     * is reachable from tests and from any future verifier without re-implementing
     * the JSON shape.
     */
    private static byte[] canonicalPartner(Partner partner) {
        return AuditLogService.canonicalPartnerJson(
                partner.partnerId(),
                partner.partnerCode(),
                partner.type() == null ? null : partner.type().name(),
                partner.settlementCurrency(),
                partner.settlementRoundingMode() == null ? null : partner.settlementRoundingMode().name());
    }

    /**
     * Allocate the next BIGINT surrogate from {@code partners_id_seq} (V003). Used
     * by {@link #save} on every INSERT (which under SCD-6 is every write). The
     * sequence is queried via the EntityManager so the same SQL works against
     * PostgreSQL and H2 in PostgreSQL mode.
     */
    private Long nextPartnerSurrogateId() {
        Object value = entityManager
                .createNativeQuery("select nextval('partners_id_seq')")
                .getSingleResult();
        if (value instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalStateException(
                "partners_id_seq returned non-numeric value: " + value);
    }

    /**
     * Retrieve the current partner row for the given business code (cache-aside:
     * Redis first, then DB with write-back). Under SCD-6 "current" means the row
     * with {@code superseded_at IS NULL}.
     *
     * @throws ResponseStatusException with 404 when no current row matches.
     */
    public Partner get(String partnerCode) {
        String key = cacheKey(partnerCode);
        Optional<Partner> cached = cache.get(key, Partner.class);
        if (cached.isPresent()) {
            return cached.get();
        }
        PartnerEntity entity = repository.findCurrentByPartnerCode(partnerCode).orElse(null);
        if (entity == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown partner: " + partnerCode);
        }
        Partner partner = entity.toDomain();
        cache.put(key, partner);
        return partner;
    }

    /**
     * Bitemporal as-of read: return the row that was BOTH valid in business time
     * at {@code validAt} AND recorded as the current row in transaction time at
     * {@code recordedAt}. Bypasses the cache (audit-inspection workload, not
     * hot path).
     */
    public Partner getAsOf(String partnerCode, Instant validAt, Instant recordedAt) {
        return repository.findAsOf(partnerCode, validAt, recordedAt)
                .map(PartnerEntity::toDomain)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no partner '" + partnerCode + "' valid at " + validAt
                                + " as recorded at " + recordedAt));
    }

    /**
     * Back-compat shim for the pre-bitemporal {@code getEffectiveAt(partnerCode, at)}
     * call shape. Forwards to {@link #getAsOf} with the same instant used for both
     * business-time and transaction-time bounds — i.e. "what was true at time T
     * according to what we knew at time T". The dedicated as-of endpoint should
     * be preferred for new callers; this method exists so the {@code GET /v1/partners/{id}?at=}
     * controller path keeps the same observable semantics it had under V002.
     */
    public Partner getEffectiveAt(String partnerCode, Instant at) {
        return getAsOf(partnerCode, at, at);
    }

    /**
     * Update only the settlement rounding mode for the given partner; other fields
     * are preserved. The change is audit-relevant (see MONEY_CONVENTION.md). Under
     * SCD-6 this is an INSERT of a new current row, not an in-place UPDATE.
     */
    public Partner updateRoundingMode(String partnerCode, RoundingMode mode) {
        Partner current = get(partnerCode);
        Partner updated = new Partner(
                current.partnerId(),
                current.partnerCode(),
                current.type(),
                current.settlementCurrency(),
                mode);
        return save(updated);
    }

    /**
     * Snapshot of every currently-active partner. Routes through
     * {@link PartnerRepository#findAllCurrent} so historical rows are filtered
     * out at the DB layer rather than in Java. Bypasses the cache (low-frequency
     * operator view + per-row cache lookup would dwarf the savings).
     */
    public List<Partner> listAll() {
        return repository.findAllCurrent().stream().map(PartnerEntity::toDomain).toList();
    }

    /** Cache key for a partner's current view, keyed by the business code. */
    static String cacheKey(String partnerCode) {
        return "config:partner:" + partnerCode;
    }
}
