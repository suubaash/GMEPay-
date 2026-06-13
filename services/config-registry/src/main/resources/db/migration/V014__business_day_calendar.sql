-- V014: business_day_calendar reference table + 2026-2028 holiday seed —
-- Slice 4 "Banking & Settlement" (docs/PARTNER_SETUP_PLAN.md §Slice 4).
--
-- PURPOSE
-- -------
-- Drives SettlementScheduleCalculator: a projected payout date must skip
-- weekends plus the holidays of BOTH KR (GME side — every settlement touches a
-- Korean bank) and the partner's bank country. Plain reference data, NOT
-- bitemporal: a holiday is a public fact, corrections are ordinary UPDATE/INSERT
-- through later migrations, and nothing regulated hinges on "what did we think
-- the 2027 calendar was last March".
--
-- SEED ACCURACY / SOURCES
-- -----------------------
-- KR 2026-2028: statutory days per the Public Holidays Act incl. substitute
--   holidays (대체공휴일) and the 2026 restoration of Constitution Day (제헌절,
--   Jul 17 — re-designated a statutory holiday by the Jan-2026 amendment of the
--   공휴일에 관한 법률, effective from 2026). Workers' Day (May 1) is not a
--   state public holiday but IS a bank/financial-market closure under the
--   Labor Standards Act — it matters for settlement, so it is seeded.
--   2026 also carries the 9th nationwide local election (Jun 3); 2028 carries
--   the scheduled 23rd National Assembly election (2nd Wednesday of April =
--   Apr 12, 2028) — statutory election-day holidays.
-- KH: Khmer New Year, Pchum Ben, Water Festival (Bon Om Touk) — the three
--   multi-day bank-closure blocks. 2026 dates are per the published royal
--   sub-decree; 2027/2028 lunar dates are best-known projections (the royal
--   sub-decree for each year lands the preceding autumn).
-- VN: Tet block + National Day (+ the fixed statutory days). The exact Tet
--   bridge days are set by an annual MOLISA decision; the seeded weekdays are
--   the statutory 5-day core around Lunar New Year.
-- TH: New Year + Songkran (the settlement-relevant fixed blocks) incl. Bank of
--   Thailand substitute days when a day falls on a weekend.
-- MN: Tsagaan Sar (Mongolian lunar new year — NOTE: diverges from the Chinese
--   lunar calendar in some years, e.g. 2028 falls a month after Chinese LNY)
--   and Naadam (Jul 11-15, fixed in law).
-- Where a 2027/2028 lunar conversion is not yet officially gazetted the
-- best-known date is seeded and flagged with a comment; an ordinary follow-up
-- migration corrects any drift once the official calendars publish.
--
-- COMPATIBILITY: BIGSERIAL + CHAR(2) + DATE run identically on PostgreSQL and
-- H2 PG-mode. Multi-row INSERT ... VALUES is portable across both.
-- ADR-013 Expand discipline: wholly-new table; no in-place ALTERs.

CREATE TABLE business_day_calendar (
    id           BIGSERIAL    NOT NULL,

    -- ISO-3166 alpha-2 country the closure applies to.
    country      CHAR(2)      NOT NULL,

    holiday_date DATE         NOT NULL,

    -- Human-readable name; surfaces verbatim in SettlementPreview explanations
    -- ("2026-09-25 skipped - KR holiday: Chuseok").
    name         VARCHAR(100) NOT NULL,

    CONSTRAINT pk_business_day_calendar PRIMARY KEY (id),

    -- One row per (country, date): re-seeding or correcting uses UPSERT
    -- semantics in later migrations instead of stacking duplicates.
    CONSTRAINT uq_business_day_calendar UNIQUE (country, holiday_date)
);

-- Calculator hot path: "holidays for countries (KR, XX) between d1 and d2".
-- The UNIQUE above already indexes (country, holiday_date); no further index
-- needed.

