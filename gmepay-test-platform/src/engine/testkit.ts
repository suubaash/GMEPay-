import { createHash, createHmac, randomUUID } from 'node:crypto';
import type { UseCaseMeta } from '../shared/types';
import { GmePayClient } from './client';
import { Check, Recorder } from './assert';
import { CREDENTIALS, FIXTURES, gatewayPartnerCode } from '../config';
import {
  CredentialKind,
  MissingCredentialError,
  credentialStatus,
} from './credentials';

/**
 * Build api-gateway partner-edge headers. Canonical string is
 * METHOD\nPATH_WITH_QUERY\nX-Timestamp\nSHA256_HEX(body), signed HMAC-SHA256
 * (lowercase hex). See HmacSignatureVerifier.
 *
 * Two things changed on 2026-07-28 and both broke the old version of this helper:
 *  1. The published stub pair `pk_test_abc` / `sk_test_xyz` authenticates nothing —
 *     StubPartnerCredentialService was deleted from src/main, so the gateway holds no
 *     HMAC material for it and correctly answers 401 INVALID_API_KEY. Real values must
 *     come from the environment.
 *  2. `X-Nonce` is now mandatory and fail-closed (absent → 400, reused → 401), and
 *     `X-Partner-Id` must MATCH the partner the key resolves to (mismatch → 403
 *     PARTNER_ID_MISMATCH). The old helper sent neither.
 */
export function gatewayHeaders(
  method: string,
  pathWithQuery: string,
  body = '',
): Record<string, string> {
  const status = credentialStatus('partner-key');
  if (!status.configured) {
    throw new MissingCredentialError('partner-key', status.missing, status.hint);
  }
  const ts = new Date().toISOString();
  const bodyHash = createHash('sha256').update(body).digest('hex');
  const canonical = `${method}\n${pathWithQuery}\n${ts}\n${bodyHash}`;
  const signature = createHmac('sha256', CREDENTIALS.partnerHmacSecret!)
    .update(canonical)
    .digest('hex');
  return {
    'X-API-Key': CREDENTIALS.partnerApiKey!,
    'X-Timestamp': ts,
    'X-Nonce': randomUUID(),
    'X-Partner-Id': gatewayPartnerCode(),
    'X-Signature': signature,
    'X-Idempotency-Key': `idem-${ts}`,
  };
}

/**
 * sim-scheme starts with an empty merchant registry and 404s on authorize for
 * unknown merchants. Register the sandbox merchant (idempotent — saveMerchant
 * overwrites) so the real-time scheme leg + wallet payment path can complete.
 * The simulators are not internal-gated.
 */
export async function ensureSchemeMerchant(client: GmePayClient): Promise<void> {
  await client.call('sim-scheme', 'POST', '/v1/scheme/merchants', {
    merchantId: FIXTURES.merchantId,
    name: 'Seoul Mart',
    city: 'Seoul',
    mcc: '5999',
  });
}

/** Context handed to every test's run() function. */
export interface Ctx {
  client: GmePayClient;
  check: Check;
  rec: Recorder;
}

export interface UseCase extends UseCaseMeta {
  run?: (ctx: Ctx) => Promise<void>;
  /** Typed form of UseCaseMeta.credentials. */
  credentials?: CredentialKind[];
}

/** Declare a known-blocked entry whose first step states the real reason. */
export function blocked(
  id: string,
  bs: string,
  bsTitle: string,
  title: string,
  mvp: boolean,
  services: string[],
  reason: string,
): UseCase {
  return {
    id,
    bs,
    bsTitle,
    title,
    mvp,
    phase: mvp ? 'Phase 1 (MVP)' : 'Phase 2',
    services,
    intent: reason,
    automated: true,
    async run({ check, rec }) {
      rec.info('Probing intended function — known limitation from PRD audit:');
      check.blockedIf(true, reason);
    },
  };
}

/**
 * Declare a case whose asserted behaviour was deliberately REMOVED from the
 * platform, so it can never run again. Kept in the matrix on purpose: deleting it
 * would quietly shrink the traceability list, and letting it fail would misreport a
 * deliberate security decision as a defect.
 */
export function unsupported(
  id: string,
  bs: string,
  bsTitle: string,
  title: string,
  mvp: boolean,
  services: string[],
  reason: string,
): UseCase {
  return {
    id,
    bs,
    bsTitle,
    title,
    mvp,
    phase: mvp ? 'Phase 1 (MVP)' : 'Phase 2',
    services,
    intent: reason,
    automated: false,
    unsupportedReason: reason,
    credentials: [],
  };
}

/** Short unique suffix for self-contained test data (partner codes, refs). */
export function uniq(prefix = ''): string {
  return prefix + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
}

/** Today's date as YYYY-MM-DD (UTC) for date-range queries. */
export function today(): string {
  return new Date().toISOString().slice(0, 10);
}
