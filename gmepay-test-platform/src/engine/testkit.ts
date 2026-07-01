import { createHash, createHmac } from 'node:crypto';
import type { UseCaseMeta } from '../shared/types';
import { GmePayClient } from './client';
import { Check, Recorder } from './assert';
import { FIXTURES } from '../config';

/**
 * Build valid api-gateway HMAC headers for a request. Canonical string is
 * METHOD\nPATH_WITH_QUERY\nX-Timestamp\nSHA256_HEX(body), signed HMAC-SHA256
 * (lowercase hex) with the stub partner secret. See HmacSignatureVerifier.
 */
export function gatewayHeaders(method: string, pathWithQuery: string, body = ''): Record<string, string> {
  const ts = new Date().toISOString();
  const bodyHash = createHash('sha256').update(body).digest('hex');
  const canonical = `${method}\n${pathWithQuery}\n${ts}\n${bodyHash}`;
  const signature = createHmac('sha256', 'sk_test_xyz').update(canonical).digest('hex');
  return {
    'X-API-Key': 'pk_test_abc',
    'X-Timestamp': ts,
    'X-Signature': signature,
    'X-Idempotency-Key': `idem-${ts}`,
  };
}

/** RBAC identity headers the gateway would normally stamp (services trust them). */
export function rbacHeaders(permissions: string, principalId = '1'): Record<string, string> {
  return { 'X-Gme-Principal-Id': principalId, 'X-Gme-Permissions': permissions };
}

/**
 * sim-scheme starts with an empty merchant registry and 404s on authorize for
 * unknown merchants. Register the sandbox merchant (idempotent — saveMerchant
 * overwrites) so the real-time scheme leg + wallet payment path can complete.
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

/** Short unique suffix for self-contained test data (partner codes, refs). */
export function uniq(prefix = ''): string {
  return prefix + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
}

/** Today's date as YYYY-MM-DD (UTC) for date-range queries. */
export function today(): string {
  return new Date().toISOString().slice(0, 10);
}