-- =========================== KOREA (KR) ====================================
-- 2026 ----------------------------------------------------------------------
INSERT INTO business_day_calendar (country, holiday_date, name) VALUES
    ('KR', DATE '2026-01-01', 'New Year''s Day'),
    ('KR', DATE '2026-02-16', 'Seollal holiday'),
    ('KR', DATE '2026-02-17', 'Seollal (Korean Lunar New Year)'),
    ('KR', DATE '2026-02-18', 'Seollal holiday'),
    ('KR', DATE '2026-03-01', 'Independence Movement Day'),
    ('KR', DATE '2026-03-02', 'Substitute holiday (Independence Movement Day)'),
    ('KR', DATE '2026-05-01', 'Workers'' Day (bank closure)'),
    ('KR', DATE '2026-05-05', 'Children''s Day'),
    ('KR', DATE '2026-05-24', 'Buddha''s Birthday'),
    ('KR', DATE '2026-05-25', 'Substitute holiday (Buddha''s Birthday)'),
    ('KR', DATE '2026-06-03', 'Local election day (9th nationwide local elections)'),
    ('KR', DATE '2026-06-06', 'Memorial Day'),
    ('KR', DATE '2026-07-17', 'Constitution Day (restored statutory holiday from 2026)'),
    ('KR', DATE '2026-08-15', 'Liberation Day'),
    ('KR', DATE '2026-08-17', 'Substitute holiday (Liberation Day)'),
    ('KR', DATE '2026-09-24', 'Chuseok holiday'),
    ('KR', DATE '2026-09-25', 'Chuseok'),
    ('KR', DATE '2026-09-26', 'Chuseok holiday'),
    ('KR', DATE '2026-10-03', 'National Foundation Day'),
    ('KR', DATE '2026-10-05', 'Substitute holiday (National Foundation Day)'),
    ('KR', DATE '2026-10-09', 'Hangul Day'),
    ('KR', DATE '2026-12-25', 'Christmas Day');

-- 2027 ----------------------------------------------------------------------
-- Seollal: the Korean lunisolar day-1 falls on Feb 7 KST in 2027 (one day
-- after Chinese LNY — the new moon lands after midnight KST), so the block is
-- Feb 6-8 with the Sunday-overlap substitute on Feb 9.
INSERT INTO business_day_calendar (country, holiday_date, name) VALUES
    ('KR', DATE '2027-01-01', 'New Year''s Day'),
    ('KR', DATE '2027-02-06', 'Seollal holiday'),
    ('KR', DATE '2027-02-07', 'Seollal (Korean Lunar New Year)'),
    ('KR', DATE '2027-02-08', 'Seollal holiday'),
    ('KR', DATE '2027-02-09', 'Substitute holiday (Seollal)'),
    ('KR', DATE '2027-03-01', 'Independence Movement Day'),
    ('KR', DATE '2027-05-01', 'Workers'' Day (bank closure)'),
    ('KR', DATE '2027-05-05', 'Children''s Day'),
    ('KR', DATE '2027-05-13', 'Buddha''s Birthday'),
    ('KR', DATE '2027-06-06', 'Memorial Day'),
    ('KR', DATE '2027-07-17', 'Constitution Day'),
    -- Constitution Day 2027 falls on a Saturday; as a national celebration day
    -- it carries the Sat/Sun substitute rule -> Monday Jul 19 (subject to the
    -- official 2027 gazette).
    ('KR', DATE '2027-07-19', 'Substitute holiday (Constitution Day)'),
    ('KR', DATE '2027-08-15', 'Liberation Day'),
    ('KR', DATE '2027-08-16', 'Substitute holiday (Liberation Day)'),
    ('KR', DATE '2027-09-14', 'Chuseok holiday'),
    ('KR', DATE '2027-09-15', 'Chuseok'),
    ('KR', DATE '2027-09-16', 'Chuseok holiday'),
    ('KR', DATE '2027-10-03', 'National Foundation Day'),
    ('KR', DATE '2027-10-04', 'Substitute holiday (National Foundation Day)'),
    ('KR', DATE '2027-10-09', 'Hangul Day'),
    ('KR', DATE '2027-10-11', 'Substitute holiday (Hangul Day)'),
    ('KR', DATE '2027-12-25', 'Christmas Day'),
    ('KR', DATE '2027-12-27', 'Substitute holiday (Christmas Day)');

