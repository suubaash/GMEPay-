# Before/After Regression Harness

Record the current behavior of GMEPay+ (**before**), publish your change, record
again (**after**), and get a field-by-field diff — all against **one** environment,
run by hand.

> UI: `http://localhost:4000/regression/` (after the test-platform server is up)
> API: `/api/reg/*`

## The loop

1. **Record `before`** — pick the case groups, label the run `before`, hit *Record run*.
2. **Publish** your change to the environment.
3. **Record `after`** — label it `after`, *Record run*.
4. **Compare** — select before + after, *Compare*. Red = a value your change moved.

## Try it with zero services running

The `mock` group is a built-in fake FX quote so you can see the whole loop work:

```
REG_MOCK_VERSION=1  → start server → record "before"
# stop, set REG_MOCK_VERSION=2, restart   ← this stands in for "publish"
REG_MOCK_VERSION=2  → start server → record "after"
Compare → payoutAmount 995 → 995.5, marginBps 50 → 45, revenueSplit added.
(quoteId + issuedAt are scrubbed, so they do NOT show as diffs.)
```

## Writing real cases

Cases are JSON data in `cases/*.json` (add/edit without touching code). Copy
`cases/10-real-templates.json.example` → `cases/10-real.json` and fill in real
paths/bodies. Format:

```jsonc
{
  "id": "contract-fx-quote",
  "name": "rate-fx quote",
  "group": "calc | contract | flow | mock",
  "target": "rate-fx",          // service key from src/config.ts, or "url:http://host:port"
  "readOnly": true,             // false = creates data (see gotcha #2)
  "scrubFields": ["schemeTxnRef"], // extra volatile fields to neutralize
  "steps": [
    { "name": "quote", "method": "POST", "path": "/v1/quote",
      "headers": { "x-test-frozen-rate": "1330.50" },
      "body": { "sendAmount": 1000 } }
  ]
}
```

Flows chain steps and pass values with `{{steps.<step>.<dot.path>}}` and
`{{fixtures.<key>}}` (FIXTURES from `src/config.ts`).

## Two gotchas on a single environment

1. **Drift between runs** — pin FX rates (send a frozen-rate header / test flag) and
   use dedicated synthetic accounts, so the only difference is your code. Volatile
   ids/timestamps/refs are auto-scrubbed (`scrub.ts`).
2. **Writes change state** — prefer `readOnly` cases. For write flows, use a fixed
   idempotency key + reset the test account, or rely on scrubbing so generated ids
   don't count as diffs (this is what catches the "BFF faked the ref" class of bug:
   the fake ref is scrubbed, but the real business values are still compared).

## Layers

- **calc** — pure value math (safest, start here).
- **contract** — one endpoint's request→response shape.
- **flow** — multi-call frontend↔backend sequence through the BFF.
