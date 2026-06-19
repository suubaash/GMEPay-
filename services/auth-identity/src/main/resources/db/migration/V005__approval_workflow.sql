-- auth-identity V005: multi-level approval workflows (WBS 18.x — RBAC Phase, approval tiers).
--
-- DB-driven, tiered approval for high-risk operations (refund $1k/$5k tiers first), with
-- sequential multi-level steps and CFO break-glass. No hardcoded tiers: a policy maps
-- (request_type, currency, amount-band) → an ordered list of required approver permissions.
-- The engine (com.gme.pay.auth.approval.ApprovalWorkflowService) computes the tier at request
-- time, then gates each step on the approver holding the step's permission (or being a CFO
-- superuser). Maker-checker (distinct approvers, none = requester) is enforced in the service.
--
-- Decision bridge: ApprovalWorkflowService.decision(type, subjectRef, tenantId) reports whether the
-- latest matching request is granted. The cross-service wiring that stamps X-Gme-Approval-Granted
-- from this (so the APPROVAL constraint's ctx.approvalGranted() flips true) is the pending gateway
-- loop (WBS task: close constraint cross-service loop) — this migration builds the auth-identity side.
-- Conventions match V002/V003/V004 (BIGSERIAL, TIMESTAMP WITH TIME ZONE, snake_case, H2 PG-compat).

-- ── approval_policies: (request_type, currency, amount-band) → ordered step permissions ──
CREATE TABLE approval_policies (
    id               BIGSERIAL                PRIMARY KEY,
    request_type     VARCHAR(32)              NOT NULL,            -- e.g. 'REFUND'
    currency         VARCHAR(3),                                  -- NULL = any currency (wildcard fallback)
    min_amount       NUMERIC(20,4)            NOT NULL DEFAULT 0,  -- inclusive lower bound
    max_amount       NUMERIC(20,4),                               -- exclusive upper bound; NULL = no cap
    tier_label       VARCHAR(32)              NOT NULL,            -- SELF_SERVE | L1 | L2_CFO ...
    step_permissions VARCHAR(512)             NOT NULL DEFAULT '', -- ordered CSV of required permission codes; '' = none
    auto_approve     BOOLEAN                  NOT NULL DEFAULT FALSE,
    active           BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_approval_policies_band CHECK (max_amount IS NULL OR max_amount > min_amount)
);

-- ── approval_requests: one per high-risk operation needing sign-off ──
CREATE TABLE approval_requests (
    id             BIGSERIAL                PRIMARY KEY,
    request_type   VARCHAR(32)              NOT NULL,
    subject_ref    VARCHAR(128)             NOT NULL,             -- the operation reference (e.g. refund/txn ref)
    amount         NUMERIC(20,4)            NOT NULL,
    currency       VARCHAR(3)               NOT NULL,
    tier_label     VARCHAR(32)              NOT NULL,
    status         VARCHAR(16)              NOT NULL,             -- PENDING | APPROVED | REJECTED | AUTO_APPROVED
    -- ordered CSV of required approver permissions, snapshotted from the policy at request time
    -- (immutable per-request, so a later policy edit can't change an in-flight request's tier)
    step_permissions VARCHAR(512)           NOT NULL DEFAULT '',
    required_steps INTEGER                  NOT NULL DEFAULT 0,
    current_step   INTEGER                  NOT NULL DEFAULT 0,
    version        BIGINT                   NOT NULL DEFAULT 0,   -- JPA optimistic-lock guard
    requested_by   VARCHAR(128)             NOT NULL,
    requested_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_at     TIMESTAMP WITH TIME ZONE,
    reject_reason  VARCHAR(512),
    tenant_id      BIGINT,
    CONSTRAINT ck_approval_requests_status
        CHECK (status IN ('PENDING','APPROVED','REJECTED','AUTO_APPROVED'))
);

-- ── approval_decisions: per-step audit of who approved/rejected (incl. CFO override) ──
CREATE TABLE approval_decisions (
    id                  BIGSERIAL                PRIMARY KEY,
    request_id          BIGINT                   NOT NULL,
    step_index          INTEGER                  NOT NULL,
    required_permission VARCHAR(128),                             -- the step's demanded permission; NULL if CFO/auto
    approver_id         VARCHAR(128)             NOT NULL,
    decision            VARCHAR(16)              NOT NULL,        -- APPROVE | REJECT
    cfo_override        BOOLEAN                  NOT NULL DEFAULT FALSE,
    reason              VARCHAR(512),
    decided_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_approval_decisions_request FOREIGN KEY (request_id) REFERENCES approval_requests (id),
    CONSTRAINT ck_approval_decisions_decision CHECK (decision IN ('APPROVE','REJECT')),
    -- maker-checker DB backstop: a principal may act on a given request at most once, so a single
    -- person can never satisfy two steps of a multi-level approval (race-proof, not just service-checked)
    CONSTRAINT uq_approval_decisions_request_approver UNIQUE (request_id, approver_id)
);

CREATE INDEX idx_approval_policies_lookup  ON approval_policies (request_type, active);
CREATE INDEX idx_approval_requests_queue   ON approval_requests (status, requested_at);
CREATE INDEX idx_approval_requests_subject ON approval_requests (request_type, subject_ref);
CREATE INDEX idx_approval_decisions_request ON approval_decisions (request_id, step_index);

-- ── seed: approval-related permissions (added to the V003 catalogue) ──
INSERT INTO permissions (code, resource, action, description, created_at) VALUES
    ('refund.approve_l1',     'refund',   'approve_l1',   'Approve refunds at tier 1 ($1k-$5k)', CURRENT_TIMESTAMP),
    ('refund.approve_l2',     'refund',   'approve_l2',   'Approve refunds at tier 2 (>$5k)',    CURRENT_TIMESTAMP),
    ('approval.cfo_override', 'approval', 'cfo_override', 'CFO break-glass approval override',   CURRENT_TIMESTAMP),
    ('approval.view',         'approval', 'view',         'View the approval queue',             CURRENT_TIMESTAMP);

-- grant the new approval permissions to HUB_ADMIN (V003's catalogue cross-join predates them) --
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'HUB_ADMIN'
  AND p.code IN ('refund.approve_l1','refund.approve_l2','approval.cfo_override','approval.view');

-- ── seed: default REFUND tiers in USD (amount band is [min, max); thresholds per the spec) ──
INSERT INTO approval_policies (request_type, currency, min_amount, max_amount, tier_label, step_permissions, auto_approve, created_at) VALUES
    ('REFUND', 'USD',    0.0000, 1000.0000, 'SELF_SERVE', '',                                   TRUE,  CURRENT_TIMESTAMP),
    ('REFUND', 'USD', 1000.0000, 5000.0000, 'L1',         'refund.approve_l1',                  FALSE, CURRENT_TIMESTAMP),
    ('REFUND', 'USD', 5000.0000, NULL,      'L2_CFO',     'refund.approve_l1,refund.approve_l2', FALSE, CURRENT_TIMESTAMP);