-- 2028 ----------------------------------------------------------------------
-- Chuseok day (lunar Aug 15) falls on Oct 3, coinciding with National
-- Foundation Day -> the overlap substitute lands on Thu Oct 5. Buddha's
-- Birthday May 2 is the best-known lunar conversion (official 2028 calendar
-- not yet gazetted).
INSERT INTO business_day_calendar (country, holiday_date, name) VALUES
    ('KR', DATE '2028-01-01', 'New Year''s Day'),
    ('KR', DATE '2028-01-25', 'Seollal holiday'),
    ('KR', DATE '2028-01-26', 'Seollal (Korean Lunar New Year)'),
    ('KR', DATE '2028-01-27', 'Seollal holiday'),
    ('KR', DATE '2028-03-01', 'Independence Movement Day'),
    ('KR', DATE '2028-04-12', 'National Assembly election day (scheduled, 2nd Wed of April)'),
    ('KR', DATE '2028-05-01', 'Workers'' Day (bank closure)'),
    ('KR', DATE '2028-05-02', 'Buddha''s Birthday (best-known lunar date)'),
    ('KR', DATE '2028-05-05', 'Children''s Day'),
    ('KR', DATE '2028-06-06', 'Memorial Day'),
    ('KR', DATE '2028-07-17', 'Constitution Day'),
    ('KR', DATE '2028-08-15', 'Liberation Day'),
    ('KR', DATE '2028-10-02', 'Chuseok holiday'),
    ('KR', DATE '2028-10-03', 'Chuseok / National Foundation Day'),
    ('KR', DATE '2028-10-04', 'Chuseok holiday'),
    ('KR', DATE '2028-10-05', 'Substitute holiday (Chuseok/National Foundation Day overlap)'),
    ('KR', DATE '2028-10-09', 'Hangul Day'),
    ('KR', DATE '2028-12-25', 'Christmas Day');

-- ========================== CAMBODIA (KH) ===================================
-- The three multi-day bank-closure blocks. 2026 per the published royal
-- sub-decree; 2027/2028 are best-known lunar projections.
INSERT INTO business_day_calendar (country, holiday_date, name) VALUES
    ('KH', DATE '2026-04-14', 'Khmer New Year'),
    ('KH', DATE '2026-04-15', 'Khmer New Year'),
    ('KH', DATE '2026-04-16', 'Khmer New Year'),
    ('KH', DATE '2026-10-10', 'Pchum Ben'),
    ('KH', DATE '2026-10-11', 'Pchum Ben'),
    ('KH', DATE '2026-10-12', 'Pchum Ben'),
    ('KH', DATE '2026-11-23', 'Water Festival (Bon Om Touk)'),
    ('KH', DATE '2026-11-24', 'Water Festival (Bon Om Touk)'),
    ('KH', DATE '2026-11-25', 'Water Festival (Bon Om Touk)'),

    ('KH', DATE '2027-04-14', 'Khmer New Year'),
    ('KH', DATE '2027-04-15', 'Khmer New Year'),
    ('KH', DATE '2027-04-16', 'Khmer New Year'),
    -- 2027 lunar projections (royal sub-decree pending):
    ('KH', DATE '2027-09-29', 'Pchum Ben (projected)'),
    ('KH', DATE '2027-09-30', 'Pchum Ben (projected)'),
    ('KH', DATE '2027-10-01', 'Pchum Ben (projected)'),
    ('KH', DATE '2027-11-12', 'Water Festival (Bon Om Touk, projected)'),
    ('KH', DATE '2027-11-13', 'Water Festival (Bon Om Touk, projected)'),
    ('KH', DATE '2027-11-14', 'Water Festival (Bon Om Touk, projected)'),

    ('KH', DATE '2028-04-14', 'Khmer New Year'),
    ('KH', DATE '2028-04-15', 'Khmer New Year'),
    ('KH', DATE '2028-04-16', 'Khmer New Year'),
    -- 2028 lunar projections (an intercalary Khmer month pushes Pchum Ben back
    -- to early October):
    ('KH', DATE '2028-10-07', 'Pchum Ben (projected)'),
    ('KH', DATE '2028-10-08', 'Pchum Ben (projected)'),
    ('KH', DATE '2028-10-09', 'Pchum Ben (projected)'),
    ('KH', DATE '2028-10-31', 'Water Festival (Bon Om Touk, projected)'),
    ('KH', DATE '2028-11-01', 'Water Festival (Bon Om Touk, projected)'),
    ('KH', DATE '2028-11-02', 'Water Festival (Bon Om Touk, projected)');

