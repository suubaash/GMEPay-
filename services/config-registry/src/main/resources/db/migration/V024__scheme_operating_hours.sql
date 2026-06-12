-- V024: scheme_operating_hours reference table — Slice 7 "Scheme Enablement"
-- (docs/PARTNER_SETUP_PLAN.md §Slice 7). V023 is owned by Lane B
-- (partner_corridor); this file deliberately takes V024.
--
-- WHY
-- ---
-- Each payment scheme has a weekly operating schedule (when the rail accepts
-- traffic) and an optional daily settlement cutoff. The router and the
-- settlement calculator need this to decide "can this transaction route NOW?"
-- and "which value date does it book to?". One row per (scheme, weekday).
--
-- WHY NOT BITEMPORAL
-- ------------------
-- This is seeded REFERENCE data, not operator-mutable partner config: no
-- wizard writes it, no API mutates it, changes arrive as new migrations. So
-- no SCD-6 columns (same class of table as business_day_calendar, V014); the
-- JPA entity is @Immutable and there is no write endpoint.
--
-- TIME SEMANTICS
-- --------------
-- open/close/cutoff are wall-clock times-of-day evaluated in `timezone` (an
-- IANA zone id) — TIME + zone-id-string instead of a zoned type is the same
-- portable choice as V013's cutoff_time/cutoff_timezone. 24x7 schemes carry
-- open 00:00:00 / close 23:59:59 (TIME '24:00' is not portable to H2).
-- weekday is ISO-ish 0..6 with 0 = Monday .. 6 = Sunday.
--
-- COMPATIBILITY
-- -------------
-- Plain TIME / TIMESTAMP-free table; engine-neutral chain (H2 PG-mode + PG16).
-- Seeds are idempotent (INSERT ... SELECT ... WHERE NOT EXISTS) so the
-- migration re-applies cleanly on databases where an earlier seed attempt
-- already landed rows.

CREATE TABLE scheme_operating_hours (
    -- Same closed roster as partner_scheme (V022). No FK — this is reference
    -- data keyed by the scheme identifier itself.
    scheme_id          VARCHAR(20) NOT NULL,

    -- 0 = Monday .. 6 = Sunday.
    weekday            INT         NOT NULL,

    -- Wall-clock window in `timezone` during which the scheme accepts traffic.
    open_time_local    TIME        NOT NULL,
    close_time_local   TIME        NOT NULL,

    -- Daily settlement cutoff (wall-clock in `timezone`); NULL = the scheme
    -- has no intra-day cutoff.
    cutoff_time_local  TIME,

    -- IANA zone id the three TIME columns are evaluated in (V013 precedent).
    timezone           VARCHAR(40) NOT NULL,

    CONSTRAINT pk_scheme_operating_hours PRIMARY KEY (scheme_id, weekday),

    CONSTRAINT ck_scheme_operating_hours_weekday CHECK (
        weekday BETWEEN 0 AND 6
    )
);

-- ZEROPAY: 24x7 rail; KFTC interbank settlement cutoff 16:30 KST.
INSERT INTO scheme_operating_hours
    (scheme_id, weekday, open_time_local, close_time_local, cutoff_time_local, timezone)
SELECT 'ZEROPAY', w.weekday, TIME '00:00:00', TIME '23:59:59', TIME '16:30:00', 'Asia/Seoul'
FROM (VALUES (0), (1), (2), (3), (4), (5), (6)) AS w(weekday)
WHERE NOT EXISTS (
    SELECT 1 FROM scheme_operating_hours s
    WHERE s.scheme_id = 'ZEROPAY' AND s.weekday = w.weekday
);

-- BAKONG: 24x7 rail; NBC settlement window 09:00-15:00 ICT — the 15:00 close
-- of the NBC window is the effective daily cutoff.
INSERT INTO scheme_operating_hours
    (scheme_id, weekday, open_time_local, close_time_local, cutoff_time_local, timezone)
SELECT 'BAKONG', w.weekday, TIME '00:00:00', TIME '23:59:59', TIME '15:00:00', 'Asia/Phnom_Penh'
FROM (VALUES (0), (1), (2), (3), (4), (5), (6)) AS w(weekday)
WHERE NOT EXISTS (
    SELECT 1 FROM scheme_operating_hours s
    WHERE s.scheme_id = 'BAKONG' AND s.weekday = w.weekday
);

-- NAPAS_247: 24x7 instant rail, no intra-day cutoff.
INSERT INTO scheme_operating_hours
    (scheme_id, weekday, open_time_local, close_time_local, cutoff_time_local, timezone)
SELECT 'NAPAS_247', w.weekday, TIME '00:00:00', TIME '23:59:59', CAST(NULL AS TIME), 'Asia/Ho_Chi_Minh'
FROM (VALUES (0), (1), (2), (3), (4), (5), (6)) AS w(weekday)
WHERE NOT EXISTS (
    SELECT 1 FROM scheme_operating_hours s
    WHERE s.scheme_id = 'NAPAS_247' AND s.weekday = w.weekday
);

-- PROMPT_PAY: 24x7 instant rail, no intra-day cutoff.
INSERT INTO scheme_operating_hours
    (scheme_id, weekday, open_time_local, close_time_local, cutoff_time_local, timezone)
SELECT 'PROMPT_PAY', w.weekday, TIME '00:00:00', TIME '23:59:59', CAST(NULL AS TIME), 'Asia/Bangkok'
FROM (VALUES (0), (1), (2), (3), (4), (5), (6)) AS w(weekday)
WHERE NOT EXISTS (
    SELECT 1 FROM scheme_operating_hours s
    WHERE s.scheme_id = 'PROMPT_PAY' AND s.weekday = w.weekday
);

-- FAST_SG: 24x7 instant rail, no intra-day cutoff.
INSERT INTO scheme_operating_hours
    (scheme_id, weekday, open_time_local, close_time_local, cutoff_time_local, timezone)
SELECT 'FAST_SG', w.weekday, TIME '00:00:00', TIME '23:59:59', CAST(NULL AS TIME), 'Asia/Singapore'
FROM (VALUES (0), (1), (2), (3), (4), (5), (6)) AS w(weekday)
WHERE NOT EXISTS (
    SELECT 1 FROM scheme_operating_hours s
    WHERE s.scheme_id = 'FAST_SG' AND s.weekday = w.weekday
);

-- QRIS / KHQR: rostered in V022 but not yet seeded — their operating windows
-- arrive with the corridors that first enable them (a later migration).
