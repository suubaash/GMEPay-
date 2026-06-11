package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.AuditClient;
import com.gme.pay.bff.web.dto.Page;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase-1 in-memory stub of {@link AuditClient}. Returns 25 deterministic
 * audit entries over the past 30 days mixing the canonical operator actions
 * ({@code partner.create}, {@code rounding-mode.update}, {@code
 * partner.suspend}, {@code scheme.enable}, {@code login.success}) so the
 * Admin UI Audit page renders a realistic timeline.
 *
 * <p>Entries are seeded newest-first and paginated in memory.
 */
@Component
public class StubAuditClient implements AuditClient {

    /** Total seeded entries (matches the spec: "25 fake entries"). */
    static final int TOTAL = 25;

    /** Fixed "now" so the seeded timestamps are deterministic across runs. */
    private static final Instant NOW = Instant.parse("2026-06-10T12:00:00Z");

    /** Round-robin actor pool. */
    private static final String[] ACTORS = {
            "ops.admin@gmepay.com",
            "ops.compliance@gmepay.com",
            "ops.finance@gmepay.com",
            "system"
    };

    /** Round-robin action pool. */
    private static final String[] ACTIONS = {
            "partner.create",
            "rounding-mode.update",
            "partner.suspend",
            "scheme.enable",
            "login.success"
    };

    private final List<AuditEntry> entries;

    public StubAuditClient() {
        List<AuditEntry> seed = new ArrayList<>(TOTAL);
        for (int i = 0; i < TOTAL; i++) {
            String id = String.format("AUD-%04d", TOTAL - i); // newest first
            String actor = ACTORS[i % ACTORS.length];
            String action = ACTIONS[i % ACTIONS.length];
            String target = switch (action) {
                case "partner.create", "partner.suspend", "rounding-mode.update"
                        -> "partner_test_" + String.format("%03d", (i % 3) + 1);
                case "scheme.enable" -> "zeropay_kr";
                case "login.success" -> ACTORS[i % ACTORS.length];
                default -> "-";
            };
            // Spread entries across the past 30 days (≈ 28.8 hours apart).
            Instant at = NOW.minus((long) i * 28L, ChronoUnit.HOURS)
                    .minus((long) (i * 48L) % 60L, ChronoUnit.MINUTES);
            String detail = action + " on " + target + " by " + actor;
            seed.add(new AuditEntry(id, actor, action, target, at, detail));
        }
        this.entries = List.copyOf(seed);
    }

    @Override
    public Page<AuditEntry> list(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = size <= 0 ? 20 : size;
        int fromIndex = Math.min(safePage * safeSize, entries.size());
        int toIndex = Math.min(fromIndex + safeSize, entries.size());
        List<AuditEntry> slice = entries.subList(fromIndex, toIndex);
        return new Page<>(slice, safePage, safeSize, entries.size());
    }
}