-- =========================== VIETNAM (VN) ===================================
-- Statutory fixed days + the 5-day Tet core. The annual MOLISA decision adds
-- bridge/substitute days (e.g. weekend swaps) that an ordinary follow-up
-- migration picks up once published.
INSERT INTO business_day_calendar (country, holiday_date, name) VALUES
    ('VN', DATE '2026-01-01', 'New Year''s Day'),
    -- Tet 2026: Lunar New Year Feb 17 (Tue); statutory block Mon-Fri.
    ('VN', DATE '2026-02-16', 'Tet holiday (Lunar New Year''s Eve)'),
    ('VN', DATE '2026-02-17', 'Tet (Lunar New Year)'),
    ('VN', DATE '2026-02-18', 'Tet holiday'),
    ('VN', DATE '2026-02-19', 'Tet holiday'),
    ('VN', DATE '2026-02-20', 'Tet holiday'),
    ('VN', DATE '2026-04-30', 'Reunification Day'),
    ('VN', DATE '2026-05-01', 'International Labour Day'),
    ('VN', DATE '2026-09-02', 'National Day'),

    ('VN', DATE '2027-01-01', 'New Year''s Day'),
    -- Tet 2027: Lunar New Year Feb 6 (Sat); statutory 5 days fall Fri Feb 5 -
    -- Tue Feb 9 with weekend days substituting into the following week
    -- (MOLISA decision pending; the seeded weekdays are the projected
    -- closures).
    ('VN', DATE '2027-02-05', 'Tet holiday (Lunar New Year''s Eve)'),
    ('VN', DATE '2027-02-06', 'Tet (Lunar New Year)'),
    ('VN', DATE '2027-02-07', 'Tet holiday'),
    ('VN', DATE '2027-02-08', 'Tet holiday'),
    ('VN', DATE '2027-02-09', 'Tet holiday (projected substitute)'),
    ('VN', DATE '2027-02-10', 'Tet holiday (projected substitute)'),
    ('VN', DATE '2027-04-30', 'Reunification Day'),
    ('VN', DATE '2027-05-01', 'International Labour Day'),
    ('VN', DATE '2027-09-02', 'National Day'),

    ('VN', DATE '2028-01-01', 'New Year''s Day'),
    -- Tet 2028: Lunar New Year Jan 26 (Wed); block Tue Jan 25 - Sat Jan 29,
    -- Saturday day substituting to Mon Jan 31 (projected).
    ('VN', DATE '2028-01-25', 'Tet holiday (Lunar New Year''s Eve)'),
    ('VN', DATE '2028-01-26', 'Tet (Lunar New Year)'),
    ('VN', DATE '2028-01-27', 'Tet holiday'),
    ('VN', DATE '2028-01-28', 'Tet holiday'),
    ('VN', DATE '2028-01-31', 'Tet holiday (projected substitute)'),
    ('VN', DATE '2028-04-30', 'Reunification Day'),
    ('VN', DATE '2028-05-01', 'International Labour Day'),
    -- Apr 30 2028 falls on a Sunday -> projected substitute Tue May 2.
    ('VN', DATE '2028-05-02', 'Reunification Day (projected substitute)'),
    ('VN', DATE '2028-09-02', 'National Day'),
    -- Sep 2 2028 falls on a Saturday -> projected substitute Mon Sep 4.
    ('VN', DATE '2028-09-04', 'National Day (projected substitute)');

