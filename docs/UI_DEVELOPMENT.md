# GMEPay+ — UI Development Guide

The design-system playbook for the two Next.js front-ends in `apps/`
(`admin-ui` and `partner-portal-ui`). This document is **prescriptive** — when
new screens are built, follow these conventions so the two apps stay
consistent and reviewable.

For app-level setup, login creds, and architecture context, see
[`apps/README.md`](../apps/README.md).

---

## 1. File & folder naming

| Kind | Convention | Example |
|---|---|---|
| Route folders (App Router) | kebab-case, ending in `page.tsx` | `src/app/partners/new/page.tsx` |
| Layout / route groups | kebab-case | `src/app/(dashboard)/layout.tsx` |
| React components | `PascalCase.tsx`, one component per file | `src/components/MoneyDisplay.tsx` |
| Hooks | `useCamelCase.ts` | `src/hooks/usePartner.ts` |
| Tests | co-located under `__tests__/`, suffix `.test.tsx` | `src/components/__tests__/MoneyDisplay.test.tsx` |
| API types | `src/api/types.ts` (single barrel) | `Partner`, `Transaction`, `MoneyAmount` |
| Redux slices | `src/store/<resource>Slice.ts` | `src/store/partnersSlice.ts` |
| Yup schemas | `src/schemas/<name>Schema.ts` | `src/schemas/partnerSchema.ts` |
| Lottie JSON | `src/lottie/<name>.json` | `src/lottie/empty-box.json` |

One screen = one folder. Keep page components thin: data-fetching dispatches to
a Redux thunk, rendering is delegated to presentational components in
`src/components/`.

---

## 2. State management (Redux Toolkit)

- **One slice per backend resource.** Resources mirror what the BFF returns:
  `dashboardSlice`, `partnersSlice`, `transactionsSlice`, `settlementSlice`, etc.
- **Thunks for async.** Use `createAsyncThunk` and dispatch from page
  components in a `useEffect`. Slice shape:
  ```ts
  { data: T | T[] | null, status: 'idle' | 'loading' | 'succeeded' | 'failed', error: string | null }
  ```
- **Selectors live next to the slice** and are imported via the typed hooks
  (`useAppSelector`, `useAppDispatch`) from `src/store/index.ts`.
- **No RTK Query in Phase 1.** It's an explicit Phase-2 candidate (see §11).
- Never read or mutate `localStorage` from a slice — that's the auth layer's
  job (§9).

---

## 3. Forms (React Hook Form + Yup)

- Schemas live at `src/schemas/<name>Schema.ts` and are reused between create
  and edit forms.
- Wire RHF with `useForm({ resolver: yupResolver(<schema>) })`.
- Use MUI `TextField`, `Select`, `Switch` controlled via `<Controller>` —
  uncontrolled `register()` is fine for plain `<input>` fields.
- Surface validation errors **inline** under each field (`helperText` +
  `error={!!fieldState.error}`) and a top-level `<ErrorAlert>` only for
  submission failures.
- Submit handlers dispatch a thunk and toast on success / error via
  `useSnackbar()` (§8).

---

## 4. API client

- Typed wrappers live in `src/api/client.ts`; request/response shapes in
  `src/api/types.ts`. **No `fetch` calls outside `src/api/`.**
- The client reads `NEXT_PUBLIC_BFF_BASE_URL` and injects the
  `Authorization: Bearer <token>` header automatically when a token is present
  in `localStorage` (set by the login flow, §9).
- Errors are normalised to a single shape:
  ```ts
  type ApiError = { status: number; code?: string; message: string; details?: unknown }
  ```
- Server responses are validated only as far as needed for runtime safety
  (narrow casts at the boundary); no `zod`/`io-ts` in Phase 1.

---

## 5. Theme (MUI)

- The theme lives in `src/theme/theme.ts` and is applied via
  `<ThemeRegistry>` in `app/layout.tsx`.
- **GMEPay+ brand palette:**
  - `primary.main` — deep blue `#1F3864`
  - `secondary.main` — slate gray (`#475569` family)
  - Success / warning / error use MUI defaults, tuned for AA contrast on white.
- Typography: MUI defaults with `Inter` as the body font. Headings use a
  slightly tighter `lineHeight: 1.2` for dashboard cards.
- Use the theme — never hard-code colors in components. If a color is missing,
  add it to the palette.

---

## 6. Money

> **Rule:** never format money inline. Always render it through `<MoneyDisplay>`.

```tsx
<MoneyDisplay amount="10500.567" currency="USD" />     {/* renders "10,500.57 USD"  */}
<MoneyDisplay amount="50000"     currency="KRW" />     {/* renders "50,000 KRW"     */}
```

`MoneyDisplay` consults the per-currency scale (KRW / JPY / VND → 0dp;
others → 2dp) and applies `Intl.NumberFormat` with the active locale. This
mirrors `lib-money/CurrencyScale` on the backend so display matches what the
ledger booked. See [`docs/MONEY_CONVENTION.md`](./MONEY_CONVENTION.md).

API responses keep money as a **decimal string + ISO-4217 currency** — never
parse it to `number` in JS (loses precision). `MoneyDisplay` accepts the
string directly.

---

## 7. Rounding mode (per-partner)

