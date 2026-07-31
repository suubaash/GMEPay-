/**
 * Dry-run / plan mode. Prints which credential every case would present and whether
 * the environment supplies it — WITHOUT firing a single HTTP request, so it works
 * with no fleet, no Keycloak and no Docker.
 *
 * This is the static answer to "what will 401 and why", which is the question the
 * 2026-07-28 hardening made expensive to answer by running the suite.
 */
import { USE_CASES } from './engine/registry';
import {
  CredentialKind,
  allCredentialStatuses,
  credentialStatus,
} from './engine/credentials';
import type { PlanRow } from './shared/types';

export function buildPlan(): PlanRow[] {
  return USE_CASES.map((uc) => {
    const creds = (uc.credentials ?? []) as CredentialKind[];
    const missing = [
      ...new Set(creds.flatMap((k) => credentialStatus(k).missing)),
    ];
    return {
      id: uc.id,
      title: uc.title,
      kind: uc.kind ?? 'use-case',
      credentials: creds,
      ready: !uc.unsupportedReason && missing.length === 0,
      missing,
      unsupportedReason: uc.unsupportedReason,
    };
  });
}

/** Group the plan by the credential a case needs — the useful executive view. */
export function planSummary(rows: PlanRow[]) {
  const byCredential = new Map<string, PlanRow[]>();
  for (const r of rows) {
    const keys = r.credentials.length ? r.credentials : ['none'];
    for (const k of keys) {
      if (!byCredential.has(k)) byCredential.set(k, []);
      byCredential.get(k)!.push(r);
    }
  }
  return {
    total: rows.length,
    ready: rows.filter((r) => r.ready).length,
    blockedOnCredentials: rows.filter((r) => !r.ready && !r.unsupportedReason).length,
    unsupported: rows.filter((r) => !!r.unsupportedReason).length,
    byCredential,
  };
}

export function printPlan(): void {
  const rows = buildPlan();
  const s = planSummary(rows);

  console.log('\n  GMEPay+ Test Platform — credential plan (no HTTP is sent)\n');
  console.log('  Credential availability in THIS environment:');
  for (const c of allCredentialStatuses()) {
    const mark = c.configured ? 'OK     ' : 'MISSING';
    console.log(`    [${mark}] ${c.kind.padEnd(14)} ${c.label}`);
    console.log(`               presents: ${c.presentedAs}`);
    if (c.configured && c.suppliedBy.length)
      console.log(`               from:     ${c.suppliedBy.join(', ')}`);
    if (!c.configured) console.log(`               set:      ${c.missing.join(' + ')}`);
  }

  console.log('\n  Cases by credential:');
  for (const [kind, list] of [...s.byCredential].sort()) {
    const ready = list.filter((r) => r.ready).length;
    console.log(`    ${kind.padEnd(14)} ${String(list.length).padStart(3)} cases  (${ready} ready)`);
  }

  const unsupported = rows.filter((r) => r.unsupportedReason);
  if (unsupported.length) {
    console.log('\n  UNSUPPORTED (capability deliberately withdrawn — will never run):');
    for (const r of unsupported) console.log(`    ${r.id.padEnd(22)} ${r.title}`);
  }

  const blocked = rows.filter((r) => !r.ready && !r.unsupportedReason);
  if (blocked.length) {
    console.log('\n  Would be BLOCKED on a missing credential:');
    for (const r of blocked)
      console.log(`    ${r.id.padEnd(22)} needs ${r.missing.join(' + ')}`);
  }

  console.log(
    `\n  ${s.total} cases · ${s.ready} ready · ${s.blockedOnCredentials} blocked on credentials · ` +
      `${s.unsupported} unsupported\n`,
  );
}
