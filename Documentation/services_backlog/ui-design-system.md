# ui-design-system  (frontend)

**Scope:** Shared React design system, money/margin inputs, a11y/i18n

**Owned WBS work-packages:** 12.1, 12.4, 12.5  ·  **Tickets:** 89  ·  **Est:** 57.2h

> Self-contained backlog for this service. Build in its own module against `shared-libs` contracts. Each ticket has a deliverable + acceptance checks.


## WBS 12.1 — Design system & component library
### 12.1-T01 — Define CSS custom-property colour tokens  _(30 min)_
**Context:** UX-11 §2.2 specifies 9 colour tokens as CSS custom properties. Both Admin System and Partner Portal share these tokens; they must be defined once in a shared design-system package. Tokens: --color-brand #1A56DB, --color-danger #E02424, --color-warning #D97706, --color-success #057A55, --color-neutral-900 #111928, --color-neutral-600 #4B5563, --color-neutral-200 #E5E7EB, --color-neutral-50 #F9FAFB, --color-surface #FFFFFF.
**Steps:** Create shared package src/design-system/tokens/colours.css; Define each token as :root { --color-brand: #1A56DB; ... } for all 9 tokens; Export a JS/TS object tokens.colours mirroring each token name and hex value for use in JS-side styling; Write a colours.test.ts that imports the object and asserts each hex value matches the spec exactly; Verify :root CSS file can be imported in both admin and portal app entry points without duplication
**Deliverable:** src/design-system/tokens/colours.css and tokens/colours.ts exporting 9 colour tokens
**Acceptance / logic checks:**
- All 9 token names match spec exactly (e.g. --color-brand, not --brand-color)
- Each hex value matches spec: --color-brand === #1A56DB, --color-danger === #E02424, --color-success === #057A55
- tokens.colours TS object keys match CSS custom-property names (strip -- prefix, camelCase)
- colours.test.ts passes with no assertion failures
- Importing colours.css twice does not produce duplicate :root declarations

### 12.1-T02 — Define CSS typography tokens and font-stack  _(25 min)_
**Context:** UX-11 §2.1 defines 6 typography roles: Page title 24px/600, Section heading 18px/600, Subsection 15px/600, Body 14px/400, Small/caption 12px/400, Monospace 13px/400. Font stack: Inter, -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif. Monospace: JetBrains Mono, Fira Code, monospace. Must be CSS custom properties and exported TS tokens.
**Steps:** Create src/design-system/tokens/typography.css with --font-size-page-title: 24px through --font-size-mono: 13px and corresponding --font-weight-* tokens; Add --font-family-base and --font-family-mono custom properties with the specified font stacks; Create typography.ts exporting TS enum/object for each role (size + weight); Add typography.test.ts asserting each size/weight value; Document role names in a brief comment above each token group
**Deliverable:** src/design-system/tokens/typography.css and typography.ts with 6 type roles
**Acceptance / logic checks:**
- --font-size-page-title === 24px, weight 600; --font-size-body === 14px, weight 400; --font-size-mono === 13px, weight 400
- --font-family-base includes Inter as first entry; --font-family-mono includes JetBrains Mono as first entry
- TS object exposes all 6 role keys; test assertions all pass
- No hardcoded font sizes exist outside the token file in this package
- CSS imports cleanly with no parse errors
**Depends on:** 12.1-T01

### 12.1-T03 — Define spacing scale tokens (8 px base unit)  _(20 min)_
**Context:** UX-11 §2.3: 8 px base unit. Spacing tokens: 4, 8, 12, 16, 24, 32, 48, 64 px. All padding, margin, and gap values in both applications must use these tokens only. Define as CSS custom properties and a TS constant map.
**Steps:** Create src/design-system/tokens/spacing.css with --space-1 through --space-8 mapping to 4, 8, 12, 16, 24, 32, 48, 64 px; Create spacing.ts exporting SPACING constant {s1:4, s2:8, s3:12, s4:16, s5:24, s6:32, s7:48, s8:64} (all numbers in px); Add spacing.test.ts asserting each numeric value; Document token-to-value mapping in a comment table inside the CSS file
**Deliverable:** src/design-system/tokens/spacing.css and spacing.ts
**Acceptance / logic checks:**
- 8 tokens defined: values exactly 4, 8, 12, 16, 24, 32, 48, 64 px in ascending order
- TS SPACING values match CSS: SPACING.s2 === 8, SPACING.s6 === 32
- No intermediate values (e.g. 10 px) exist in either file
- Test file passes with zero failures
- Token names follow consistent naming convention (--space-N, 1-indexed)
**Depends on:** 12.1-T01

### 12.1-T04 — Create design-system token index barrel file  _(25 min)_
**Context:** Consumers (Admin System, Partner Portal) should be able to import all tokens from a single entry point: import { colours, typography, SPACING } from '@gmepay/design-system/tokens'. The shared package lives at packages/design-system.
**Steps:** Create packages/design-system/src/tokens/index.ts that re-exports colours, typography, and SPACING from the respective token files; Create packages/design-system/package.json with name @gmepay/design-system, version 0.1.0, main pointing to dist/index.js, types to dist/index.d.ts; Add a minimal tsconfig.json for the package; Verify that importing from @gmepay/design-system/tokens resolves all three exports without error in a stub consumer test
**Deliverable:** packages/design-system/src/tokens/index.ts barrel plus package.json
**Acceptance / logic checks:**
- import { colours, typography, SPACING } from resolved path resolves all three symbols
- package.json name field === @gmepay/design-system
- All three token modules re-exported (no missing export)
- Barrel file contains no logic, only re-exports
- A stub import test (no jest required; tsc --noEmit) compiles without errors
**Depends on:** 12.1-T01, 12.1-T02, 12.1-T03

### 12.1-T05 — Build Button component (4 variants, 3 sizes, disabled state)  _(45 min)_
**Context:** UX-11 §2.4 defines 4 button variants: Primary (filled --color-brand, white text), Secondary (outlined --color-brand, brand text), Danger (filled --color-danger, white text, requires confirmation modal per caller), Ghost (no border, neutral text). Sizes: sm 28px height, md 36px height (default), lg 44px height (modals only). Disabled: 40% opacity, cursor not-allowed, aria-disabled=true.
**Steps:** Create src/design-system/components/Button/Button.tsx with props: variant (primary|secondary|danger|ghost), size (sm|md|lg), disabled, onClick, children; Apply colour tokens via CSS modules or styled-components using --color-brand and --color-danger; no hardcoded hex; Implement disabled state: opacity 0.4, cursor not-allowed, aria-disabled=true, onClick suppressed; Export Button from src/design-system/components/index.ts; Add Button.stories.tsx (or equivalent) showing all 4 variants at all 3 sizes
**Deliverable:** src/design-system/components/Button/Button.tsx with CSS and story
**Acceptance / logic checks:**
- Primary button background equals var(--color-brand); text is white
- Danger button background equals var(--color-danger); text is white
- Disabled button has opacity 0.4 and aria-disabled=true; click handler is not called when disabled
- sm/md/lg heights are exactly 28px/36px/44px (verify with a DOM height check in unit test or story)
- Ghost variant has no border and text colour is --color-neutral-600
**Depends on:** 12.1-T04

### 12.1-T06 — Build form control components: TextInput and NumberInput  _(45 min)_
**Context:** UX-11 §2.5: TextInput is 36px height, 1px border neutral-200, focus ring 2px brand. NumberInput is right-aligned value with optional currency prefix/suffix label. All controls have associated label element with for attribute; required fields show asterisk. Focus ring uses --color-brand.
**Steps:** Create TextInput.tsx with props: id, label, required, value, onChange, error (string|undefined); render label with for=id, asterisk if required; Create NumberInput.tsx extending TextInput with rightAlign text, plus optional currencyPrefix/currencySuffix props rendered as span siblings; Apply CSS: height 36px, border 1px solid var(--color-neutral-200), focus outline 2px solid var(--color-brand), text-align right for NumberInput; Show error string below input in 12px --color-danger text when error prop is set; Add unit tests: renders label, renders asterisk when required=true, renders error text, suppresses error span when error is undefined
**Deliverable:** TextInput.tsx, NumberInput.tsx with CSS and unit tests
**Acceptance / logic checks:**
- TextInput renders label element with for attribute matching id prop
- Required TextInput renders asterisk (*) adjacent to label
- Error prop non-empty: error text visible below input in --color-danger; error prop undefined: no error element in DOM
- NumberInput input text is right-aligned
- NumberInput with currencyPrefix=USD renders USD label to the left of the input
**Depends on:** 12.1-T04

### 12.1-T07 — Build Select/Dropdown and Toggle form components  _(40 min)_
**Context:** UX-11 §2.5: Select uses native <select> with custom styling; searchable for lists > 10 items. Toggle/switch is for boolean flags (e.g. partner active/inactive). Both need associated label with for attribute. Required fields show asterisk.
**Steps:** Create Select.tsx with props: id, label, required, options (Array<{value,label}>), value, onChange, searchable, error; render native select, add search input above when searchable=true and options.length > 10; Create Toggle.tsx with props: id, label, checked, onChange; render styled checkbox/role=switch with visual pill indicator; Apply CSS for custom styling of select (hide native arrow, add custom chevron); Add unit tests: Select renders all option elements, Toggle emits onChange with negated value on click, Select shows search input only when searchable=true and options > 10
**Deliverable:** Select.tsx, Toggle.tsx with CSS and unit tests
**Acceptance / logic checks:**
- Select renders label with for attribute matching id
- Select with 11+ options and searchable=true renders a search input; with 10 options does not
- Toggle sets aria-checked=true when checked=true, aria-checked=false otherwise
- Toggle onChange called with true when unchecked and clicked; false when checked and clicked
- Both components render asterisk (*) when required=true
**Depends on:** 12.1-T04

### 12.1-T08 — Build DatePicker component (single date and range variants)  _(55 min)_
**Context:** UX-11 §2.5: DatePicker has single-date and date-range variants. ISO calendar (Monday first). Date display format throughout the app is YYYY-MM-DD (ISO 8601) per UX-11 §8.2. Date range validation (from <= to, range <= 90 days) is a form-level rule defined in §7.2.
**Steps:** Create DatePicker.tsx with props: mode (single|range), value (string|{from:string,to:string}), onChange, error; Render a calendar popover anchored to an input; week starts on Monday; For range mode expose from/to inputs; emit {from, to} on each selection; Display selected dates as YYYY-MM-DD in the input fields; Add DatePicker.test.tsx: single mode emits ISO string on day click, range mode emits {from, to} object, calendar week starts on Monday (first column header is Mon)
**Deliverable:** DatePicker.tsx with CSS and unit tests
**Acceptance / logic checks:**
- Single mode: clicking a date emits YYYY-MM-DD formatted string (e.g. 2026-06-05)
- Range mode: clicking start then end emits {from: YYYY-MM-DD, to: YYYY-MM-DD}
- First column of the calendar grid is labeled Mon (not Sun)
- Selected date displayed in input matches YYYY-MM-DD pattern
- Error prop renders error text below the input
**Depends on:** 12.1-T04

### 12.1-T09 — Build StatusBadge component for transaction statuses  _(35 min)_
**Context:** UX-11 §2.7 defines 6 transaction status badges as inline pill components: font 12px bold, border-radius 4px, horizontal padding 6px. APPROVED: bg #DEF7EC text #057A55; DECLINED: bg #FDE8E8 text #E02424; PENDING: bg #FEF3C7 text #D97706; CANCELLED: bg #E5E7EB text #4B5563; REFUNDED: bg #E0F2FE text #0369A1; UNCERTAIN: bg #FEF9C3 text #92400E. aria-label must include full status text for screen readers (not just colour).
**Steps:** Create StatusBadge.tsx with prop: status (APPROVED|DECLINED|PENDING|CANCELLED|REFUNDED|UNCERTAIN); Map each status to its background and text colour using a const lookup table (no inline conditionals); Apply CSS: font-size 12px, font-weight bold, border-radius 4px, padding 0 6px; Set aria-label={status} on the badge span so screen readers announce the text; Add unit tests covering all 6 statuses: correct bg/text colour applied, aria-label equals status string
**Deliverable:** StatusBadge.tsx (transaction) with CSS and unit tests for all 6 statuses
**Acceptance / logic checks:**
- APPROVED badge: background-color #DEF7EC, color #057A55
- DECLINED badge: background-color #FDE8E8, color #E02424
- PENDING badge: background-color #FEF3C7, color #D97706
- Each badge has aria-label equal to the status string (e.g. aria-label=APPROVED)
- Rendering an unknown status value (e.g. FOO) does not throw; falls back to CANCELLED styling or renders a neutral badge
**Depends on:** 12.1-T04

### 12.1-T10 — Build StatusBadge variants for settlement statement statuses  _(25 min)_
**Context:** UX-11 §2.7 defines 3 settlement statement status badges with the same pill spec (12px bold, border-radius 4px, 6px horizontal padding): DRAFT bg #E5E7EB text #4B5563; CONFIRMED bg #DEF7EC text #057A55; DISPUTED bg #FDE8E8 text #E02424. These share the same component but use a separate prop domain.
**Steps:** Extend StatusBadge.tsx (or create SettlementBadge.tsx) to accept a statementStatus prop: DRAFT|CONFIRMED|DISPUTED; Add colour mapping for 3 settlement statuses separate from transaction status mapping; Ensure same pill CSS applies (12px, bold, border-radius 4px, 6px padding); Add unit tests for all 3 settlement statuses verifying colour values and aria-label; Export SettlementBadge (or the combined StatusBadge) from component index
**Deliverable:** SettlementBadge component (or StatusBadge with statementStatus prop) with unit tests
**Acceptance / logic checks:**
- DRAFT badge: background-color #E5E7EB, color #4B5563
- CONFIRMED badge: background-color #DEF7EC, color #057A55
- DISPUTED badge: background-color #FDE8E8, color #E02424
- aria-label equals statementStatus string for all 3 variants
- Component does not share state or colour map with transaction StatusBadge (separate lookup table)
**Depends on:** 12.1-T09

### 12.1-T11 — Implement formatMoney utility function  _(40 min)_
**Context:** UX-11 §2.8: All monetary values formatted as {CURRENCY_CODE} {amount}. Currency code always precedes amount. Thousands separator: comma. Decimal separator: period. KRW: 0 decimal places. All other currencies: 2 decimal places. Negative amounts prefix with Unicode minus U+2212 (not hyphen-minus). Zero: USD 0.00 (not blank). Exchange rates: 6 significant figures (e.g. 1350.42, 0.000740). Store amounts as Decimal (string or big-decimal library) to avoid floating-point errors.
**Steps:** Create src/design-system/utils/formatMoney.ts exporting formatMoney(amount: string|number, currency: string): string; Implement KRW branch: Math.round to integer, format with comma thousands, no decimal; Implement default branch: format to 2 decimal places with comma thousands; Prefix Unicode minus (\u2212) for negative values; positive amounts have no sign prefix; Export formatRate(rate: string|number): string that formats to 6 significant figures with comma thousands
**Deliverable:** src/design-system/utils/formatMoney.ts with formatMoney and formatRate exports
**Acceptance / logic checks:**
- formatMoney(10234.56, USD) === USD 10,234.56
- formatMoney(45000, KRW) === KRW 45,000 (no decimals)
- formatMoney(-33.83, USD) === \u2212USD 33.83 (Unicode minus, colour applied by caller)
- formatMoney(0, USD) === USD 0.00 (not blank or dash)
- formatRate(1350.4200) === 1,350.42 (6 sig figs); formatRate(0.00074) === 0.000740
**Depends on:** 12.1-T04

### 12.1-T12 — Unit tests for formatMoney edge cases  _(30 min)_
**Context:** formatMoney (12.1-T11) must handle: KRW zero-decimal, large KRW amounts, negative USD, zero USD, fractional KRW input that must round, MNT at 2dp, USD with trailing zero (USD 33.80 not USD 33.8), and formatRate for both large (1350.42) and small (0.000740) rates. Rounding must never produce fractional KRW.
**Steps:** Create src/design-system/utils/formatMoney.test.ts; Write test vectors: KRW 45000 -> KRW 45,000; KRW 0 -> KRW 0; USD 0 -> USD 0.00; USD -33.83 -> minus-sign USD 33.83; USD 33.80 -> USD 33.80 (trailing zero); MNT 123456.78 -> MNT 123,456.78; KRW 44999.9 -> KRW 45,000 (rounded, not 44,999); Write formatRate vectors: 1350.42 -> 1,350.42; 0.00074 -> 0.000740; 1000000 -> 1,000,000; 0.0000001 -> 0.000000100 (6 sig figs); Confirm all tests pass against the implementation from 12.1-T11
**Deliverable:** formatMoney.test.ts with >= 10 named test cases covering all edge cases
**Acceptance / logic checks:**
- KRW 44999.9 rounds to KRW 45,000 (no fractional KRW)
- USD 33.80 renders as USD 33.80 not USD 33.8
- Negative USD -33.83 renders with Unicode minus U+2212 not ASCII hyphen-minus (U+002D)
- formatRate(0.00074) renders 0.000740 (padded to 6 sig figs)
- All 10+ test cases pass with zero failures
**Depends on:** 12.1-T11

### 12.1-T13 — Build MoneyDisplay component using formatMoney  _(30 min)_
**Context:** UX-11 §1.2 and §2.8: money amounts always displayed with currency code and 2 decimal places (KRW: 0 dp). Negative amounts are shown in --color-danger. Zero is USD 0.00. The MoneyDisplay component wraps formatMoney and handles colour; callers pass a raw numeric amount and currency code.
**Steps:** Create MoneyDisplay.tsx with props: amount (number|string), currency (string), className; Call formatMoney(amount, currency) internally to produce display string; Apply --color-danger style when amount is negative; Render output in a <span> with data-testid=money-display for test targeting; Add unit tests: positive USD renders without danger colour, negative USD renders with danger colour, KRW renders without decimals, zero USD renders USD 0.00
**Deliverable:** MoneyDisplay.tsx component with unit tests
**Acceptance / logic checks:**
- MoneyDisplay amount=10234.56 currency=USD renders USD 10,234.56
- MoneyDisplay amount=-33.83 currency=USD has color var(--color-danger)
- MoneyDisplay amount=45000 currency=KRW renders KRW 45,000 (no decimals)
- MoneyDisplay amount=0 currency=USD renders USD 0.00
- MoneyDisplay amount=33.80 currency=USD renders USD 33.80 (trailing zero preserved)
**Depends on:** 12.1-T11

### 12.1-T14 — Build DataTable component with spec-compliant styling  _(55 min)_
**Context:** UX-11 §2.6: header row 12px uppercase label, neutral-600, neutral-50 bg, 1px bottom border neutral-200. Body rows 14px neutral-900, 44px default row height (compact 36px), alternating neutral-50/white bg. Numeric/currency columns right-aligned, text left-aligned, status badges centre-aligned. Sticky header on scroll. Hover state: neutral-50 on hovered row. Sortable columns show sort icons; active sort highlighted. Max 6 columns per table.
**Steps:** Create DataTable.tsx with props: columns (Array<{key,label,align:left|right|center,sortable?}>), rows (Array<Record<string,ReactNode>>), compact (boolean, default false), onSort; Render thead with 12px uppercase labels in neutral-600 on neutral-50; 1px bottom border; Render tbody with alternating row bg (neutral-50/white), 44px height (compact: 36px), hover neutral-50; Enforce max 6 columns: throw console.warn (not error) if columns.length > 6; Sticky thead via position:sticky top:0; add sort icon (inactive/asc/desc) on sortable columns
**Deliverable:** DataTable.tsx with CSS and unit tests
**Acceptance / logic checks:**
- Header cells rendered with font-size 12px, text-transform uppercase, background var(--color-neutral-50)
- Body rows alternate between white and var(--color-neutral-50)
- Row height is 44px in default mode and 36px in compact mode
- Column with align=right has text-align:right; align=center has text-align:center
- console.warn (not error) is emitted when more than 6 columns are passed
**Depends on:** 12.1-T04

### 12.1-T15 — Build Pagination component for data tables  _(35 min)_
**Context:** UX-11 §4.5 and §5.2 show pagination: Showing 1-50 of 1,234  [< Prev] 1 [Next >] [50 v]. Pagination is used in Transaction List (Admin and Portal). Page size selector offers 50 (default), 100, 200. Next/Prev buttons disabled at boundaries.
**Steps:** Create Pagination.tsx with props: totalItems (number), pageSize (number), currentPage (number), onPageChange, onPageSizeChange; Render label: Showing {start}-{end} of {totalItems.toLocaleString()}; Render Prev button (disabled when currentPage===1), current page number, Next button (disabled when on last page); Render page-size select with options [50,100,200]; Add unit tests: Prev disabled on page 1, Next disabled on last page, label reflects correct range (page 2 of 50-per-page for 1234 items shows 51-100 of 1,234)
**Deliverable:** Pagination.tsx with unit tests
**Acceptance / logic checks:**
- On page 1 totalItems=1234 pageSize=50: label is Showing 1-50 of 1,234
- On page 2 pageSize=50: label is Showing 51-100 of 1,234
- Prev button has disabled attribute on page 1
- Next button has disabled attribute when currentPage * pageSize >= totalItems
- onPageChange called with 2 when Next clicked from page 1
**Depends on:** 12.1-T04

### 12.1-T16 — Build Toast notification component  _(50 min)_
**Context:** UX-11 §2.10: Toasts positioned top-right 16px from viewport edge. Auto-dismiss 5s (success/info), persistent until dismissed (error). Max 3 simultaneous toasts, stacked. Variants: success (green left border), error (red left border), warning (amber left border), info (blue left border). Use --color-success, --color-danger, --color-warning, --color-brand for borders.
**Steps:** Create Toast.tsx with props: id, variant (success|error|warning|info), message, onDismiss; Create ToastContainer.tsx that renders up to 3 toasts stacked, positioned fixed top-right 16px; Implement auto-dismiss: start setTimeout(5000) for success/info; do not start timer for error variant; Render dismiss (X) button on each toast; Add unit tests: error toast remains after 5s (no auto-dismiss); success toast removed after 5s; 4th toast added when container has 3 causes oldest to be removed or rejected
**Deliverable:** Toast.tsx and ToastContainer.tsx with unit tests
**Acceptance / logic checks:**
- Success/info toasts call onDismiss after 5000ms (use jest fake timers)
- Error toast does not auto-dismiss after 5000ms
- Max 3 toasts visible simultaneously; adding a 4th removes the oldest
- Toast container positioned fixed top-right at 16px from each edge
- Each variant renders its correct left border colour (success: var(--color-success))
**Depends on:** 12.1-T04

### 12.1-T17 — Build Modal component with focus trap and overlay  _(55 min)_
**Context:** UX-11 §2.10: Confirm dialog max-width 560px, review/detail modals 720px. Overlay: 50% black scrim; click-outside closes non-destructive modals. Always has explicit Close (X) button in header. Focus trap: keyboard focus cycles within open modal. WCAG 2.1: role=dialog, aria-modal=true, focus returns to trigger on close.
**Steps:** Create Modal.tsx with props: isOpen, onClose, title, maxWidth (560|720), preventClickOutsideClose (for destructive modals), children; Render backdrop div with rgba(0,0,0,0.5); close on click if preventClickOutsideClose=false; Render dialog with role=dialog, aria-modal=true, aria-labelledby pointing to title element; Implement focus trap: on mount, focus first focusable child; Tab cycles within modal; Shift+Tab reverses; Render Close (X) button in header that calls onClose; add Escape key handler
**Deliverable:** Modal.tsx with focus-trap logic and unit/interaction tests
**Acceptance / logic checks:**
- Modal renders with role=dialog and aria-modal=true
- Click outside the modal panel calls onClose when preventClickOutsideClose=false; does not call when true
- Escape key press calls onClose
- First focusable element inside modal receives focus on open
- Tab key from last focusable element cycles back to first (focus trap)
**Depends on:** 12.1-T04

### 12.1-T18 — Build ConfirmationModal component (two-step pattern)  _(40 min)_
**Context:** UX-11 §2.11: Two-step confirmation for financial/destructive actions. Step 1 Review: modal shows before/after values summary, primary button labelled with specific action. Step 2 Result: toast confirms success or error modal with reason. Dangerous actions (delete, void, refund) use --color-danger primary button. Modal heading explicitly names the entity.
**Steps:** Create ConfirmationModal.tsx with props: title, summary (Array<{field, before, to}>), actionLabel, variant (default|danger), onConfirm, onCancel, isOpen; Render summary table with Field / Before / After columns; Render primary action button with actionLabel; variant=danger uses --color-danger filled style; Render Cancel (secondary) button; Add unit tests: renders before/after values in table, danger variant primary button has danger style, onConfirm called on primary click, onCancel called on cancel click
**Deliverable:** ConfirmationModal.tsx with unit tests
**Acceptance / logic checks:**
- Summary table renders all field/before/to rows passed via summary prop
- variant=danger: primary button background is var(--color-danger)
- variant=default: primary button background is var(--color-brand)
- onConfirm called exactly once when primary button clicked
- onCancel called when Cancel button clicked; onConfirm not called
**Depends on:** 12.1-T17

### 12.1-T19 — Build EmptyState component  _(25 min)_
**Context:** UX-11 §2.9 defines the empty state: centred icon (search or document), heading No {entity} found, sub-line Try adjusting your filters. Used in all list/table views when no results are returned.
**Steps:** Create EmptyState.tsx with props: icon (search|document), heading, subtext; Render centred layout with icon component placeholder, heading in body font, subtext in small/caption font; Export from component index; Add unit tests: renders heading text, renders subtext, renders correct icon type per prop
**Deliverable:** EmptyState.tsx with unit tests
**Acceptance / logic checks:**
- Renders heading prop as text inside the component
- Renders subtext prop as secondary paragraph below heading
- icon=search renders a search icon (or placeholder); icon=document renders document icon
- Component is horizontally and vertically centred (display flex, justify/align center on parent)
- Unit tests pass for both icon variants with correct accessible alt/aria-label
**Depends on:** 12.1-T04

### 12.1-T20 — Build LoadingState skeleton shimmer component  _(40 min)_
**Context:** UX-11 §2.9: Loading state uses skeleton shimmer (animated grey bars matching table row structure). Minimum display duration 200ms to avoid flash of loading state. Used while data fetch is in flight for all tables.
**Steps:** Create SkeletonRow.tsx rendering a row of grey animated bars; props: columns (number), rowHeight (36|44); Create SkeletonTable.tsx with props: rows (number, default 5), columns (number), compact (boolean) that renders N SkeletonRows; Implement shimmer animation via CSS @keyframes (background gradient sweep); Add minDisplayMs=200 logic: accept a visible prop; do not unmount until 200ms has elapsed since visible became false; Add unit tests: renders correct row count, renders correct column count, shimmer CSS class applied
**Deliverable:** SkeletonRow.tsx, SkeletonTable.tsx with CSS and unit tests
**Acceptance / logic checks:**
- SkeletonTable rows=5 columns=4 renders exactly 20 skeleton bar cells
- Compact=true uses 36px row height; compact=false uses 44px
- Shimmer animation class is applied to each bar element
- Component does not unmount before 200ms even if visible prop turns false immediately
- Unit tests for row and column count assertions pass
**Depends on:** 12.1-T04

### 12.1-T21 — Build ErrorState component with Retry button  _(25 min)_
**Context:** UX-11 §2.9: Error state shows warning triangle icon, Could not load {entity}, Error: {short error code}, and a Retry button. The error code must be human-readable (not a stack trace). The Retry button triggers the same fetch.
**Steps:** Create ErrorState.tsx with props: entity (string), errorCode (string), onRetry; Render warning icon, heading Could not load {entity}, subtext Error: {errorCode}; Render Retry button (secondary variant from Button component); Add unit tests: renders entity name in heading, renders errorCode in subtext, onRetry called when Retry clicked
**Deliverable:** ErrorState.tsx with unit tests
**Acceptance / logic checks:**
- Heading contains entity prop text (e.g. Could not load transactions)
- Subtext contains errorCode (e.g. Error: FETCH_FAILED)
- Retry button click calls onRetry callback exactly once
- errorCode displayed as human-readable string; raw stack traces not expected in prop
- Component renders in error colour context (warning icon visible)
**Depends on:** 12.1-T04, 12.1-T05

### 12.1-T22 — Build Breadcrumb component  _(25 min)_
**Context:** UX-11 §3.1: Breadcrumb renders 1 row above page title. Format: Module / Sub-section / Current page. Links to parent levels. Omitted on top-level pages (Dashboard). Uses 14px body text; last item is non-clickable current page.
**Steps:** Create Breadcrumb.tsx with props: items (Array<{label: string, href?: string}>); last item is always non-linked; Render items separated by / delimiter; Last item rendered as non-link span; preceding items as anchor tags (<a href>); Add unit tests: single item renders no link, multiple items last item has no href, delimiter present between items
**Deliverable:** Breadcrumb.tsx with unit tests
**Acceptance / logic checks:**
- Single item [{label: Dashboard}]: no anchor rendered, no delimiter
- Two items [{label:Transactions, href:/transactions}, {label: HUB-001}]: first item is anchor, second is span with no href
- Delimiter / rendered between each pair of items
- Breadcrumb renders nothing (null) when items is empty array
- Font size inherits body 14px (no override needed; test class/role is present)
**Depends on:** 12.1-T04

### 12.1-T23 — Build LeftNavSidebar component (Admin System shell)  _(50 min)_
**Context:** UX-11 §3.1: Left sidebar 240px wide, fixed. Nav items: Dashboard, Schemes, Partners, Transactions, Settlement, FX Rates, Audit Log, Settings. Active item has brand-colour left border and brand background tint. Hover shows tooltip with item name. Collapsible to 56px icon-only view at < 1280px. Uses --color-brand and --color-neutral tokens.
**Steps:** Create LeftNavSidebar.tsx with props: collapsed (boolean), activeItem (string), onToggle, items (Array<{key,label,icon,href,subItems?}>); Render 240px sidebar (or 56px when collapsed=true); transition via CSS width animation; Active item: 3px left border var(--color-brand), background tint (brand at 8% opacity); Hover state: neutral-50 background, tooltip (title attribute) with item label; Add unit tests: collapsed width 56px, expanded 240px, active item has correct border class
**Deliverable:** LeftNavSidebar.tsx with CSS and unit tests
**Acceptance / logic checks:**
- Collapsed sidebar width is 56px; expanded is 240px
- Active item element has CSS with border-left 3px solid var(--color-brand)
- Inactive item does not have brand border
- Each nav item has a title attribute equal to its label (for tooltip on hover)
- onToggle called when hamburger/collapse button is clicked
**Depends on:** 12.1-T04

### 12.1-T24 — Build TopBar component shared by Admin and Partner Portal  _(35 min)_
**Context:** UX-11 §3.1 and §3.2: Admin top bar: 56px height, hamburger left, app title centre, user name + role badge + logout right. Partner Portal top bar: Partner name prominent, notification bell with unread badge count, logout. Both use --color-surface background and --color-neutral-900 text.
**Steps:** Create TopBar.tsx with props: appTitle, userName, userRole (Admin only), partnerName (Portal only), notificationCount (Portal only), onMenuToggle, onLogout; Render 56px bar with left hamburger (calls onMenuToggle), centre title, right user info + logout; When notificationCount > 0, render bell icon with numeric badge; Add aria-label=Navigation toggle to hamburger button; Add unit tests: renders appTitle, renders userName, notification badge shows count, no badge when notificationCount=0
**Deliverable:** TopBar.tsx with CSS and unit tests
**Acceptance / logic checks:**
- Renders appTitle text in centre region
- Renders logout button that calls onLogout on click
- Notification badge visible and shows count 3 when notificationCount=3
- Notification badge not rendered when notificationCount=0
- TopBar height is 56px
**Depends on:** 12.1-T04

### 12.1-T25 — Build AppShell layout component for Admin System  _(40 min)_
**Context:** UX-11 §3.1: Admin System shell = LeftNavSidebar + TopBar + content area. Content area fills remaining width, max-width 1400px centred, 32px padding all sides. Breadcrumb rendered 1 row above page title inside content area.
**Steps:** Create AdminShell.tsx with props: activeNavItem, breadcrumbItems, pageTitle, children; Compose LeftNavSidebar + TopBar + main content area; Content area: max-width 1400px, margin 0 auto, padding 32px; Render Breadcrumb above page title h1 inside content; Wire sidebar collapse state (useState) and pass to LeftNavSidebar; adjust content area margin-left on collapse
**Deliverable:** AdminShell.tsx layout component
**Acceptance / logic checks:**
- Content area has max-width 1400px and padding 32px on all sides
- Breadcrumb renders above h1 page title when breadcrumbItems prop is provided
- Breadcrumb omitted when breadcrumbItems is empty (top-level page)
- Sidebar collapse toggles content area margin-left between 240px and 56px
- pageTitle rendered as h1 inside content area
**Depends on:** 12.1-T22, 12.1-T23, 12.1-T24

### 12.1-T26 — Build AppShell layout component for Partner Portal  _(35 min)_
**Context:** UX-11 §3.2: Partner Portal shell = left nav 200px with 5 items (Dashboard, Transactions, Balance, Statements, API & Creds), TopBar with partner name + notification bell, content area 32px padding max-width 1200px. No sub-nav items in Phase 1.
**Steps:** Create PartnerShell.tsx with props: partnerName, notificationCount, activeNavItem, breadcrumbItems, pageTitle, children; Render 200px left nav with 5 items; active item uses same brand border as Admin sidebar; Compose with TopBar (partner variant) and content area max-width 1200px, padding 32px; Add unit tests: renders correct 5 nav items, content area max-width 1200px (inspect inline style or CSS class)
**Deliverable:** PartnerShell.tsx layout component
**Acceptance / logic checks:**
- Left nav renders exactly 5 items: Dashboard, Transactions, Balance, Statements, API & Creds
- Content area max-width is 1200px (not 1400px used in Admin Shell)
- TopBar receives partnerName and notificationCount props
- Active nav item has brand-colour left border
- pageTitle rendered as h1 inside content area
**Depends on:** 12.1-T22, 12.1-T24

### 12.1-T27 — Implement i18n string externalisation setup  _(55 min)_
**Context:** UX-11 §8.2 and §9 Assumption A-05: All UI string literals must be externalised to i18n key files from the first commit so Korean can be added in Phase 2 without code changes. Phase 1 ships English only. Use a standard i18n library (e.g. react-i18next). All component strings (labels, error messages, aria-labels) must use translation keys, never hardcoded English.
**Steps:** Install and configure react-i18next (or equivalent) in the design-system package; Create src/design-system/i18n/en.json with all string keys used in components built so far (buttons, badges, empty/error states, nav labels, validation messages); Wrap the design-system in an I18nProvider that defaults to en locale; Replace all hardcoded English strings in Button, StatusBadge, EmptyState, ErrorState, LeftNavSidebar with t(key) calls; Add a test asserting that all keys used in components exist in en.json (no missing key warnings)
**Deliverable:** en.json translation file and i18n wiring across all design-system components
**Acceptance / logic checks:**
- en.json contains keys for all UI strings (status badge labels, button labels, empty state messages, error messages, nav items)
- No hardcoded English string literals remain in .tsx component files (strings come from t() calls)
- Components render correct English text via the i18n layer
- Missing translation key does not cause component crash; falls back to key name
- Adding a new ko.json with Korean strings and switching locale renders Korean text without code changes
**Depends on:** 12.1-T05, 12.1-T09, 12.1-T10, 12.1-T19, 12.1-T21, 12.1-T23

### 12.1-T28 — Implement responsive breakpoints and sidebar collapse behaviour  _(50 min)_
**Context:** UX-11 §8.3: Desktop >= 1440px: full nav + content. Desktop min 1280-1439px: Admin sidebar collapses to icons (56px). Tablet landscape 1024-1279px: sidebar hidden, hamburger shows overlay. Below 1024px: not supported in Phase 1. Use CSS media queries in the shared design system; do not use JS window.resize for breakpoint logic.
**Steps:** Add CSS media query breakpoints as custom properties or SCSS variables: --bp-desktop-min: 1280px, --bp-tablet: 1024px; In AdminShell CSS: at 1280-1439px, set LeftNavSidebar collapsed=true automatically via CSS class on the shell; At 1024-1279px, hide sidebar (display:none) and render hamburger menu that shows sidebar as overlay (position:fixed z-index:100); Add a viewport test (using jsdom resize or Storybook viewport) confirming sidebar state at each breakpoint; Add PartnerShell with same breakpoint behaviour
**Deliverable:** CSS breakpoint rules in AdminShell.css and PartnerShell.css with responsive sidebar
**Acceptance / logic checks:**
- At viewport width 1300px sidebar renders in icon-only (56px) mode
- At viewport width 1100px sidebar is hidden; hamburger button is visible
- At viewport width 1440px sidebar renders in full 240px mode
- Breakpoints implemented via CSS @media queries, not JS window.innerWidth checks
- No horizontal scroll bar appears at 1280px minimum supported width
**Depends on:** 12.1-T25, 12.1-T26

### 12.1-T29 — Build form validation hook for margin and service-charge fields  _(45 min)_
**Context:** UX-11 §7.2 and §7.3: m_a: number 0.00-99.99, 2dp max. m_b: number 0.00-99.99, 2dp max. m_a + m_b >= 2.00% for cross-border rules; 0 allowed for same-currency (detected when all 4 currencies are equal). service_charge >= 0. Validation fires on blur; cross-field validation fires on submit. Error messages are exact strings from spec. Same-currency mode removes the 2% combined constraint and shows a blue info banner.
**Steps:** Create useRuleFormValidation hook with inputs: m_a, m_b, serviceCharge, isSameCurrency (boolean); Validate m_a/m_b on blur: must be number 0.00-99.99; error message: Enter a percentage between 0.00 and 99.99; Validate combined margin on submit: if !isSameCurrency and m_a+m_b < 2.00 set combinedError: Combined margin must be at least 2.00% for cross-border rules; Validate serviceCharge: >= 0; error: Service charge must be zero or positive; Return {errors, validate, validateField}; validate() returns boolean isValid
**Deliverable:** src/design-system/hooks/useRuleFormValidation.ts with unit tests
**Acceptance / logic checks:**
- m_a=1.50, m_b=0.40, isSameCurrency=false: combinedError set (1.90 < 2.00)
- m_a=1.00, m_b=1.00, isSameCurrency=false: no combinedError (exactly 2.00 is valid)
- m_a=0, m_b=0, isSameCurrency=true: no combinedError (same-currency exemption)
- m_a=100.00: fieldError set (exceeds 99.99 max)
- serviceCharge=-0.01: error set; serviceCharge=0.00: no error
**Depends on:** 12.1-T04

### 12.1-T30 — Build form validation for partner and filter fields  _(40 min)_
**Context:** UX-11 §7.2: rate_quote_ttl_seconds integer 60-1800, error TTL must be between 60 and 1,800 seconds. low_balance_threshold > 0 USD, error Threshold must be greater than zero. webhook_url valid HTTPS URL, error Webhook URL must be a valid HTTPS address. Date range filter: from <= to, range <= 90 days, error Date range cannot exceed 90 days. FX rate: number > 0, <= 6 decimal places.
**Steps:** Create usePartnerFormValidation hook: validates ttl (integer 60-1800), threshold (>0), webhookUrl (valid https://), fxRate (>0, <=6dp); Create useDateRangeValidation hook: from <= to AND dateDiff(to, from) <= 90 days; Return field-level errors and a validate() function returning isValid; Add unit tests for all fields covering boundary values
**Deliverable:** usePartnerFormValidation.ts and useDateRangeValidation.ts with unit tests
**Acceptance / logic checks:**
- ttl=59: error set; ttl=60: no error; ttl=1800: no error; ttl=1801: error set
- threshold=0: error set; threshold=0.01: no error
- webhookUrl=http://example.com (not https): error set; https://example.com: no error
- dateRange from=2026-01-01 to=2026-04-01 (90 days): no error; to=2026-04-02 (91 days): error set
- fxRate=0: error; fxRate=1350.420000 (6dp): no error; fxRate=1350.4200001 (7dp): error
**Depends on:** 12.1-T04

### 12.1-T31 — Build inline validation error display for Rule form  _(45 min)_
**Context:** UX-11 §7.1: Validation fires on field blur (not keystroke) for text/number inputs; cross-field rules on submit. Error messages appear inline directly below the field in --color-danger 12px text. Form submission blocked while any field has error. Submit button shows spinner during async validation.
**Steps:** Create RuleFormMarginSection.tsx using TextInput components and useRuleFormValidation hook; Bind validateField to each input onBlur handler; bind validate to form onSubmit; Display combinedMarginError below both m_a and m_b fields when set; Show inline info banner (blue, not danger) when isSameCurrency=true: This is a same-currency rule. The USD pool is bypassed. Minimum combined margin is not required.; Add integration tests: blur m_a with value 150 -> error; submit with m_a=1.0 m_b=0.5 cross-border -> combined error; submit with isSameCurrency -> no combined error
**Deliverable:** RuleFormMarginSection.tsx with validation integration and tests
**Acceptance / logic checks:**
- Blurring m_a input with value 150 shows Enter a percentage between 0.00 and 99.99 below that field
- Submitting cross-border rule with m_a=1.00 m_b=0.50 shows Combined margin must be at least 2.00% for cross-border rules
- isSameCurrency=true: no combined error shown and blue info banner visible
- isSameCurrency=false: info banner hidden
- Submit button disabled while any field error exists
**Depends on:** 12.1-T06, 12.1-T29

### 12.1-T32 — Build RateDisplay component for exchange rate formatting  _(25 min)_
**Context:** UX-11 §2.8 and transaction detail wireframe §4.6: Exchange rates displayed to 6 significant figures. Example: 1,350.42 (KRW/USD), 0.000740 (USD/KRW). Rates shown in monospace font (JetBrains Mono stack, 13px). The offer_rate and cross_rate fields use this component. A RateDisplay renders {rate} with pair label optionally appended.
**Steps:** Create RateDisplay.tsx with props: rate (number|string), pairLabel (optional string); Call formatRate(rate) from formatMoney utils to get 6-sig-fig formatted string; Apply monospace font stack: JetBrains Mono, Fira Code, monospace at 13px (from typography tokens); Render {formattedRate} {pairLabel} if pairLabel provided, else just rate; Add unit tests: formatRate called with 1350.42 -> 1,350.42; with 0.00074 -> 0.000740; monospace font applied
**Deliverable:** RateDisplay.tsx with unit tests
**Acceptance / logic checks:**
- RateDisplay rate=1350.42 pairLabel=KRW/USD renders 1,350.42 KRW/USD
- RateDisplay rate=0.00074 renders 0.000740
- Component uses monospace font-family (verifiable via CSS class or inline style)
- font-size is 13px
- RateDisplay rate=1000000 renders 1,000,000 (6 sig figs with comma thousands)
**Depends on:** 12.1-T11, 12.1-T02

### 12.1-T33 — Build PrefundingStatusBadge and balance indicator  _(30 min)_
**Context:** UX-11 §4.1 and §5.1: Prefunding balance shows OK (green dot) when balance >= threshold, LOW (amber warning icon + text) when balance < threshold. Admin dashboard and partner portal both show this. OK uses --color-success, LOW uses --color-warning. The LOW alert is per UX-11 §2.7 spec reference and PRD-07 §3.4.
**Steps:** Create PrefundingStatusBadge.tsx with props: balance (number), threshold (number), currency (default USD); Render OK indicator (green circle + OK text, --color-success) when balance >= threshold; Render LOW indicator (amber warning + LOW text, --color-warning) when balance < threshold; Display formatted balance using MoneyDisplay component; Add unit tests: balance=45200 threshold=10000 -> OK; balance=8400 threshold=10000 -> LOW; balance exactly equal to threshold -> OK (boundary)
**Deliverable:** PrefundingStatusBadge.tsx with unit tests
**Acceptance / logic checks:**
- balance=45200 threshold=10000: renders OK with --color-success
- balance=8400 threshold=10000: renders LOW with --color-warning
- balance=10000 threshold=10000 (exactly equal): renders OK (>= not >)
- balance=9999.99 threshold=10000: renders LOW
- Formatted balance displayed via MoneyDisplay (e.g. USD 45,200.00)
**Depends on:** 12.1-T09, 12.1-T13

### 12.1-T34 — Compose design-system component index and build output  _(45 min)_
**Context:** All components (Button, TextInput, NumberInput, Select, Toggle, DatePicker, StatusBadge, SettlementBadge, MoneyDisplay, DataTable, Pagination, Toast, ToastContainer, Modal, ConfirmationModal, EmptyState, SkeletonTable, ErrorState, Breadcrumb, LeftNavSidebar, TopBar, RateDisplay, PrefundingStatusBadge) must be re-exported from a single barrel at packages/design-system/src/index.ts. Build must produce a typed dist/ folder consumed by both Admin and Portal apps.
**Steps:** Create packages/design-system/src/index.ts re-exporting every component and utility built in prior tickets; Configure tsup or rollup in packages/design-system to bundle to dist/index.js + dist/index.d.ts; Run build and verify no TypeScript errors; Write a stub consumer test in a separate workspace package that imports 5 representative components (Button, StatusBadge, MoneyDisplay, DataTable, Modal) and verifies they are not undefined; Add a package.json exports field pointing to dist/index.js
**Deliverable:** packages/design-system/dist/ with index.js and index.d.ts; barrel src/index.ts
**Acceptance / logic checks:**
- tsc --noEmit on the design-system package exits 0 (no TS errors)
- dist/index.js exists after build and is non-empty
- dist/index.d.ts exports Button, StatusBadge, MoneyDisplay, DataTable, Modal type signatures
- Stub consumer import { Button } from @gmepay/design-system compiles without error
- No circular import warnings during build
**Depends on:** 12.1-T05, 12.1-T06, 12.1-T07, 12.1-T08, 12.1-T09, 12.1-T10, 12.1-T13, 12.1-T14, 12.1-T15, 12.1-T16, 12.1-T17, 12.1-T18, 12.1-T19, 12.1-T20, 12.1-T21, 12.1-T22, 12.1-T23, 12.1-T24, 12.1-T32, 12.1-T33

### 12.1-T35 — Write component library Storybook stories and visual documentation  _(60 min)_
**Context:** UX-11 §9 Assumption A-01: No Figma/mockup files exist; text wireframes and component specs are sole design authority. Storybook stories serve as living documentation of the design system for both Admin and Portal teams. Every component must have at minimum: a Default story, all variant stories, and an edge-case story (e.g. long text, zero balance, 6-column table).
**Steps:** Configure Storybook in packages/design-system if not already present; Create .stories.tsx for each component: Button (4 variants x 3 sizes + disabled), StatusBadge (all 6 transaction statuses), SettlementBadge (3 statuses), MoneyDisplay (positive, negative, zero, KRW), DataTable (6 columns, compact, long text), EmptyState, SkeletonTable, ErrorState, Modal, ConfirmationModal, Toast, DatePicker, PrefundingStatusBadge; Add a Design Tokens story rendering a swatch grid for all 9 colour tokens, typography scale, and spacing scale; Ensure Storybook builds without errors: run storybook build
**Deliverable:** Storybook stories for every design-system component; storybook build succeeds
**Acceptance / logic checks:**
- storybook build exits 0 with no errors
- Button story shows all 4 variants at all 3 sizes plus disabled state
- StatusBadge story shows all 6 transaction statuses with correct colours visible
- Design Tokens story renders all 9 colour swatches with token name and hex value
- MoneyDisplay story includes negative USD (shows danger colour) and KRW (no decimals)
**Depends on:** 12.1-T34


## WBS 12.4 — Money/margin input components
### 12.4-T01 — Define MarginInputProps and MoneyInputProps TypeScript interfaces  _(20 min)_
**Context:** WBS 12.4 delivers the money/margin input components used in the Rule/Mapping page (Admin System, UX-11 section 4.4). Two fields control FX margin: m_a (Collection-Side Margin %) and m_b (Payout-Side Margin %), both Decimal, 0.00-99.99, max 4 decimal places per spec. A third derived display shows the combined margin. A separate MoneyInput handles service_charge (Decimal >= 0, in Settle A currency, max 4 decimal places). These interfaces are the contract all components and tests depend on.
**Steps:** Create src/components/inputs/types.ts; Define MarginInputProps: value (string), onChange (value: string) => void, onBlur optional, label string, name string, disabled boolean, error optional string, hint optional string, min (default 0), max (default 99.9999), decimalPlaces (default 4); Define MoneyInputProps: value (string), onChange, onBlur optional, label string, name string, currencyCode string, disabled boolean, error optional string, hint optional string, min (default 0), decimalPlaces (default 4); Define CombinedMarginDisplayProps: ma (string), mb (string), isCrossBorder boolean; Export all interfaces from src/components/inputs/index.ts
**Deliverable:** src/components/inputs/types.ts with MarginInputProps, MoneyInputProps, and CombinedMarginDisplayProps interfaces
**Acceptance / logic checks:**
- MarginInputProps includes required value/onChange and optional error, disabled, min, max, decimalPlaces fields
- MoneyInputProps includes currencyCode and decimalPlaces fields
- CombinedMarginDisplayProps includes isCrossBorder boolean used to drive cross-field validation display
- All interfaces compile with zero TypeScript errors under strict mode
- No runtime-only or domain logic in this file - types only

### 12.4-T02 — Implement parseMarginInput utility: string -> Decimal with validation  _(25 min)_
**Context:** The margin fields m_a and m_b accept user-entered strings like '1.00' or '1.2500' representing a percentage (e.g. 1.00 means 1.00%, stored as Decimal('0.0100') for the rate engine). The parser must: strip non-numeric chars, enforce max 4 decimal places, clamp to [0, 99.9999], and return a result object {value: Decimal | null, error: string | null}. The rate engine expects m_a and m_b as Decimal fractions (divide by 100). Use the decimal.js or Decimal library (no native float).
**Steps:** Create src/components/inputs/parseMarginInput.ts; Import Decimal from the project decimal library; Implement parseMarginInput(raw: string): {value: Decimal | null, error: string | null}; Return error 'Enter a percentage between 0.00 and 99.99' if raw is not a valid non-negative number or exceeds 99.9999; Return error if more than 4 decimal places are present in raw; On success return {value: new Decimal(raw), error: null} where value is the percentage (NOT yet divided by 100); Export parseMarginInput
**Deliverable:** src/components/inputs/parseMarginInput.ts with parseMarginInput(raw: string) function
**Acceptance / logic checks:**
- parseMarginInput('1.00') returns {value: Decimal('1.00'), error: null}
- parseMarginInput('99.9999') returns {value: Decimal('99.9999'), error: null}
- parseMarginInput('100') returns {error: 'Enter a percentage between 0.00 and 99.99', value: null}
- parseMarginInput('1.00001') returns an error because 5 decimal places
- parseMarginInput('-0.01') returns an error because value is negative
- parseMarginInput('abc') returns an error
**Depends on:** 12.4-T01

### 12.4-T03 — Implement parseMoneyInput utility: string -> Decimal with currency-aware validation  _(20 min)_
**Context:** The service_charge field accepts a money amount in Settle A currency (e.g. '0.50' for USD, '500' for KRW). Rules: must be >= 0, max 4 decimal places, non-negative, and numeric. Error message: 'Service charge must be zero or positive'. The currency context (e.g. KRW = 0 decimal places, USD = 2) is passed in for display guidance but the storage precision limit is always 4 dp per UX-11 section 7.2. Zero is valid (domestic rules).
**Steps:** Create src/components/inputs/parseMoneyInput.ts; Implement parseMoneyInput(raw: string, currencyCode: string): {value: Decimal | null, error: string | null}; Reject non-numeric or negative values with error 'Service charge must be zero or positive'; Reject inputs with more than 4 decimal places; Accept '0' and '0.00' as valid (return Decimal('0')); Export parseMoneyInput
**Deliverable:** src/components/inputs/parseMoneyInput.ts with parseMoneyInput function
**Acceptance / logic checks:**
- parseMoneyInput('0.50', 'USD') returns {value: Decimal('0.50'), error: null}
- parseMoneyInput('500', 'KRW') returns {value: Decimal('500'), error: null}
- parseMoneyInput('0', 'USD') returns {value: Decimal('0'), error: null}
- parseMoneyInput('-1', 'USD') returns {error: 'Service charge must be zero or positive', value: null}
- parseMoneyInput('0.00001', 'USD') returns error because 5 decimal places
**Depends on:** 12.4-T01

### 12.4-T04 — Implement computeCombinedMargin utility and cross-border minimum check  _(25 min)_
**Context:** When the operator edits m_a or m_b on the Rule/Mapping page, a 'Combined' row auto-updates (UX-11 section 4.4 / 6.4.3). Cross-border rules (settle_a_ccy != settle_b_ccy or collection_ccy != payout_ccy) require m_a + m_b >= 2.00%. Same-currency rules must have m_a = m_b = 0. The combined value is displayed as a percentage string to 2 decimal places (e.g. '2.00%'). Error message for cross-border violation: 'Combined margin must be at least 2.00% for cross-border rules'.
**Steps:** Create src/components/inputs/computeCombinedMargin.ts; Implement computeCombinedMargin(ma: string, mb: string): {combined: string, combinedDecimal: Decimal | null} using parseMarginInput internally; Implement validateCombinedMargin(ma: string, mb: string, isCrossBorder: boolean): string | null returning an error string or null; For cross-border: error if combined < 2.00% or if either individual margin is < 0; For same-currency: error if ma != '0' or '0.00' or mb != '0' or '0.00'; Display combined as formatted string '2.00%' (always 2 dp); Export both functions
**Deliverable:** src/components/inputs/computeCombinedMargin.ts with computeCombinedMargin and validateCombinedMargin
**Acceptance / logic checks:**
- computeCombinedMargin('1.00', '1.00').combined equals '2.00%'
- validateCombinedMargin('1.00', '0.99', true) returns error string containing '2.00%'
- validateCombinedMargin('1.00', '1.00', true) returns null (valid)
- validateCombinedMargin('0', '0', false) returns null (same-currency valid)
- validateCombinedMargin('1.00', '0', false) returns error (same-currency must be 0)
- computeCombinedMargin('0.5000', '1.5000').combined equals '2.00%'
**Depends on:** 12.4-T02

### 12.4-T05 — Build MarginInput React component with label, input, and inline error  _(40 min)_
**Context:** The MarginInput component renders a single margin percentage field (m_a or m_b) on the Rule/Mapping page Section 3 (UX-11 section 4.4). Spec: 36px height number input, right-aligned value, '%' suffix label, 1px border neutral-200 (#E5E7EB), 2px brand focus ring (#1A56DB). Validation fires on blur (not keystroke). Error appears inline below the field in --color-danger (#E02424) at 12px. Disabled state = 40% opacity + aria-disabled=true. The field accepts up to 4 decimal places. Label text is passed via props.
**Steps:** Create src/components/inputs/MarginInput.tsx; Render a <label> with for attribute linked to input id; Render <input type='number'> with step='0.0001', min='0', max='99.9999', right-align via CSS; Apply 1px border neutral-200 normally, red border on error, 2px brand focus ring; Show '%' suffix text adjacent to input; On onBlur call parseMarginInput and invoke props.onBlur if provided; Render error message in <span> below input with role='alert', color #E02424, 12px, linked via aria-describedby; Apply disabled prop with aria-disabled and 40% opacity; Export as default
**Deliverable:** src/components/inputs/MarginInput.tsx React component
**Acceptance / logic checks:**
- Input has aria-describedby pointing to error span id when error prop is set
- aria-invalid='true' is set on input when error prop is non-empty
- Disabled input renders with 40% opacity and aria-disabled='true'
- '%' suffix is visible adjacent to input
- Snapshot or rendered markup shows label with correct for/id linkage
- Component accepts and forwards all MarginInputProps fields
**Depends on:** 12.4-T01, 12.4-T02

### 12.4-T06 — Build MoneyInput React component with currency prefix and inline error  _(40 min)_
**Context:** The MoneyInput component handles the service_charge field on Rule/Mapping Section 4 (UX-11 section 4.4 and 6.4.4). The currency code (Settle A ccy, e.g. 'USD' or 'KRW') is shown as a non-editable prefix. Validation on blur. Error message 'Service charge must be zero or positive' shown inline at 12px #E02424. Field rules: >= 0, max 4 decimal places. The currency label must be prominent (UX-11 6.4.4 assumption) to prevent misentry. Layout: currency code label to the left of the input, 36px height.
**Steps:** Create src/components/inputs/MoneyInput.tsx; Render <label> linked to input by id; Render currency code as a non-editable prefix span inside the input group (e.g. 'USD' with neutral-600 color); Render <input type='number'> with step='0.0001', min='0'; On blur call parseMoneyInput(value, currencyCode) and report error via aria-describedby span; Apply error border and aria-invalid='true' on invalid state; Disabled state: 40% opacity, cursor not-allowed, aria-disabled='true'; Export as default
**Deliverable:** src/components/inputs/MoneyInput.tsx React component
**Acceptance / logic checks:**
- Currency code prefix is rendered as non-editable visible text left of the input
- Error span is linked via aria-describedby and has role='alert'
- Input does not accept negative values (min='0' attribute)
- aria-invalid='true' is set when error is present
- Disabled state shows 40% opacity and aria-disabled='true'
- Component accepts all MoneyInputProps fields without TypeScript errors
**Depends on:** 12.4-T01, 12.4-T03

### 12.4-T07 — Build CombinedMarginDisplay component with cross-border check indicator  _(35 min)_
**Context:** Below m_a and m_b inputs on Rule/Mapping Section 3, the UI shows a read-only 'Combined' row that auto-updates as the operator types. For cross-border rules (isCrossBorder=true) it shows a green checkmark when combined >= 2.00% or a red error indicator when below. Spec (UX-11 6.4.3): 'Combined: 2.00% checkmark (min 2.0%)'. For same-currency rules show an informational note instead: 'Same-currency rule. Margin locked at 0%'. Color tokens: success #057A55, danger #E02424.
**Steps:** Create src/components/inputs/CombinedMarginDisplay.tsx; Accept CombinedMarginDisplayProps: ma string, mb string, isCrossBorder boolean; Derive combined label using computeCombinedMargin(ma, mb); For cross-border: show 'Combined: {X}%' + checkmark icon (#057A55) if >= 2.00, or red X + error text (#E02424) if < 2.00; For same-currency: show blue info text 'Same-currency rule. Margin locked at 0%' with no combined display; Make the combined value output a <span> with data-testid='combined-margin-value' for test targeting; Export as default
**Deliverable:** src/components/inputs/CombinedMarginDisplay.tsx React component
**Acceptance / logic checks:**
- With ma='1.00' mb='1.00' isCrossBorder=true shows '2.00%' in green with checkmark
- With ma='1.00' mb='0.50' isCrossBorder=true shows '1.50%' in red with error indicator
- With ma='0' mb='0' isCrossBorder=false shows same-currency info message, not a 2% check
- data-testid='combined-margin-value' element contains the computed percentage string
- Component re-renders correctly when ma or mb props change
**Depends on:** 12.4-T04

### 12.4-T08 — Add formatMarginDisplay and formatMoneyDisplay pure formatting utilities  _(30 min)_
**Context:** Margin values stored in the DB as fractions (e.g. 0.0100) must be formatted for display as '1.00%'. Money amounts must follow UX-11 section 2.8: '{currency_code} {amount}', comma thousands separator, period decimal, KRW = 0 dp, USD/others = 2 dp, zero = '0.00'. These utilities are pure functions with no side effects, used by both components and any summary/review panels. They do NOT use native float - accept Decimal or string.
**Steps:** Create src/components/inputs/formatters.ts; Implement formatMarginDisplay(fraction: Decimal): string - multiply by 100, format to 2 dp, append '%' e.g. Decimal('0.0100') -> '1.00%'; Implement formatMarginPercent(percent: string): string - normalise to 2 dp, append '%'; Implement formatMoneyDisplay(amount: Decimal, currencyCode: string): string - 'USD 10,234.56', 'KRW 45,000' (0 dp), comma thousands, period decimal; Implement formatRateDisplay(rate: Decimal): string - 6 significant figures as per UX-11 2.8; Export all four functions
**Deliverable:** src/components/inputs/formatters.ts with formatMarginDisplay, formatMarginPercent, formatMoneyDisplay, formatRateDisplay
**Acceptance / logic checks:**
- formatMarginDisplay(Decimal('0.0100')) returns '1.00%'
- formatMoneyDisplay(Decimal('10234.56'), 'USD') returns 'USD 10,234.56'
- formatMoneyDisplay(Decimal('45000'), 'KRW') returns 'KRW 45,000' (no decimals)
- formatMoneyDisplay(Decimal('0'), 'USD') returns 'USD 0.00' (not blank or dash)
- formatRateDisplay(Decimal('1350.42')) returns '1,350.42' (6 sig figs)
**Depends on:** 12.4-T01

### 12.4-T09 — Implement margin reduction inline warning (cross-field rule)  _(25 min)_
**Context:** UX-11 section 7.3 cross-field rule: if m_a or m_b is reduced below its previously saved value, show an inline warning (non-blocking): 'Reducing the margin will lower GME revenue on new transactions. Confirm this is intentional.' This is NOT a form-blocking error - the Save button remains enabled. It appears below the individual field. The component needs previousValue props to compare against current.
**Steps:** Extend MarginInputProps in types.ts to add optional previousValue: string field; In MarginInput.tsx, after blur validation passes, compare value to previousValue if provided; If current < previousValue, set a local warning state and render a warning span with color #D97706 (--color-warning), 12px, below the field; Warning does not set aria-invalid and does not block form submission; Add data-testid='margin-reduction-warning' to the warning span; Export updated component
**Deliverable:** Updated MarginInput.tsx with margin-reduction inline warning behavior
**Acceptance / logic checks:**
- With previousValue='1.50' and value='1.00', warning text appears after blur containing 'lower GME revenue'
- Warning uses #D97706 color, not #E02424 (danger)
- Warning does not set aria-invalid='true' on the input
- With previousValue='1.00' and value='1.25' (increase), no warning appears
- With previousValue not provided, no warning appears regardless of value
**Depends on:** 12.4-T05

### 12.4-T10 — Wire Section 3 margin fields into Rule/Mapping page form state  _(45 min)_
**Context:** The Rule/Mapping page (UX-11 section 4.4) has Section 3 with m_a, m_b, and combined display. The form state holds current values and previously-saved values. Cross-border flag is derived from the rule's currency setup (Section 1 read-only fields). Same-currency rules auto-lock m_a and m_b to '0' and disable the inputs. The Save/Review button must be disabled while combined margin error exists for cross-border rules (UX-11 6.4.3).
**Steps:** In the Rule/Mapping page component (or form slice), add form state fields: ma, mb, previousMa, previousMb; Derive isCrossBorder from currencySetup (settle_a_ccy != settle_b_ccy or collection_ccy != payout_ccy); If same-currency detected, set ma='0', mb='0', disabled=true, and show the CombinedMarginDisplay in same-currency mode; Pass ma, mb, isCrossBorder into CombinedMarginDisplay; pass previousMa/previousMb into respective MarginInput components; Compute marginError = validateCombinedMargin(ma, mb, isCrossBorder) and pass to Save/Review button disabled prop; Save/Review button disabled when marginError is non-null
**Deliverable:** Rule/Mapping page Section 3 wired to form state with correct enable/disable and combined validation
**Acceptance / logic checks:**
- Entering m_a='1.00' m_b='0.99' with isCrossBorder=true disables Save/Review button and shows combined error
- Entering m_a='1.00' m_b='1.00' with isCrossBorder=true enables Save/Review button, combined shows '2.00%' checkmark
- For a same-currency rule, both margin inputs are disabled and set to '0'
- Changing m_a updates combined display without requiring blur (onChange updates combined in real-time)
- Previously-saved values from the loaded rule populate previousMa, previousMb props
**Depends on:** 12.4-T04, 12.4-T05, 12.4-T06, 12.4-T07

### 12.4-T11 — Wire Section 4 service_charge MoneyInput into Rule/Mapping form state  _(35 min)_
**Context:** Section 4 of Rule/Mapping page (UX-11 6.4.4): service_charge_amount (Decimal >= 0, max 4 dp) and service_charge_ccy (display-only, auto-set to Settle A currency, operator cannot change). The currency label must be shown prominently next to the amount field. Validation: error 'Service charge must be zero or positive'. Zero is valid for domestic rules. The charge never enters USD pool math.
**Steps:** Add serviceChargeAmount and settleACcy to Rule/Mapping form state; Render MoneyInput with currencyCode={settleACcy} for the service_charge_amount field; Set settleACcy as display-only derived from the rule currency setup (not editable here); On MoneyInput blur, call parseMoneyInput(value, settleACcy) and set serviceChargeError in form state; Block Save/Review if serviceChargeError is non-null; Show currency label 'Settle A: {ccy}' as a helper hint below the input
**Deliverable:** Rule/Mapping page Section 4 wired to form state with MoneyInput and currency display
**Acceptance / logic checks:**
- Entering '-1' in service charge field shows error 'Service charge must be zero or positive' after blur
- Entering '0' is valid and does not show error
- Entering '500' with settleACcy='KRW' shows 'KRW' prefix and no error
- Currency code is derived automatically from rule's Settle A currency, not manually entered
- Save/Review is blocked when serviceChargeError is set
**Depends on:** 12.4-T06, 12.4-T10

### 12.4-T12 — Implement same-currency short-circuit mode detection and field lock  _(30 min)_
**Context:** UX-11 7.3 and 6.4.3: when all four currencies (collection_ccy, settle_a_ccy, settle_b_ccy, payout_ccy) are the same (e.g. all KRW for GME Remit Domestic), the form must auto-detect same-currency mode, lock m_a and m_b to '0.00', disable the margin inputs, and show a blue info banner: 'This is a same-currency rule. The USD pool is bypassed. Minimum combined margin is not required.' The 2% combined constraint must not apply.
**Steps:** Create src/components/inputs/detectSameCurrency.ts with function detectSameCurrency(currencies: {collectionCcy, settleACcy, settleBCcy, payoutCcy}): boolean; In Rule/Mapping page, call detectSameCurrency on mount and when currency setup changes; If same-currency detected: set isCrossBorder=false, force ma='0', mb='0', disable MarginInput components; Render info banner (blue, --color-brand border-left) with exact text from spec; Export detectSameCurrency
**Deliverable:** detectSameCurrency.ts utility and same-currency mode in Rule/Mapping page
**Acceptance / logic checks:**
- detectSameCurrency({collectionCcy:'KRW', settleACcy:'KRW', settleBCcy:'KRW', payoutCcy:'KRW'}) returns true
- detectSameCurrency({collectionCcy:'MNT', settleACcy:'USD', settleBCcy:'KRW', payoutCcy:'KRW'}) returns false
- When same-currency=true, margin inputs are disabled and values forced to '0'
- Blue info banner with 'USD pool is bypassed' message is visible
- Combined 2.00% error does not appear when same-currency=true
**Depends on:** 12.4-T04, 12.4-T07

### 12.4-T13 — Build Review Changes modal diff view for margin and service charge fields  _(40 min)_
**Context:** UX-11 6.4.3 / 4.4: before saving, a Review Changes modal shows a before/after diff table. Fields shown: m_a (as percentage), m_b (as percentage), service_charge (as money). Previous values come from the loaded rule. Note states: 'Changes apply to NEW transactions only. Committed transactions are unaffected.' Save button label: 'Save Changes'. The modal must show actual formatted values, not raw fractions.
**Steps:** Create src/components/inputs/MarginReviewModal.tsx; Accept props: isOpen, onClose, onConfirm, previousMa, previousMb, newMa, newMb, previousServiceCharge, newServiceCharge, settleACcy; Render a table with columns: Field, Before, After; Rows: 'Collection Margin (m_a)', 'Payout Margin (m_b)', 'Service Charge'; Format percentages using formatMarginPercent; format money using formatMoneyDisplay; Show warning notice about new-transaction-only effect; Render Cancel (secondary) and Save Changes (primary, --color-brand) buttons; Close on Cancel or backdrop click; trigger onConfirm on Save Changes
**Deliverable:** src/components/inputs/MarginReviewModal.tsx with before/after diff table
**Acceptance / logic checks:**
- Row 'm_a' shows previousMa='0.80%' and newMa='1.00%' formatted as percentages
- Row 'Service Charge' shows 'USD 0.50' for settleACcy='USD'
- Warning text 'Changes apply to NEW transactions only' is visible
- Save Changes button calls onConfirm when clicked
- Cancel button closes modal without calling onConfirm
- Modal has role='dialog' and aria-modal='true' with focus trap
**Depends on:** 12.4-T08

### 12.4-T14 — Add USD pool preview tooltip/info panel to margin section  _(25 min)_
**Context:** UX-11 section 6.4.3 specifies a rate engine behavior reminder displayed to the operator: 'Margins are applied as USD dollar amounts to the collection_usd pool: collection_margin_usd = collection_usd x m_a and payout_margin_usd = collection_usd x m_b. Pool identity: collection_usd - collection_margin_usd - payout_margin_usd = payout_usd_cost.' This should be a dismissible info banner or tooltip/help icon in Section 3 of the mapping page. Not shown for same-currency rules.
**Steps:** Create src/components/inputs/MarginInfoBanner.tsx; Display info text from spec verbatim (using variable names as shown); Hide completely when isCrossBorder=false (same-currency mode); Render as a collapsible or static info panel with a (?) help icon trigger option; Style with a neutral-50 background and brand-color left border (informational, not a warning); Export component
**Deliverable:** src/components/inputs/MarginInfoBanner.tsx info panel component
**Acceptance / logic checks:**
- Banner text includes 'collection_margin_usd = collection_usd x m_a'
- Banner is not rendered when isCrossBorder=false
- Banner is rendered when isCrossBorder=true
- Banner does not block form interaction (informational only)
- Component accepts isCrossBorder prop
**Depends on:** 12.4-T01

### 12.4-T15 — Unit tests: parseMarginInput edge cases  _(30 min)_
**Context:** Thorough unit tests for parseMarginInput covering all stated validation rules. Test file uses Jest + expected Decimal output. Tests must run in isolation with no React rendering. Edge cases include boundary values (0, 99.9999, 100), decimal precision (4 dp valid, 5 dp invalid), non-numeric input, empty string, negative values, and whitespace.
**Steps:** Create src/components/inputs/__tests__/parseMarginInput.test.ts; Test happy path: '0', '0.00', '1', '1.00', '1.2500', '99.9999'; Test upper boundary: '100' -> error; '99.9999' -> valid; Test decimal places: '1.12345' (5 dp) -> error; '1.1234' (4 dp) -> valid; Test non-numeric: 'abc', '', ' ', '1.2.3' -> error; Test negatives: '-0.01', '-1' -> error; Assert error message text matches exactly 'Enter a percentage between 0.00 and 99.99'
**Deliverable:** src/components/inputs/__tests__/parseMarginInput.test.ts with >= 15 test cases
**Acceptance / logic checks:**
- All 15+ tests pass with jest
- Boundary value '99.9999' passes and '100.0000' fails
- 5-decimal-place input returns error
- Empty string returns error
- Error message matches spec text exactly: 'Enter a percentage between 0.00 and 99.99'
**Depends on:** 12.4-T02

### 12.4-T16 — Unit tests: parseMoneyInput edge cases  _(25 min)_
**Context:** Unit tests for parseMoneyInput covering zero (valid), positive values, negative (error), decimal precision (max 4 dp), non-numeric, and large values. Tests confirm KRW integer amounts are accepted (no enforced decimal restriction based on currency code, only the 4-dp storage limit applies). Tests use Jest.
**Steps:** Create src/components/inputs/__tests__/parseMoneyInput.test.ts; Test valid cases: '0', '0.00', '500', '0.50', '0.0001', '999999.9999'; Test invalid: '-1', '-0.01', 'abc', '', '0.00001' (5 dp); Test KRW: '500' with currencyCode='KRW' is valid, '500.5' is also accepted (stored to 4 dp regardless of display currency); Assert error message: 'Service charge must be zero or positive'; Run all tests and assert 0 failures
**Deliverable:** src/components/inputs/__tests__/parseMoneyInput.test.ts with >= 12 test cases
**Acceptance / logic checks:**
- parseMoneyInput('0', 'USD') passes
- parseMoneyInput('-0.01', 'KRW') returns error
- 5-decimal input returns error
- Error message matches spec exactly: 'Service charge must be zero or positive'
- All 12+ tests pass
**Depends on:** 12.4-T03

### 12.4-T17 — Unit tests: computeCombinedMargin and validateCombinedMargin  _(30 min)_
**Context:** Unit tests for the combined margin computation and cross-border/same-currency validation logic. These are the most critical logic tests: they encode the 2.00% hard floor (RATE-04 / UX-11 7.2) and same-currency zero-lock rules. Must cover: just above floor (2.00%), just below floor (1.99%), zero-zero same-currency, non-zero same-currency (error), boundary decimals.
**Steps:** Create src/components/inputs/__tests__/computeCombinedMargin.test.ts; Test computeCombinedMargin: ('1.00','1.00').combined == '2.00%'; ('0.5000','1.5000').combined == '2.00%'; ('0','0').combined == '0.00%'; Test validateCombinedMargin cross-border: ('1.00','1.00',true) -> null; ('1.00','0.99',true) -> error; ('0','0',true) -> error; Test validateCombinedMargin same-currency: ('0','0',false) -> null; ('0.01','0',false) -> error; Test edge: ('99.9999','0.0001',true) -> null (sum=100.0000 but <1.0 fraction guard is rate-engine concern, display layer only checks >=2.00); Assert error message contains '2.00%' for cross-border violation
**Deliverable:** src/components/inputs/__tests__/computeCombinedMargin.test.ts with >= 10 test cases
**Acceptance / logic checks:**
- ('1.00','1.00',true) returns null (valid)
- ('1.00','0.99',true) returns error string containing '2.00%'
- ('0','0',false) returns null (valid same-currency)
- ('0.01','0',false) returns error (same-currency must be 0)
- computeCombinedMargin('0.5000','1.5000').combined equals '2.00%'
**Depends on:** 12.4-T04

### 12.4-T18 — Unit tests: detectSameCurrency utility  _(20 min)_
**Context:** Unit tests for detectSameCurrency covering same-currency (all four equal), cross-border (any two differ), and common real-world configurations. Configurations tested: GME Remit Domestic (all KRW -> same-currency), SendMN Inbound (MNT/USD/USD/KRW -> cross-border), USD-to-USD HUB (all USD -> same-currency), partial match (collect=USD settle_a=USD settle_b=KRW payout=KRW -> cross-border).
**Steps:** Create src/components/inputs/__tests__/detectSameCurrency.test.ts; Test all-KRW returns true; Test MNT/USD/USD/KRW returns false; Test all-USD returns true; Test USD/USD/KRW/KRW (settle_b differs from settle_a) returns false; Test USD/USD/USD/KRW returns false; Assert function type: (obj) -> boolean
**Deliverable:** src/components/inputs/__tests__/detectSameCurrency.test.ts with >= 6 test cases
**Acceptance / logic checks:**
- All-same-currency config returns true
- Any two-currency-differ config returns false
- MNT/USD/USD/KRW -> false
- All-USD HUB direction -> true
- All tests pass with jest
**Depends on:** 12.4-T12

### 12.4-T19 — Unit tests: formatters (formatMoneyDisplay, formatMarginDisplay, formatRateDisplay)  _(25 min)_
**Context:** Unit tests for the pure formatting utilities in formatters.ts. Critical edge cases: KRW 0 decimals, USD 2 decimals, zero values (must show '0.00' not blank), large amounts (comma thousands separator), negative amounts (must use Unicode minus U+2212 and --color-danger per UX-11 2.8 - but color is in component layer, formatter should prepend unicode minus), margin fraction to percent conversion.
**Steps:** Create src/components/inputs/__tests__/formatters.test.ts; Test formatMoneyDisplay: Decimal('10234.56'),'USD' -> 'USD 10,234.56'; Decimal('45000'),'KRW' -> 'KRW 45,000'; Decimal('0'),'USD' -> 'USD 0.00'; Test formatMarginDisplay: Decimal('0.0100') -> '1.00%'; Decimal('0.02') -> '2.00%'; Decimal('0') -> '0.00%'; Test formatRateDisplay: Decimal('1350.42') -> '1,350.42'; Decimal('0.000740') -> '0.000740'; Test formatMarginPercent: '1.00' -> '1.00%'; '2.0000' -> '2.00%'
**Deliverable:** src/components/inputs/__tests__/formatters.test.ts with >= 12 test cases
**Acceptance / logic checks:**
- formatMoneyDisplay(Decimal('0'),'USD') returns 'USD 0.00' not blank
- formatMoneyDisplay(Decimal('45000'),'KRW') returns 'KRW 45,000' with no decimal places
- formatMarginDisplay(Decimal('0.0100')) returns '1.00%'
- formatRateDisplay(Decimal('1350.42')) returns 6 significant figures
- All 12+ tests pass
**Depends on:** 12.4-T08

### 12.4-T20 — Integration test: Section 3 margin fields in Rule/Mapping page form  _(45 min)_
**Context:** Integration test using React Testing Library verifying Section 3 end-to-end: rendering both margin inputs, combined display, same-currency lock, and Save button enabled/disabled state. Uses a mock rule with isCrossBorder=true. Tests simulate operator entering values, blurring fields, and verifying UI reactions.
**Steps:** Create src/components/inputs/__tests__/MarginSection.integration.test.tsx; Render the Rule/Mapping page Section 3 wrapper with a cross-border mock rule; Simulate typing '1.00' into m_a and '1.00' into m_b, blur both - combined should show '2.00%' with checkmark and Save enabled; Simulate typing '1.00' into m_a and '0.99' into m_b - Save should be disabled, combined error visible; Simulate same-currency rule (isCrossBorder=false) - both inputs disabled and locked to '0'; Simulate reducing m_a from '1.50' to '1.00' (with previousMa='1.50') - reduction warning appears
**Deliverable:** src/components/inputs/__tests__/MarginSection.integration.test.tsx with >= 6 integration scenarios
**Acceptance / logic checks:**
- m_a='1.00' mb='1.00' cross-border: Save button not disabled, combined shows '2.00%'
- m_a='1.00' mb='0.99' cross-border: Save button disabled, error message visible
- Same-currency mode: both inputs are disabled
- Margin reduction warning appears when value decreases below previousValue
- All scenarios pass with React Testing Library
**Depends on:** 12.4-T10, 12.4-T09

### 12.4-T21 — Accessibility audit: MarginInput and MoneyInput WCAG 2.1 AA checks  _(35 min)_
**Context:** UX-11 section 8.1 requires WCAG 2.1 AA compliance. Key rules for input components: errors linked to fields via aria-describedby; aria-invalid='true' on invalid inputs; visible 2px brand-colour focus ring (#1A56DB); all form controls have associated <label> with for attribute; disabled state uses aria-disabled='true'. Use jest-axe or axe-core for automated checks plus manual assertions.
**Steps:** Create src/components/inputs/__tests__/accessibility.test.tsx; Use @axe-core/react or jest-axe to run axe on rendered MarginInput (valid state, error state, disabled state); Use @axe-core/react or jest-axe to run axe on rendered MoneyInput (valid state, error state); Assert no axe violations in any state; Manually assert: aria-describedby on input points to error span id; aria-invalid='true' when error set; label for= matches input id; aria-disabled='true' when disabled
**Deliverable:** src/components/inputs/__tests__/accessibility.test.tsx with axe and manual ARIA assertions
**Acceptance / logic checks:**
- axe reports 0 violations for MarginInput in valid state
- axe reports 0 violations for MarginInput in error state
- aria-describedby on input element matches id of error span
- aria-invalid='true' is present when error prop is non-empty
- aria-disabled='true' is present when disabled prop is true
**Depends on:** 12.4-T05, 12.4-T06

### 12.4-T22 — Storybook stories for MarginInput, MoneyInput, CombinedMarginDisplay  _(40 min)_
**Context:** Storybook stories serve as living documentation and visual regression baseline for the margin/money input components. Each component needs: default/empty, filled-valid, error, disabled, and same-currency-locked variants. Stories use real field names and numeric examples from the spec (e.g. m_a=1.00, m_b=1.00, service_charge=USD 0.50, SendMN Inbound rule context).
**Steps:** Create src/components/inputs/MarginInput.stories.tsx with stories: Default, Filled (m_a=1.00%), Error state, Disabled (same-currency lock); Create src/components/inputs/MoneyInput.stories.tsx with stories: Default, Filled USD (0.50), Filled KRW (500), Error, Disabled; Create src/components/inputs/CombinedMarginDisplay.stories.tsx with: CrossBorderValid (2.00%), CrossBorderError (1.50%), SameCurrency; Use args/controls so designers can tweak values interactively; Each story has a meaningful name and description comment
**Deliverable:** Three Storybook story files covering all visual states of each component
**Acceptance / logic checks:**
- MarginInput story 'Error' shows red border and error text below input
- MoneyInput story 'FilledKRW' shows 'KRW' prefix and integer value '500'
- CombinedMarginDisplay 'CrossBorderError' shows red indicator with combined < 2.00%
- CombinedMarginDisplay 'SameCurrency' shows blue info message, not a percentage
- All story files load without errors in Storybook
**Depends on:** 12.4-T05, 12.4-T06, 12.4-T07

### 12.4-T23 — Export barrel and component index for inputs module  _(20 min)_
**Context:** All components and utilities in src/components/inputs/ must be accessible via a clean barrel export so consuming pages import from a single path: import { MarginInput, MoneyInput, CombinedMarginDisplay, parseMarginInput, validateCombinedMargin, formatMoneyDisplay } from '@/components/inputs'. This is a common pattern required before the inputs can be wired into the Rule/Mapping page without relative import chains.
**Steps:** Create or update src/components/inputs/index.ts; Re-export all components: MarginInput, MoneyInput, CombinedMarginDisplay, MarginReviewModal, MarginInfoBanner; Re-export all utilities: parseMarginInput, parseMoneyInput, computeCombinedMargin, validateCombinedMargin, detectSameCurrency; Re-export all formatters: formatMoneyDisplay, formatMarginDisplay, formatMarginPercent, formatRateDisplay; Re-export all types: MarginInputProps, MoneyInputProps, CombinedMarginDisplayProps; Verify no circular imports by running tsc --noEmit
**Deliverable:** src/components/inputs/index.ts barrel with all public exports
**Acceptance / logic checks:**
- import { MarginInput } from '@/components/inputs' resolves without error
- import { validateCombinedMargin } from '@/components/inputs' resolves without error
- tsc --noEmit completes with 0 errors
- No circular dependency warnings from tsc or bundler
- All exported names match their source file exports exactly
**Depends on:** 12.4-T05, 12.4-T06, 12.4-T07, 12.4-T08, 12.4-T12, 12.4-T13, 12.4-T14


## WBS 12.5 — Accessibility (WCAG AA) & i18n
### 12.5-T01 — Create i18n translation-key file scaffold for English (en) locale  _(45 min)_
**Context:** UX-11 A-05 mandates all UI string literals be externalised to i18n key files from the first commit so Korean can be added in Phase 2 without code changes. Both Admin System and Partner Portal are React SPAs sharing a common component library. Phase 1 ships English only; Korean (ko) is Phase 2. NFR-10 §9.2 confirms Admin portal: Korean primary + English; Partner portal: English primary.
**Steps:** Create src/i18n/en.json in the shared component library with a flat or namespaced key structure (e.g. {"nav.dashboard":"Dashboard","badge.approved":"Approved"}); Add placeholder src/i18n/ko.json with the same keys mapped to empty strings or English fallback; Configure the i18n runtime (e.g. react-i18next) to load the locale files and default to 'en'; Document the key naming convention (snake_case, namespace prefix) in a CONVENTIONS comment block at the top of en.json
**Deliverable:** src/i18n/en.json and src/i18n/ko.json scaffold files with i18n runtime wired into the SPA entry point
**Acceptance / logic checks:**
- en.json parses as valid JSON with zero duplicate keys
- Switching the runtime locale to 'ko' does not throw; missing keys fall back to 'en' string
- A t('nav.dashboard') call renders 'Dashboard' in English mode
- Importing en.json from both Admin and Portal apps succeeds without path errors
- File contains at least the 20 nav/badge strings defined in UX-11 §2.7 and §navigation sections

### 12.5-T02 — Audit all hardcoded UI strings in Admin System and replace with i18n keys  _(60 min)_
**Context:** UX-11 A-05: no hardcoded string literals in JSX/TSX. Admin System screens include: Login, Dashboard, Partner List/Detail, Scheme List/Detail, Mapping Page, Transaction Search, Prefunding, Refund, Audit Log, Reporting (per PRD-07 and UX-11 screen sections). All labels, button text, error messages, placeholder text, and toast messages must use t('key') calls.
**Steps:** Run a codebase grep for JSX string literals (text nodes and string props) in src/admin/**; For each found string create or reuse a key in en.json and replace the literal with {t('key')}; Repeat for inline object strings (aria-label, placeholder, title attributes); Verify no raw English strings remain in JSX (automated lint rule or grep check)
**Deliverable:** Admin System codebase with zero hardcoded English UI strings; all keys present in en.json
**Acceptance / logic checks:**
- grep -r 'jsx-string-literal' src/admin returns 0 results after lint rule is applied
- en.json key count increases by at least the number of strings extracted
- Switching locale to 'ko' (even with English fallback) does not break Admin System render
- aria-label and placeholder attributes use t() calls, verifiable by code search
- Build passes without TypeScript errors after refactor
**Depends on:** 12.5-T01

### 12.5-T03 — Audit all hardcoded UI strings in Partner Portal and replace with i18n keys  _(60 min)_
**Context:** UX-11 A-05 applies equally to the Partner Portal (separate React SPA at portal.gmepay.com). Partner Portal screens: Login, Dashboard, Transaction List/Detail, Settlement Statements, Prefunding Balance, API Credentials (per PRD-08 and UX-11). English primary; Korean secondary (Phase 2). Same shared i18n runtime and en.json/ko.json files.
**Steps:** Run a codebase grep for JSX string literals in src/portal/**; For each found string create or reuse a key in en.json and replace with {t('key')}; Audit aria-label, placeholder, title, and alt attributes; Verify build and that no raw strings remain
**Deliverable:** Partner Portal codebase with zero hardcoded English UI strings; keys added to en.json
**Acceptance / logic checks:**
- grep for residual hardcoded strings in src/portal returns 0 results
- All new keys present in en.json; ko.json has matching keys (empty strings acceptable)
- Partner Portal renders correctly with locale='en' after refactor
- No duplicate keys introduced between Admin and Portal extractions
**Depends on:** 12.5-T01, 12.5-T02

### 12.5-T04 — Implement language-toggle component and locale persistence  _(45 min)_
**Context:** UX-11 §8.2 Phase 2 requires English + Korean toggle. NFR-10 §9.2: Admin portal Korean primary + English. The toggle must be present from Phase 1 (initially showing only 'EN'; 'KO' activates when ko.json is populated). Locale preference must persist across sessions (localStorage or user profile). Both Admin and Portal share the same toggle component from the design-system library.
**Steps:** Build a LanguageToggle component ('EN' | 'KO') using the shared design-system button style (ghost variant per UX-11 §2.4); On change call i18n.changeLanguage() and persist selection to localStorage key 'gmepay_locale'; On app mount read localStorage and initialise i18n with persisted locale (default 'en'); Place toggle in the global nav header of both Admin and Portal apps; Disable the 'KO' option via a feature flag FEATURE_KOREAN_UI (off in Phase 1) so it is present but inert until ko.json is complete
**Deliverable:** LanguageToggle React component and locale-persistence hook used in both app headers
**Acceptance / logic checks:**
- Selecting 'EN' changes all t() rendered strings to English and persists across page reload
- Selecting 'KO' with FEATURE_KOREAN_UI=true renders ko.json strings (or English fallback if key missing)
- With FEATURE_KOREAN_UI=false the KO button is aria-disabled=true and does not trigger language change
- localStorage 'gmepay_locale' value equals the active locale after toggle
- Component meets focus-ring requirement: 2 px brand-colour focus ring visible on keyboard focus (UX-11 §8.1)
**Depends on:** 12.5-T01, 12.5-T02, 12.5-T03

### 12.5-T05 — Populate Korean (ko) translation strings in ko.json  _(45 min)_
**Context:** NFR-10 §9.2: Admin portal labels, error messages, and help text must be available in Korean. UX-11 §8.2 Phase 2 requirement. Korean strings must use UTF-8 encoding. Key set must exactly match en.json. This ticket covers translation of all keys extracted in T02 and T03. Translator (GME Ops/internal) provides the Korean text; this ticket wires it in.
**Steps:** Obtain Korean translations for all keys in en.json from GME Ops/translator; Populate ko.json with translated values, preserving the exact key names from en.json; Ensure the file is saved as UTF-8 (no BOM) and parses as valid JSON; Validate that every key in en.json exists in ko.json (automated check); Set FEATURE_KOREAN_UI=true in staging environment to enable testing
**Deliverable:** Fully populated src/i18n/ko.json file with Korean translations for all UI strings
**Acceptance / logic checks:**
- ko.json key count equals en.json key count (zero missing keys)
- File parses as valid JSON with UTF-8 encoding verified by file -i or equivalent
- Switching locale to 'ko' in Admin System renders Korean text in nav, badges, and form labels without layout breakage
- Korean text in badge labels such as 'APPROVED' renders as the Korean equivalent without clipping at 12 px badge font size
- No English string literals remain in ko.json values (i.e. translation is not a copy of en.json)
**Depends on:** 12.5-T04

### 12.5-T06 — Verify and enforce colour-contrast ratios for all design-system tokens (WCAG AA)  _(45 min)_
**Context:** UX-11 §8.1: all text/background combinations must meet 4.5:1 (normal text, < 18 px or < 14 px bold) or 3:1 (large text >= 18 px or >= 14 px bold). Design tokens: --color-brand #1A56DB on white; --color-neutral-900 #111928 on #F9FAFB; status badge combos e.g. #057A55 on #DEF7EC (APPROVED), #E02424 on #FDE8E8 (DECLINED), #D97706 on #FEF3C7 (PENDING), #4B5563 on #E5E7EB (CANCELLED). All combinations must pass WCAG 2.1 AA.
**Steps:** List every foreground/background token combination used in the design system (buttons, badges, nav, table, toast); Compute contrast ratio for each using the WCAG relative luminance formula or a tool such as axe or Storybook a11y addon; For any failing combination adjust the token value (darkening text or lightening background) until the ratio passes; Update CSS custom property values in tokens.css or equivalent and re-run the contrast audit; Document results in a contrast-audit.md table (pass/fail per combination)
**Deliverable:** Updated tokens.css with all token combinations verified >= 4.5:1 (normal text) or >= 3:1 (large text), plus contrast-audit.md
**Acceptance / logic checks:**
- #1A56DB (#color-brand) on white (#FFFFFF): contrast ratio >= 4.5:1 (expected ~5.9:1 — verify)
- #057A55 on #DEF7EC (APPROVED badge): contrast ratio >= 4.5:1
- #E02424 on #FDE8E8 (DECLINED badge): contrast ratio >= 4.5:1
- #D97706 on #FEF3C7 (PENDING badge): contrast ratio >= 4.5:1
- #4B5563 (#color-neutral-600) on #F9FAFB (#color-neutral-50): contrast ratio >= 4.5:1 for 14 px body text

### 12.5-T07 — Implement skip-to-content link on every page (WCAG 2.4.1 Bypass Blocks)  _(30 min)_
**Context:** UX-11 §8.1: a skip-to-content link is required on each page to allow keyboard users to bypass the navigation sidebar and jump to the main content area. WCAG 2.1 AA Success Criterion 2.4.1. Applied to both Admin System and Partner Portal. The link must be the first focusable element in the DOM but visually hidden until focused.
**Steps:** Add a <a href='#main-content' class='skip-link'>Skip to main content</a> as the very first child of <body> in the root layout component; Style it: position absolute, transform translateY(-100%) by default; transform translateY(0) on :focus; Add id='main-content' to the <main> element of each page layout; Verify the link appears on Tab keypress before any nav item receives focus; Apply to both Admin System root layout and Partner Portal root layout
**Deliverable:** Skip-to-content link component integrated into both app root layouts
**Acceptance / logic checks:**
- First Tab keypress on any page reveals the skip link at the top of the viewport
- Pressing Enter on the skip link moves keyboard focus to the element with id='main-content'
- Skip link is not visible to mouse users during normal browsing
- Both Admin System and Partner Portal pages have id='main-content' on their main wrapper
- axe automated scan reports no 'bypass-block' violation on any page

### 12.5-T08 — Ensure all interactive elements have visible 2 px brand-colour focus ring (WCAG 2.4.7)  _(40 min)_
**Context:** UX-11 §8.1: visible focus indicator required on all focusable elements; 2 px brand-colour (#1A56DB) focus ring; must not be suppressed. UX-11 §2.5: text inputs already specify 2 px brand focus ring. WCAG 2.1 AA SC 2.4.7. Applies to buttons, links, inputs, select, toggle, nav items, table sort icons, and the language toggle. Focus ring must not be suppressed by outline:none or outline:0 anywhere in the codebase.
**Steps:** Add a global CSS rule: :focus-visible { outline: 2px solid var(--color-brand); outline-offset: 2px; }; Search codebase for 'outline: none', 'outline: 0', 'outline:none', 'outline:0' and remove or replace with :focus-not(:focus-visible) pattern to preserve mouse-click behaviour; For custom components (Badge, Toggle, LanguageToggle) add explicit focus-visible styles; Verify nav sidebar items, table sort headers, modal close button, and toast dismiss button all show focus ring on Tab; Run axe scan to confirm no 'focus-visible' or 'focus-indicator' violations
**Deliverable:** Global focus-ring CSS rule applied; all suppression removed from both SPAs
**Acceptance / logic checks:**
- Tabbing through the Admin Dashboard shows visible 2 px blue focus ring on every interactive element without exception
- No 'outline: none' or 'outline: 0' remains in any .css/.scss file (grep check)
- Mouse click on buttons does not show focus ring (uses :focus-visible not :focus)
- Focus ring is visible on the LanguageToggle and sidebar collapse icon
- axe or jest-axe reports zero 'focus-visible' violations on a full page render
**Depends on:** 12.5-T06

### 12.5-T09 — Add aria-label to all status badges so colour is not the sole information carrier (WCAG 1.4.1)  _(30 min)_
**Context:** UX-11 §8.1: status badges must include aria-label with full text, not just colour. WCAG 1.4.1 Use of Colour. Status values: APPROVED, DECLINED, PENDING, CANCELLED, REFUNDED, UNCERTAIN (transaction badges); DRAFT, CONFIRMED, DISPUTED (settlement badges). Badge component renders a coloured pill; screen readers must not rely on colour alone.
**Steps:** Open the shared Badge component (design-system/components/Badge.tsx or equivalent); Add aria-label prop that defaults to the status text if not explicitly provided (e.g. aria-label='Transaction status: APPROVED'); For i18n support, derive aria-label from t('badge.ariaLabel.approved') so it can be translated; Confirm that every Badge usage in transaction tables, settlement tables, and dashboard passes the status key; Write a unit test asserting aria-label equals the expected string for each of the 9 status values
**Deliverable:** Badge component with aria-label; unit tests for all 9 status values
**Acceptance / logic checks:**
- Badge rendered with status='APPROVED' has aria-label='Transaction status: Approved' (or equivalent translated string)
- Badge rendered with status='DECLINED' has aria-label='Transaction status: Declined'
- Badge rendered with status='UNCERTAIN' has aria-label containing the word 'Uncertain' (not just colour)
- Screen reader test (VoiceOver or NVDA): navigating to a badge row announces the status word, not just silence
- axe scan reports no 'color-as-sole-indicator' violation on transaction table
**Depends on:** 12.5-T01, 12.5-T06

### 12.5-T10 — Associate all form control labels via htmlFor/for and mark required fields (WCAG 1.3.1, 3.3.2)  _(45 min)_
**Context:** UX-11 §8.1 and §2.5: all form controls must have associated <label> elements with for attribute; required fields marked with asterisk (*). WCAG 1.3.1 Info and Relationships, 3.3.2 Labels or Instructions. Applies to Admin System forms: Mapping Page (margin fields m_a, m_b, service_charge_krw), Partner Config, Rate Entry, Refund Initiation, Prefunding Top-up. All inputs must be labelled; <fieldset>/<legend> used for radio/checkbox groups.
**Steps:** Audit every <input>, <select>, <textarea> in Admin System forms for presence of associated <label for='id'>; Add htmlFor on each <label> and matching id on each control where missing; Mark required fields with an asterisk in the label text and add required attribute on the input; For number inputs (e.g. m_a margin %) add aria-describedby pointing to a hint span showing allowed range (e.g. 'Min 1.0% for cross-border rules; combined m_a + m_b >= 2%'); Run axe or jest-axe on each form and fix any 'label' category violation
**Deliverable:** All Admin System form controls labelled; required fields marked; axe form-label violations = 0
**Acceptance / logic checks:**
- axe scan of Mapping Page returns 0 'label' violations
- The m_a margin input has id='margin-collection', label for='margin-collection', and aria-describedby pointing to a hint about the 2% minimum
- Submitting the Refund form without required amount field shows aria-invalid='true' and the error is linked via aria-describedby
- Screen reader announces the label text before the input when tabbing to it
- All required fields have required attribute and visible asterisk
**Depends on:** 12.5-T01

### 12.5-T11 — Add aria-describedby error linking and aria-invalid on all validated form inputs (WCAG 3.3.1)  _(45 min)_
**Context:** UX-11 §8.1: errors must be linked to fields via aria-describedby; aria-invalid='true' on invalid inputs. WCAG 3.3.1 Error Identification, 3.3.3 Error Suggestion. Inline validation is required on the Mapping Page (PRD-07): combined margin < 2% for cross-border rules must appear inline adjacent to the field, not only on Save. Error messages must be specific (e.g. 'Combined margin is 1.5% — minimum is 2.0% for cross-border rules').
**Steps:** For each validated input, add an error <span id='{field}-error' role='alert'> below the input, initially hidden; When validation fails, set aria-invalid='true' on the input, aria-describedby='{field}-error', and show the error span with the message; For cross-border margin validation: if m_a + m_b < 2.0%, set aria-invalid on both m_a and m_b inputs; message: 'Combined margin {actual}% is below the 2.0% minimum for cross-border rules'; For the rate guard-rail check: if the entered rate is outside the guard-rail range show the inline error immediately; On blur (not only on submit) validate and set/clear aria-invalid
**Deliverable:** Inline error pattern with aria-invalid and aria-describedby applied to all validated inputs in both SPAs
**Acceptance / logic checks:**
- Setting m_a=0.5% and m_b=1.0% on Mapping Page shows inline error on blur: aria-invalid='true' on both fields and error text visible
- Setting m_a=1.5% and m_b=1.0% (combined=2.5%) clears error and sets aria-invalid='false'
- Screen reader announces the error message when focus lands on the invalid input (via aria-describedby + role=alert)
- On form submit all invalid fields show errors simultaneously, not just the first
- axe scan reports 0 'form-field-multiple-labels' or 'aria-invalid' violations
**Depends on:** 12.5-T10

### 12.5-T12 — Implement focus trap in all modal dialogs (WCAG 2.1.2)  _(50 min)_
**Context:** UX-11 §8.1: focus must be confined to open modal; role='dialog' with aria-modal='true'. UX-11 §2.10: modals have explicit close button; click-outside closes non-destructive modals. WCAG 2.1.2 No Keyboard Trap (escape allowed); 2.4.3 Focus Order. Modals in Admin System: confirmation dialogs (activate scheme, revoke key, initiate refund), review-changes modal, partner detail modal. Max width 560 px (confirm) / 720 px (review/detail).
**Steps:** Create a FocusTrap hook that on mount collects all focusable elements inside the modal container and intercepts Tab/Shift+Tab to cycle within them; On modal open: save the previously focused element; move focus to the first focusable element inside the modal (or the dialog heading); On Escape keypress close the modal and restore focus to the element that triggered it; Add role='dialog', aria-modal='true', and aria-labelledby pointing to the modal heading id on all modal root divs; Verify close (X) button in header is always focusable and activatable with Enter/Space
**Deliverable:** FocusTrap hook/component applied to all modal instances in both Admin System and Partner Portal
**Acceptance / logic checks:**
- Tabbing from the last focusable element inside an open modal cycles back to the first (not to background elements)
- Pressing Escape closes the modal and returns focus to the trigger button
- Screen reader announces the modal heading when the modal opens (via aria-labelledby)
- The background page is not navigable by keyboard while a modal is open
- axe scan of an open modal returns 0 'dialog-name' or 'aria-modal' violations
**Depends on:** 12.5-T08

### 12.5-T13 — Add table accessibility markup: <th scope> headers and caption for all data tables (WCAG 1.3.1)  _(40 min)_
**Context:** UX-11 §8.1: tables must have <th scope='col'> on column headers and <th scope='row'> on row headers. WCAG 1.3.1 Info and Relationships. UX-11 §2.6: tables have sticky headers, sort icons on sortable columns, max 6 columns. Tables appear in: Transaction Search (Admin + Portal), Settlement Statements, Audit Log, Partner List, Mapping Rules. Sortable column headers need aria-sort attribute.
**Steps:** Replace <td> with <th scope='col'> for all column header cells in shared Table component; Add aria-sort='ascending' | 'descending' | 'none' to sortable <th> elements, toggling on sort click; Add a <caption> element to every table with a descriptive title (e.g. 'Transaction list') — visually hidden with .sr-only if design does not show it; For tables with row-level actions (e.g. Refund button per transaction row), add scope='row' to the first cell; Verify all table render paths (Admin transaction table, Partner transaction table, Audit Log table) have the correct markup
**Deliverable:** Shared Table component updated with <th scope>, aria-sort, and <caption>; applied to all table usages
**Acceptance / logic checks:**
- axe scan on Transaction Search page returns 0 'th-has-data-cells' or 'scope-attr-valid' violations
- Inspecting the DOM of the Partner Portal Transaction List shows <th scope='col'> on all 6 column headers
- Clicking a sortable column sets aria-sort='ascending' on that th; clicking again sets 'descending'
- Screen reader (NVDA/VoiceOver) announces column header when navigating table cells with arrow keys
- Settlement Statements table has a <caption> element containing the word 'Settlement'

### 12.5-T14 — Apply prefers-reduced-motion: limit all transitions to <= 300 ms and disable animations (WCAG 2.3.3)  _(35 min)_
**Context:** UX-11 §8.1: transitions <= 300 ms; prefers-reduced-motion media query disables animations. WCAG 2.3.3 Animation from Interactions. Animations in the codebase: sidebar collapse transition, modal open/close fade, toast slide-in, table row hover, loading spinner. All must respect the media query.
**Steps:** Audit CSS/SCSS for all transition and animation declarations; Ensure all transitions have duration <= 300 ms (e.g. sidebar collapse: 200 ms, modal fade: 150 ms); Add a global CSS block: @media (prefers-reduced-motion: reduce) { *, *::before, *::after { animation-duration: 0.01ms !important; animation-iteration-count: 1 !important; transition-duration: 0.01ms !important; } }; Verify the loading spinner and any skeleton loaders stop animating when prefers-reduced-motion is reduce; Test by enabling 'Reduce Motion' in macOS/Windows accessibility settings and navigating both SPAs
**Deliverable:** Global CSS prefers-reduced-motion block; all transitions capped at 300 ms
**Acceptance / logic checks:**
- In normal mode sidebar collapse takes <= 200 ms (measure in DevTools Performance tab)
- In prefers-reduced-motion=reduce mode all CSS transitions complete in <= 1 ms
- Modal open/close shows no fade animation with reduce-motion enabled
- Loading spinner is static (no rotation) when reduce-motion is active
- grep for 'transition-duration' values > 300ms in all CSS files returns 0 results

### 12.5-T15 — Implement responsive sidebar: icon-only collapse at 1,280-1,439 px, hamburger overlay at 1,024-1,279 px  _(55 min)_
**Context:** UX-11 §1.3 and §8.3: Admin System sidebar collapses to icon-only at 1,280-1,439 px (desktop-min breakpoint). At tablet landscape (1,024-1,279 px) sidebar is hidden, hamburger icon shows an overlay. Partner Portal: same behaviour. No mobile support (< 768 px) in Phase 1. CSS breakpoints: >= 1,440 px = desktop default; 1,280-1,439 px = desktop-min; 1,024-1,279 px = tablet-landscape.
**Steps:** Define CSS custom breakpoint variables: --bp-desktop 1440px, --bp-desktop-min 1280px, --bp-tablet-landscape 1024px; At 1,280-1,439 px: sidebar width collapses from 220 px to 56 px (icon-only); nav item text hidden; tooltip on hover shows nav label; At 1,024-1,279 px: sidebar hidden (display:none or translateX(-100%)); hamburger button (3-line icon) appears in header; click opens sidebar as overlay with scrim; Hamburger button must have aria-label='Open navigation' and aria-expanded reflecting open/closed state; Sidebar overlay must close on Escape and on click outside (same focus-trap pattern as modals for keyboard users)
**Deliverable:** Responsive sidebar component with 3-breakpoint behaviour in both SPAs
**Acceptance / logic checks:**
- At viewport width 1,350 px sidebar shows icons only (no text labels visible); nav labels appear in tooltip on hover
- At viewport width 1,100 px hamburger button is visible; clicking it opens sidebar as overlay
- At viewport width 1,100 px sidebar is not in the DOM tab order when closed (aria-hidden='true' or display:none)
- hamburger aria-expanded='true' when sidebar overlay is open, 'false' when closed
- At viewport width >= 1,440 px full sidebar with text labels and icons is visible
**Depends on:** 12.5-T08

### 12.5-T16 — Implement responsive table column collapse at tablet landscape (1,024 px): 3 primary columns + expand row  _(50 min)_
**Context:** UX-11 §8.3: at tablet landscape (1,024-1,279 px) table columns are reduced to the 3 most critical visible columns; a '+' expand control per row reveals suppressed columns. Applies to Admin System Transaction Search table (6 columns: Date, Partner, Amount, Payout, Status, TxnID) and Partner Portal Transaction List (same). Critical columns retained: Date, Amount/Payout, Status.
**Steps:** Add a responsive column configuration object to the Table component: { column: 'date', priority: 1 }, { column: 'amount', priority: 1 }, { column: 'status', priority: 1 }, lower-priority columns hidden at tablet-landscape breakpoint; At <= 1,279 px hide columns with priority > 1 via CSS class or JS media query hook; Add a '+' expand button as the last column in each row; clicking toggles an inline detail row (or row expansion) showing hidden column values; The expand control must be keyboard-accessible (Enter/Space) and have aria-expanded attribute; Ensure column headers update: at tablet-landscape only 3 <th> cells are visible (hidden ones not in tab order)
**Deliverable:** Table component with priority-based column collapse and per-row expand control; applied to transaction tables
**Acceptance / logic checks:**
- At 1,100 px viewport only Date, Amount, and Status columns are visible in the transaction table header
- Clicking '+' on row 1 reveals the hidden TxnID, Partner, and Payout values in an expanded row or inline panel
- The '+' button has aria-expanded='false' by default and 'true' when row is expanded
- Pressing Enter on '+' expands the row without mouse interaction
- At >= 1,440 px all 6 columns are visible and the '+' expand button is not shown
**Depends on:** 12.5-T13, 12.5-T15

### 12.5-T17 — Enforce date/number formatting rules per i18n spec across all display components  _(40 min)_
**Context:** UX-11 §8.2 and §2.8: Phase 1 date format is YYYY-MM-DD (ISO 8601) throughout; number format is comma thousands separator, period decimal; KRW has 0 decimal places, other currencies 2 decimal places; exchange rates 6 significant figures. Money format: '{currency_code} {amount}' e.g. 'USD 10,234.56', 'KRW 45,000'. Timezone display: KST (UTC+9) in Admin portal; partner configured timezone (default KST) in Partner Portal.
**Steps:** Create a formatMoney(amount, currency) utility: if currency='KRW' output integer with comma separator (e.g. 'KRW 45,000'); else output 2dp with comma separator (e.g. 'USD 10,234.56'); Create a formatDate(utcString) utility: returns YYYY-MM-DD HH:MM KST by converting UTC to UTC+9; Create a formatRate(rate) utility: returns 6 significant figures with comma separator if >= 1000 (e.g. '1,350.42' for KRW/USD); Replace all inline date, money, and rate rendering in both SPAs with these utilities; Add unit tests for edge cases: KRW 0, USD 0.00, rate 0.000740, rate 1350.42
**Deliverable:** formatMoney, formatDate, formatRate utility functions; all display components using them
**Acceptance / logic checks:**
- formatMoney(45000, 'KRW') returns 'KRW 45,000' (no decimal point)
- formatMoney(10234.56, 'USD') returns 'USD 10,234.56'
- formatDate('2026-06-05T03:00:00Z') returns '2026-06-05 12:00 KST' (UTC+9)
- formatRate(1350.42) returns '1,350.42'; formatRate(0.000740) returns '0.000740'
- A transaction row in the Admin Transaction table showing KRW payout renders without decimal places
**Depends on:** 12.5-T01

### 12.5-T18 — Set html lang attribute and document title per locale on each SPA page (WCAG 3.1.1, 3.1.2)  _(35 min)_
**Context:** WCAG 3.1.1: the language of the page must be programmatically determined (html lang attribute). WCAG 3.1.2: the language of page parts that differ from the document language must be identified. Both SPAs are SPAs so the lang attribute must update dynamically when the locale changes. Document titles must also be translated. Admin System default lang='en'; Partner Portal default lang='en'. When Korean is active: lang='ko'.
**Steps:** In the i18n change-language handler, update document.documentElement.lang to the new locale code ('en' or 'ko'); Set document.title on each route change using a translated title from en.json/ko.json (e.g. t('page.transactionSearch.title') = 'Transaction Search — GMEPay+'); For mixed-language content (e.g. a Korean merchant name inside an English Admin UI) wrap the element with lang='ko'; Verify lang='en' is set on initial load before any user interaction; Add a Helmet or equivalent document-head manager component to handle dynamic title and lang updates
**Deliverable:** Dynamic html lang and document title management integrated into both SPAs
**Acceptance / logic checks:**
- On Admin System load: document.documentElement.lang === 'en'
- After switching to Korean locale: document.documentElement.lang === 'ko'
- document.title changes on route navigation to match the current page title in the active locale
- axe reports 0 'html-has-lang' or 'html-lang-valid' violations on any page
- A Korean merchant name element has lang='ko' attribute in the DOM
**Depends on:** 12.5-T04

### 12.5-T19 — Add keyboard navigation for data tables: arrow-key cell navigation and column sort via keyboard (WCAG 2.1.1)  _(40 min)_
**Context:** WCAG 2.1.1: all functionality must be operable via keyboard. UX-11 §2.6: tables have sortable columns (sort icon, active sort highlighted). Sortable column headers must be activatable with Enter/Space. Table cells in Admin Transaction Search and Partner Portal Transaction List must be navigable by arrow keys (ARIA grid pattern) or at minimum Tab to each row action button. Sort state must be announced to screen readers via aria-sort.
**Steps:** Make sortable <th> elements focusable (tabIndex=0) and add onKeyDown handler for Enter/Space to trigger sort; Add aria-sort='none' | 'ascending' | 'descending' to each sortable <th>; update on sort change; For row-level action buttons (e.g. View Details): ensure each button is in the tab order and reachable via Tab; Optionally implement arrow-key navigation within the table grid using role='grid' and role='gridcell' with roving tabIndex pattern; Verify that after sorting, the first data row receives focus (or the active sort th retains focus)
**Deliverable:** Keyboard-accessible sortable table headers with aria-sort in both SPAs
**Acceptance / logic checks:**
- Tabbing into the Transaction table header reaches the Date column th with visible focus ring
- Pressing Enter on a sortable th sorts the column; aria-sort changes to 'ascending' or 'descending'
- Pressing Enter again on the same th reverses the sort; aria-sort changes
- All row-level 'View' buttons are reachable via Tab without mouse interaction
- axe scan reports 0 'aria-required-children' violations on the table
**Depends on:** 12.5-T08, 12.5-T13

### 12.5-T20 — Audit and fix heading hierarchy on all pages (WCAG 1.3.1, 2.4.6)  _(40 min)_
**Context:** WCAG 1.3.1: info and relationships conveyed through presentation must be programmatically determinable. WCAG 2.4.6: headings describe the topic or purpose. Both SPAs must have a logical heading hierarchy (h1 > h2 > h3) on each page. UX-11 §2.1 typography: page title = H1 (24 px 600), section heading = H2 (18 px 600), subsection = H3 (15 px 600). Each page must have exactly one H1.
**Steps:** Use browser DevTools or a heading-order audit tool to map the heading tree on each Admin System page (Dashboard, Transaction Search, Mapping Page, Partner Detail, Audit Log) and each Partner Portal page; Fix pages with missing H1, duplicate H1, or skipped heading levels (e.g. H1 -> H3); Ensure modal headings are H2 (one level below the page H1) with matching id referenced by aria-labelledby; Verify that section card titles are H2 and subsection labels within cards are H3; Run axe scan on all major pages and resolve 'heading-order' violations
**Deliverable:** Corrected heading hierarchy across all pages in both SPAs; axe 'heading-order' violations = 0
**Acceptance / logic checks:**
- Each page has exactly one H1 element containing the page title
- No heading level is skipped (H1 to H3 without H2) on any page
- Modal dialog heading is H2 with id referenced by the modal's aria-labelledby
- axe scan returns 0 'heading-order' violations on Dashboard, Transaction Search, and Mapping Page
- DOM inspection of Partner Portal Dashboard shows: H1 'Dashboard', H2 'Transaction Summary', H2 'Prefunding Balance'

### 12.5-T21 — Ensure toast notifications are announced by screen readers (WCAG 4.1.3 Status Messages)  _(35 min)_
**Context:** UX-11 §2.10: toasts are top-right, auto-dismiss 5 s (success/info) or persistent (error). WCAG 4.1.3: status messages communicated without focus change must be programmatically determinable via role='status' (polite) or role='alert' (assertive). Success/info toasts use role='status' (aria-live='polite'); error toasts use role='alert' (aria-live='assertive'). Max 3 simultaneous toasts stacked.
**Steps:** Ensure the toast container div has role='status' aria-live='polite' aria-atomic='false' for the success/info slot; Add a separate container with role='alert' aria-live='assertive' aria-atomic='true' for error toasts; When a new toast is added, insert it into the appropriate container (not into a shared div); Verify that dynamically added toast text is read by a screen reader without requiring focus change; For the max-3 limit: when a 4th toast is queued, dismiss the oldest non-error toast before inserting the new one
**Deliverable:** Toast component with role='status'/'alert' live regions; screen reader announcement verified
**Acceptance / logic checks:**
- Adding a success toast announces text to screen reader via polite live region without stealing focus
- Adding an error toast announces text immediately via assertive live region
- When 3 error toasts are active, a 4th toast attempt does not silently drop — it replaces the oldest non-error toast
- axe scan reports 0 'aria-live-region-wordy' or missing live-region violations on the toast container
- DOM inspection shows two separate live-region containers: one role='status', one role='alert'
**Depends on:** 12.5-T01

### 12.5-T22 — Verify image and icon accessibility: alt text and decorative-icon aria-hidden (WCAG 1.1.1)  _(35 min)_
**Context:** WCAG 1.1.1: all non-text content must have a text alternative. GMEPay+ UIs use icon-only buttons (sidebar collapse, sort arrows, hamburger, close modal X) and the GMEPay+ logo. Icon-only buttons need aria-label. Decorative icons (purely visual) must have aria-hidden='true'. The GME logo in the header needs alt text.
**Steps:** Audit all <img> elements: add descriptive alt text; for decorative images add alt='' and aria-hidden='true'; Audit all icon-only buttons and links (SVG icons from library): add aria-label describing the action; Set aria-hidden='true' on all inline SVG icons that are accompanied by visible text labels (decorative in context); For the sidebar nav items at icon-only mode (T15), icons must have aria-hidden='true' because the tooltip provides the label; the <a> must have aria-label; Run axe to check for 'image-alt' and 'button-name' violations
**Deliverable:** All images and icons compliant with WCAG 1.1.1; axe 'image-alt' and 'button-name' violations = 0
**Acceptance / logic checks:**
- GMEPay+ logo <img> has a non-empty alt attribute (e.g. alt='GMEPay+ logo')
- Hamburger menu <button> has aria-label='Open navigation' (no visible text)
- Modal close X <button> has aria-label='Close' (no visible text)
- All sidebar nav icons in icon-only mode have aria-hidden='true' on the SVG
- axe scan returns 0 'image-alt' and 0 'button-name' violations on any page
**Depends on:** 12.5-T15

### 12.5-T23 — Configure eslint-plugin-jsx-a11y to enforce accessibility rules in CI pipeline  _(40 min)_
**Context:** WBS 12.5 requires WCAG AA compliance to be maintained, not just fixed once. eslint-plugin-jsx-a11y provides static analysis of JSX accessibility attributes. Rules needed: jsx-a11y/label-has-associated-control, aria-props, alt-text, no-aria-hidden-on-focusable, interactive-supports-focus, anchor-is-valid, click-events-have-key-events. Must run in CI to prevent regressions.
**Steps:** Add eslint-plugin-jsx-a11y and @typescript-eslint/parser to devDependencies in package.json; Extend ESLint config with plugin:jsx-a11y/recommended and enable strict additional rules for label-has-associated-control, interactive-supports-focus, and no-aria-hidden-on-focusable; Add the lint step to CI pipeline (GitHub Actions or equivalent) before build; fail the pipeline on any jsx-a11y error; Fix any new violations surfaced by the plugin that were not caught in T07-T22; Document the rule set in .eslintrc comments so future developers understand which a11y rules are enforced
**Deliverable:** .eslintrc config with jsx-a11y/recommended + strict rules; CI pipeline lint step that fails on a11y violations
**Acceptance / logic checks:**
- Running 'npx eslint src/' on the codebase with the new config exits 0 (no violations)
- Introducing a deliberate <img> without alt attribute causes CI lint step to fail with the jsx-a11y/alt-text rule
- The jsx-a11y/label-has-associated-control rule fires when a <label> lacks a for attribute
- CI pipeline log shows the lint step completing before the build step
- package.json devDependencies include eslint-plugin-jsx-a11y >= 6.x
**Depends on:** 12.5-T07, 12.5-T08, 12.5-T09, 12.5-T10, 12.5-T11, 12.5-T12, 12.5-T13

### 12.5-T24 — Run axe-core automated accessibility scan against all major pages and fix remaining violations  _(60 min)_
**Context:** WBS 12.5 WCAG AA compliance requires a full automated scan after component-level fixes (T07-T22). axe-core (via @axe-core/react or jest-axe) must be run against: Admin System Dashboard, Transaction Search, Mapping Page, Partner Detail, Audit Log; Partner Portal Dashboard, Transaction List, Settlement Statements. Target: 0 critical and major violations.
**Steps:** Install @axe-core/react for development-mode scanning and jest-axe for CI test scanning; Add a jest-axe test file: for each major page render the component tree with mock data and call expect(await axe(container)).toHaveNoViolations(); Run the tests; triage all reported violations by WCAG criterion; Fix any critical or serious violations not addressed by T07-T22 (document each fix with the violation rule ID); Add the jest-axe tests to CI so regressions fail the build
**Deliverable:** jest-axe test suite for 8+ major pages; all critical/serious axe violations resolved and tests passing in CI
**Acceptance / logic checks:**
- jest-axe test for Admin Dashboard returns toHaveNoViolations() passing (0 critical/serious)
- jest-axe test for Mapping Page passes including form label and aria-invalid checks
- jest-axe test for Partner Portal Transaction List passes including th scope and aria-sort checks
- CI pipeline includes the jest-axe test step and it passes on main branch
- Any new axe critical violation introduced by a future PR fails CI automatically
**Depends on:** 12.5-T07, 12.5-T08, 12.5-T09, 12.5-T10, 12.5-T11, 12.5-T12, 12.5-T13, 12.5-T14, 12.5-T19, 12.5-T20, 12.5-T21, 12.5-T22

### 12.5-T25 — Manual keyboard-navigation smoke test: full tab traversal of Admin System (WCAG 2.1.1)  _(55 min)_
**Context:** Automated axe scans do not catch all keyboard-navigation issues. A manual keyboard test is required: full Tab traversal of all Admin System pages without a mouse. WCAG 2.1.1 Keyboard, 2.4.3 Focus Order, 2.4.7 Focus Visible. Test covers: Login, Dashboard, Transaction Search (including sorting), Mapping Page (form interaction), Refund Confirmation modal, Audit Log.
**Steps:** Using only keyboard (Tab, Shift+Tab, Enter, Space, Escape, arrow keys) navigate through each Admin System screen; For each screen verify: all interactive elements are reachable, focus order is logical (top-to-bottom, left-to-right), focus ring is always visible; Test modal lifecycle: open modal with Enter, trap focus inside, close with Escape, verify focus returns; Test table sort: Tab to sortable th, press Enter, verify sort activates, verify aria-sort changes; Document any issues found as defects linked to the relevant ticket; all P1 defects must be fixed before this ticket is closed
**Deliverable:** Manual test report (keyboard-navigation-test.md in /docs/qa/) documenting pass/fail for each screen; all P1 issues fixed
**Acceptance / logic checks:**
- All interactive elements on Login page reachable by Tab without mouse
- Mapping Page m_a and m_b inputs reachable by Tab; inline validation error appears on blur with keyboard-only interaction
- Refund confirmation modal: focus trapped inside; Escape closes and restores focus to Refund button
- Transaction table sort header: Tab + Enter activates sort; visual aria-sort indicator changes
- No focus loss (focus disappears or jumps to body) during any keyboard navigation path
**Depends on:** 12.5-T07, 12.5-T08, 12.5-T11, 12.5-T12, 12.5-T15, 12.5-T19

### 12.5-T26 — Manual keyboard-navigation smoke test: full tab traversal of Partner Portal (WCAG 2.1.1)  _(50 min)_
**Context:** Same manual keyboard test as T25 but for the Partner Portal. Screens: Login, Dashboard (summary panel + alerts panel), Transaction List (sort + expand row), Settlement Statements, Prefunding Balance. UX-11 §10.2: read-only portal — no destructive actions; no confirmation dialogs. Responsive: at 1,024 px tablet-landscape sidebar is hidden and hamburger is used.
**Steps:** Using keyboard only navigate all Partner Portal screens including at 1,024 px viewport width; For each screen verify all interactive elements reachable; focus order logical; focus ring always visible; Test hamburger sidebar at 1,024 px: keyboard activation opens overlay; Escape closes overlay; focus returns to hamburger; Test transaction row expand (T16): Tab to '+' button, Enter expands row, Enter/Space collapses row; Document pass/fail per screen in keyboard-navigation-test-portal.md; fix any P1 issues
**Deliverable:** Manual test report (keyboard-navigation-test-portal.md in /docs/qa/); all P1 issues fixed
**Acceptance / logic checks:**
- At 1,440 px all 6 transaction columns reachable by Tab; at 1,100 px only 3 visible columns plus '+' expand reachable
- Hamburger at 1,100 px: Enter opens overlay; Tab navigates nav items inside overlay; Escape closes overlay
- Row expand '+' button: Enter expands row and announces new content to screen reader (live region or focus shift)
- Transaction List sort header: Tab + Enter sorts column; aria-sort updates
- No interactive element is skipped or unreachable by keyboard on any Partner Portal page
**Depends on:** 12.5-T15, 12.5-T16, 12.5-T19, 12.5-T22

### 12.5-T27 — Screen reader smoke test: VoiceOver/NVDA traversal of key flows (WCAG 1.3, 2.4, 4.1)  _(55 min)_
**Context:** Automated axe and manual keyboard tests do not verify screen reader output. WCAG requires programmatic determinability of information. Test flows: (1) Admin Mapping Page — form labels, error messages, and confirmation modal announced correctly; (2) Transaction Search — table headers, status badges (aria-label), and sort state announced; (3) Toast notifications — announced without focus change. Test with VoiceOver (macOS Safari) or NVDA (Windows Chrome).
**Steps:** With VoiceOver or NVDA active, navigate the Mapping Page: verify each input label is announced, verify combined-margin error message is announced on blur when m_a+m_b < 2%; Navigate the Transaction Search table: verify column headers announced on cell entry, verify APPROVED badge announced as 'Transaction status: Approved' not just 'Approved'; Trigger a success toast: verify screen reader announces it without focus moving; Open and close a confirmation modal: verify heading announced on open, focus trap confirmed auditorily, focus return on close announced; Document any screen reader output that does not match expected; fix P1 issues
**Deliverable:** Screen reader test report (screen-reader-test.md in /docs/qa/); all P1 issues fixed
**Acceptance / logic checks:**
- VoiceOver/NVDA announces 'Margin collection, required, edit text' (or equivalent) when landing on m_a input
- After entering m_a=0.5% and tabbing out, screen reader announces the error message about combined margin below 2%
- Screen reader announces 'Transaction status: Approved' (not colour name) for APPROVED badge cells
- Success toast is announced within 1 second of display without focus change
- Confirmation modal heading is announced immediately when modal opens; background content is not announced while modal is open
**Depends on:** 12.5-T09, 12.5-T10, 12.5-T11, 12.5-T12, 12.5-T21, 12.5-T24

### 12.5-T28 — Unit tests: i18n key completeness and formatting utility correctness  _(45 min)_
**Context:** WBS 12.5 logic-bearing work includes the formatMoney, formatDate, formatRate utilities (T17) and the i18n key files (T01-T05). These must have explicit unit test coverage. Test vectors are derived from UX-11 §2.8 and NFR-10 §9.2: KRW no decimal, USD 2dp, rate 6 sig figs, date ISO 8601 to KST conversion. ko.json key count must equal en.json key count.
**Steps:** Write Jest unit tests for formatMoney: test vectors: (0, 'KRW') -> 'KRW 0'; (45000, 'KRW') -> 'KRW 45,000'; (10234.56, 'USD') -> 'USD 10,234.56'; (0.01, 'USD') -> 'USD 0.01'; (1234567.89, 'MNT') -> 'MNT 1,234,567.89'; Write tests for formatDate: ('2026-06-05T00:00:00Z') -> '2026-06-05 09:00 KST'; ('2026-12-31T15:00:00Z') -> '2027-01-01 00:00 KST'; Write tests for formatRate: (1350.42) -> '1,350.42'; (0.000740) -> '0.000740'; (1.0) -> '1.00000'; Write a test that loads en.json and ko.json and asserts Object.keys(en).length === Object.keys(ko).length; Run all tests and ensure 100% pass rate
**Deliverable:** Jest unit test file src/i18n/__tests__/formatting.test.ts with >= 15 test cases; all passing
**Acceptance / logic checks:**
- formatMoney(45000, 'KRW') returns exactly 'KRW 45,000' (0 decimal places, comma separator)
- formatMoney(10234.56, 'USD') returns exactly 'USD 10,234.56' (2 decimal places, comma separator)
- formatDate('2026-06-05T00:00:00Z') returns '2026-06-05 09:00 KST' (UTC+9 conversion)
- formatRate(0.000740) returns '0.000740' (6 significant figures)
- Key-count test: en.json and ko.json have identical key count (test fails if ko.json is missing any key)
**Depends on:** 12.5-T05, 12.5-T17

### 12.5-T29 — Unit tests: responsive breakpoint logic for sidebar and table column collapse  _(45 min)_
**Context:** WBS 12.5 responsive behaviour (T15, T16) involves conditional logic based on viewport width. Unit/component tests must verify: sidebar collapse state at each breakpoint, table column visibility at each breakpoint, and hamburger open/close state. Tests use React Testing Library with jsdom viewport resize or a custom matchMedia mock.
**Steps:** Mock window.matchMedia in test setup to simulate different viewport widths; Write a component test for the Sidebar: at 1,350 px verify icon-only mode (text labels hidden, icons visible); at 1,100 px verify sidebar hidden and hamburger visible; at 1,500 px verify full sidebar; Write a component test for the Table with responsive config: at 1,100 px verify only 3 columns rendered in header; at 1,500 px verify 6 columns rendered; Write a test for the hamburger toggle: click hamburger -> aria-expanded='true', overlay visible; click again -> aria-expanded='false', overlay hidden; Ensure all tests pass in CI with the matchMedia mock
**Deliverable:** Component test file src/components/__tests__/responsive.test.tsx with breakpoint tests; all passing
**Acceptance / logic checks:**
- Sidebar test at 1,350 px: sidebar container has class 'collapsed' or width 56 px; nav link text nodes are not rendered
- Sidebar test at 1,100 px: sidebar has aria-hidden='true' or is not in DOM; hamburger button is rendered
- Table test at 1,100 px: only 3 <th> elements visible in table header row
- Hamburger toggle test: aria-expanded toggles correctly on click; overlay appears/disappears
- All tests pass in CI without real browser viewport (using matchMedia mock)
**Depends on:** 12.5-T15, 12.5-T16

### 12.5-T30 — Unit tests: focus trap hook correctness (keyboard cycling within modal)  _(40 min)_
**Context:** WBS 12.5 modal focus trap (T12) is logic-bearing: the hook must correctly cycle Tab and Shift+Tab within the focusable elements of an open modal and restore focus on close. Unit tests use React Testing Library and userEvent. Test scenarios: forward cycle at last element, backward cycle at first element, Escape closes and restores focus, aria-modal attribute present.
**Steps:** Write a test that renders a modal with 3 focusable elements (input, button, close-X); simulate Tab from last element; assert focus moves to first element; Write a test that simulates Shift+Tab from first element; assert focus moves to last element; Write a test that simulates Escape keypress inside modal; assert modal is closed and a trigger button outside the modal receives focus; Write a test that asserts the modal root has role='dialog' and aria-modal='true' attributes; Write a test that asserts aria-labelledby on the modal root points to the modal heading id
**Deliverable:** Jest test file src/components/__tests__/focusTrap.test.tsx with >= 5 test cases; all passing
**Acceptance / logic checks:**
- Tab from last focusable element cycles to first (focus does not leave modal)
- Shift+Tab from first focusable element cycles to last
- Escape closes modal and moves focus to the element that originally opened it
- Modal root has role='dialog' and aria-modal='true' in the DOM
- aria-labelledby value matches the id of the modal heading element
**Depends on:** 12.5-T12

### 12.5-T31 — Write and publish WCAG AA compliance checklist and i18n expansion guide for developers  _(45 min)_
**Context:** WBS 12.5 deliverable includes documentation so future developers can maintain compliance. UX-11 A-05 notes that i18n key files must be maintained. The document must cover: how to add a new key (en.json + ko.json), how to add a new page with correct heading hierarchy and skip-link wiring, how to add a new form control with label + aria-describedby pattern, how to add a new status badge with aria-label. Also covers running the axe test suite locally.
**Steps:** Create docs/dev/a11y-i18n-guide.md covering: project a11y standards summary (WCAG 2.1 AA), how to run axe tests (jest-axe and @axe-core/react), how to add i18n keys (step-by-step), how to wire skip-link on a new page, how to add a labelled form control, how to add a new status badge with aria-label; Include a quick reference table: WCAG criterion, component pattern, code example; Add a checklist section for PR review: 8 checkboxes a reviewer must verify before approving any UI change; Link the document from the main project README; Keep the document <= 3 pages (concise for adoption)
**Deliverable:** docs/dev/a11y-i18n-guide.md covering WCAG patterns, i18n key workflow, and PR checklist
**Acceptance / logic checks:**
- Document contains a section titled 'Adding a new i18n key' with step-by-step instructions including updating both en.json and ko.json
- Document contains a section titled 'Adding a new form control' with code example showing htmlFor, aria-describedby, and aria-invalid pattern
- Document contains a PR checklist with at least 8 verifiable items (e.g. 'focus ring not suppressed', 'label has htmlFor', 'aria-label on icon buttons')
- Document includes the command to run jest-axe tests locally
- Document is linked from the project README.md
**Depends on:** 12.5-T23, 12.5-T24