-- =========================== THAILAND (TH) ==================================
-- New Year + Songkran blocks (the settlement-relevant fixed closures), with
-- Bank of Thailand substitute days when a day falls on a weekend.
INSERT INTO business_day_calendar (country, holiday_date, name) VALUES
    ('TH', DATE '2026-01-01', 'New Year''s Day'),
    ('TH', DATE '2026-04-13', 'Songkran Festival'),
    ('TH', DATE '2026-04-14', 'Songkran Festival'),
    ('TH', DATE '2026-04-15', 'Songkran Festival'),
    ('TH', DATE '2026-12-31', 'New Year''s Eve'),

    ('TH', DATE '2027-01-01', 'New Year''s Day'),
    ('TH', DATE '2027-04-13', 'Songkran Festival'),
    ('TH', DATE '2027-04-14', 'Songkran Festival'),
    ('TH', DATE '2027-04-15', 'Songkran Festival'),
    ('TH', DATE '2027-12-31', 'New Year''s Eve'),

    ('TH', DATE '2028-01-01', 'New Year''s Day'),
    -- Jan 1 2028 falls on a Saturday -> substitute Mon Jan 3.
    ('TH', DATE '2028-01-03', 'Substitute for New Year''s Day'),
    ('TH', DATE '2028-04-13', 'Songkran Festival'),
    ('TH', DATE '2028-04-14', 'Songkran Festival'),
    ('TH', DATE '2028-04-15', 'Songkran Festival'),
    -- Apr 15 2028 falls on a Saturday -> substitute Mon Apr 17.
    ('TH', DATE '2028-04-17', 'Substitute for Songkran Festival'),
    ('TH', DATE '2028-12-31', 'New Year''s Eve');
    -- (Dec 31 2028 is a Sunday; its substitute falls on Jan 2, 2029 — outside
    -- this seed window.)

-- =========================== MONGOLIA (MN) ==================================
-- Tsagaan Sar follows the MONGOLIAN lunisolar calendar, which diverges from
-- the Chinese calendar in some years: 2028 day-1 is Feb 26, a month after
-- Chinese LNY (best-known projection; officially announced each preceding
-- year). Naadam Jul 11-15 is fixed in law.
INSERT INTO business_day_calendar (country, holiday_date, name) VALUES
    ('MN', DATE '2026-02-18', 'Tsagaan Sar (Mongolian Lunar New Year)'),
    ('MN', DATE '2026-02-19', 'Tsagaan Sar holiday'),
    ('MN', DATE '2026-02-20', 'Tsagaan Sar holiday'),
    ('MN', DATE '2026-07-11', 'Naadam'),
    ('MN', DATE '2026-07-12', 'Naadam'),
    ('MN', DATE '2026-07-13', 'Naadam'),
    ('MN', DATE '2026-07-14', 'Naadam'),
    ('MN', DATE '2026-07-15', 'Naadam'),

    ('MN', DATE '2027-02-07', 'Tsagaan Sar (Mongolian Lunar New Year)'),
    ('MN', DATE '2027-02-08', 'Tsagaan Sar holiday'),
    ('MN', DATE '2027-02-09', 'Tsagaan Sar holiday'),
    ('MN', DATE '2027-07-11', 'Naadam'),
    ('MN', DATE '2027-07-12', 'Naadam'),
    ('MN', DATE '2027-07-13', 'Naadam'),
    ('MN', DATE '2027-07-14', 'Naadam'),
    ('MN', DATE '2027-07-15', 'Naadam'),

    -- 2028 Tsagaan Sar: Feb 26 day-1 (projected from the Mongolian astrological
    -- calendar; NOT the Chinese LNY of Jan 26).
    ('MN', DATE '2028-02-26', 'Tsagaan Sar (Mongolian Lunar New Year, projected)'),
    ('MN', DATE '2028-02-27', 'Tsagaan Sar holiday (projected)'),
    ('MN', DATE '2028-02-28', 'Tsagaan Sar holiday (projected)'),
    ('MN', DATE '2028-07-11', 'Naadam'),
    ('MN', DATE '2028-07-12', 'Naadam'),
    ('MN', DATE '2028-07-13', 'Naadam'),
    ('MN', DATE '2028-07-14', 'Naadam'),
    ('MN', DATE '2028-07-15', 'Naadam');