> **Rule:** any UI that edits a partner's settlement rounding mode uses
> `<RoundingModeSelect>` — never a raw `<Select>` with hand-typed options.

The seven supported modes mirror `java.math.RoundingMode`:

`HALF_UP` (default) · `HALF_DOWN` · `HALF_EVEN` · `DOWN` · `UP` · `CEILING` · `FLOOR`

```tsx
<RoundingModeSelect
  value={field.value}
  onChange={field.onChange}
  helperText="How this partner books its settlement liability"
/>
```

The control renders a short explainer (e.g. *"DOWN — truncates toward zero"*)
next to each option, so operators don't pick a mode they don't understand. Per
`docs/MONEY_CONVENTION.md`, the chosen mode is **rate-locked** onto every
transaction at commit time and the residual posts to `REVENUE_ROUNDING`.

---

## 8. Loading, errors, empty, toasts

These four UX states are **mandatory** on every fetched view — never let a
data-bound page render blank.

| State | Component | When |
|---|---|---|
| Loading | MUI `<Skeleton>` (page or block) | `status === 'loading'` and no prior data |
| Error | `<ErrorAlert error={...} onRetry={refetch} />` | `status === 'failed'` |
| Empty | `<EmptyState heading="No partners yet" cta={...} />` (Lottie centered) | `status === 'succeeded'` and `data.length === 0` |
| Success | The real component | otherwise |

Toasts use `useSnackbar()` from `<SnackbarProvider>` (mounted at the root
layout). The hook returns `success(msg)`, `error(msg)`, and `info(msg)` —
use them for transient feedback (form submit success, copy-to-clipboard,
optimistic updates).

```tsx
const snackbar = useSnackbar();
// ...
await dispatch(createPartner(values)).unwrap();
snackbar.success(`Partner ${values.id} created`);
```

---

## 9. Auth (Phase 1 stub)

The Phase 1 auth flow is intentionally minimal so the UIs can iterate before
`auth-identity` is wired:

- `/login` posts to the BFF's stub login endpoint and receives a JWT-shaped
  token.
- The token is stored in `localStorage` under `gmepay.auth.token`.
- `src/api/client.ts` reads the token and injects `Authorization: Bearer …`.
- `<AuthGate>` wraps the authenticated route group; on missing/expired token
  it redirects to `/login`.
- Logout clears the key and redirects to `/login`.

**Do not** scatter `localStorage` access across components — only `AuthGate`,
the login page, and the API client touch the storage key.

This entire layer is replaced in Phase 4 hardening by real OAuth2 (Authorization
Code + PKCE) against `auth-identity`, with refresh tokens, RBAC claims, and
silent renewal. The component boundaries are designed to make that swap
mechanical (replace `AuthGate` internals; the rest of the app keeps using the
typed API client unchanged).

---

## 10. Testing (Vitest + RTL)

- Co-locate tests under `__tests__/` next to the component or page.
- Aim for:
  - **1 test per non-trivial component** — covers default render, the
    interesting prop branch, and the user interaction.
  - **1 page-level test per high-traffic page** — mounts the page with a
    seeded Redux store and asserts the loading → succeeded transition.
- Mock the BFF at the `fetch` boundary (e.g. via `vi.stubGlobal('fetch', …)`)
  — don't mock individual API functions; you'll silently drift from the real
  contract.
- Use `@testing-library/user-event` over `fireEvent` for anything past a click.
- Snapshot tests are discouraged; assert on observable behaviour instead.

Run locally:

```bash
npm test                  # CI mode (single run)
npm test -- --watch       # watch mode for TDD
```

---

## 11. What's deferred to Phase 2

These are deliberate omissions in Phase 1 to keep the front-ends shippable.
Each one is on the Phase 2 / hardening backlog:

- **RTK Query / TanStack Query** — replace hand-rolled thunks + slice state
  with declarative caching.
- **Storybook** — component catalog with isolated stories per design-system
  primitive (`MoneyDisplay`, `RoundingModeSelect`, `EmptyState`, …).
- **Playwright E2E** — drive the BFF + UI together against `docker-compose`
  for smoke and money-path E2E.
- **Real OAuth2 via `auth-identity`** — Authorization Code + PKCE, refresh,
  RBAC claims, silent renewal (Phase 4 hardening).
- **Accessibility audit** — `axe-core` in CI, keyboard-navigation pass,
  contrast-ratio sweep across the theme.
- **Internationalisation (`i18next`)** — externalise copy, currency/date
  locale, KR/EN/MN at minimum.
- **Performance budgets** — Lighthouse CI thresholds + bundle-size guard.
- **Error tracking** — Sentry (or equivalent) wired to both front-ends with
  source maps.

---

## 12. Quick checklist before opening a PR

- [ ] New folders/files follow the naming rules in §1.
- [ ] Any data-fetching screen handles **loading / error / empty / success**.
- [ ] Money is rendered via `<MoneyDisplay>`; no inline formatting.
- [ ] Rounding-mode edits go through `<RoundingModeSelect>`.
- [ ] New forms have a Yup schema under `src/schemas/`.
- [ ] New API calls live in `src/api/client.ts` with types in `src/api/types.ts`.
- [ ] At least one test was added (component or page).
- [ ] `npm run lint`, `npm test`, `npm run build` all pass locally.
