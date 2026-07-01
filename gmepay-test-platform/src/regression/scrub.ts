/**
 * Normalization / scrubbing — the determinism spine.
 *
 * Two before/after runs happen minutes apart against ONE environment, so any
 * value that changes with time (uuids, timestamps, generated refs) would show
 * up as a fake diff. We replace those with stable tokens BEFORE diffing, so the
 * only diffs left are the ones your code actually caused.
 */

const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi;
const ISO_DATE = /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:?\d{2})?/g;
const EPOCH_MS = /\b1[0-9]{12}\b/g; // 13-digit ms epoch (2001-2286)

/** Default field names scrubbed everywhere — volatile identifiers, not values. */
export const DEFAULT_SCRUB_FIELDS = [
  'id',
  'traceId',
  'requestId',
  'correlationId',
  'txnRef',
  'transactionRef',
  'schemeTxnRef',
  'approvalCode',
  'createdAt',
  'updatedAt',
  'timestamp',
  'issuedAt',
  'expiresAt',
];

function scrubString(s: string): string {
  return s
    .replace(UUID, '<uuid>')
    .replace(ISO_DATE, '<timestamp>')
    .replace(EPOCH_MS, '<epoch>');
}

/**
 * Deep-clone `value`, replacing scrubbed fields with `<field>` tokens and
 * (when auto) normalizing volatile substrings inside every string.
 */
export function normalize(value: unknown, fields: string[], auto = true): unknown {
  const scrubSet = new Set(fields.map((f) => f.toLowerCase()));

  const walk = (v: unknown): unknown => {
    if (v === null || v === undefined) return v;
    if (Array.isArray(v)) return v.map(walk);
    if (typeof v === 'object') {
      const out: Record<string, unknown> = {};
      for (const [k, val] of Object.entries(v as Record<string, unknown>)) {
        if (scrubSet.has(k.toLowerCase())) out[k] = `<${k}>`;
        else out[k] = walk(val);
      }
      return out;
    }
    if (typeof v === 'string') return auto ? scrubString(v) : v;
    return v;
  };

  return walk(value);
}
