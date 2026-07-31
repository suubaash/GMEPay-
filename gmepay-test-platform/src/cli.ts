/**
 * Headless runner — run the whole matrix (or an --mvp subset) from the terminal
 * or CI without the dashboard. Usage:
 *   npm run cli             # run every use case
 *   npm run cli -- --mvp    # run only Phase-1 MVP use cases
 *   npm run cli -- --plan   # dry run: show the credential each case would present
 *                           # and whether this environment supplies it. Sends no HTTP,
 *                           # so it needs no fleet, no Keycloak and no Docker.
 */
import { USE_CASES } from './engine/registry';
import { runUseCase } from './engine/runner';
import { printPlan } from './plan';

if (process.argv.includes('--plan')) {
  printPlan();
  process.exit(0);
}

const mvpOnly = process.argv.includes('--mvp');
const targets = mvpOnly ? USE_CASES.filter((u) => u.mvp) : USE_CASES;

const icon: Record<string, string> = {
  PASS: '✓',
  FAIL: '✗',
  BLOCKED: '⊘',
  UNSUPPORTED: '⊗',
  NOT_AUTOMATED: '·',
};

const tally: Record<string, number> = {};
for (const uc of targets) {
  const r = await runUseCase(uc);
  tally[r.status] = (tally[r.status] ?? 0) + 1;
  console.log(`${icon[r.status] ?? '?'} ${r.status.padEnd(14)} ${uc.id.padEnd(22)} ${uc.title}`);
  if (r.status === 'FAIL') console.log(`    └─ ${r.error}`);
}

console.log('\n' + Object.entries(tally).map(([k, v]) => `${k}: ${v}`).join('   '));
process.exit(tally.FAIL ? 1 : 0);
