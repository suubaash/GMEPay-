'use client';

import { useCallback, useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Divider,
  Step,
  StepLabel,
  Stepper,
  Typography,
} from '@mui/material';
import Breadcrumbs from '@/components/Breadcrumbs';
import ErrorAlert from '@/components/ErrorAlert';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import { useSnackbar } from '@/components/SnackbarProvider';
import { useAppDispatch, useAppSelector } from '@/store';
import {
  clearError,
  fetchContacts,
  fetchDraft,
  resetCurrent,
} from '@/store/draftsSlice';
import IdentityForm from './step-1/IdentityForm';
import ContactsForm from './step-2/ContactsForm';
import KybForm from './step-3/KybForm';
import BankAccountsSection from './step-4/BankAccountsSection';
import SettlementPanel from './step-4/SettlementPanel';
import PrefundingForm from './step-5/PrefundingForm';
import { Step6CommercialForm } from './step-6/page';

/**
 * Partner Setup wizard shell (Slice 1, agent 1D.1).
 *
 * Eight stepper tabs matching docs/PARTNER_SETUP_PLAN.md §"Slice 1..8":
 *   1. Identity   (this slice, real form lives in 1D.2)
 *   2. Contacts          (Slice 2)
 *   3. KYB               (Slice 3)
 *   4. Banking           (Slice 4)
 *   5. Prefunding        (Slice 5, OVERSEAS only)
 *   6. Commercial        (Slice 6)
 *   7. Schemes           (Slice 7)
 *   8. Activate          (Slice 8)
 *
 * Persistence model (ADR-012): every step lives on the BFF as a separate
 * PATCH /v1/admin/partners/draft/{partnerCode}/step-{n}. Next/Back call
 * the current step's PATCH and then move the cursor; Cancel returns to
 * /partners without saving. Closing the browser and returning by URL
 * resumes mid-edit because all state lives on the server.
 *
 * Steps 2..8 render the "Coming in Slice N" placeholder via
 * {@link ComingSoonStep} and disable Next. Step 1's real form belongs to
 * agent 1D.2 — for now this shell renders the StepHeader + a placeholder
 * note so the route is callable and clickable end-to-end.
 */
export const STEPS = [
  { key: 'identity', label: 'Identity', slice: 1 },
  { key: 'contacts', label: 'Contacts', slice: 2 },
  { key: 'kyb', label: 'KYB', slice: 3 },
  { key: 'banking', label: 'Banking', slice: 4 },
  { key: 'prefunding', label: 'Prefunding', slice: 5 },
  { key: 'commercial', label: 'Commercial', slice: 6 },
  { key: 'schemes', label: 'Schemes', slice: 7 },
  { key: 'activate', label: 'Activate', slice: 8 },
];

/**
 * Standard "this step is not built yet" panel. Used for steps 2..8 in
 * Slice 1; later slices replace the corresponding case in renderStep().
 */
function ComingSoonStep({ stepNumber, label, sliceNumber }) {
  return (
    <Alert severity="info" variant="outlined" sx={{ my: 2 }}>
      <Typography variant="body1" sx={{ fontWeight: 600 }}>
        Step {stepNumber}: {label}
      </Typography>
      <Typography variant="body2">
        Coming in Slice {sliceNumber}. The wizard shell ships in Slice 1 so
        operators can navigate the full structure; the form for this step
        will land with its slice.
      </Typography>
    </Alert>
  );
}

/**
 * Step 1 (Identity) body — delegates to the real {@link IdentityForm}
 * component (agent 1D.2). The form owns RHF + Yup state and dispatches
 * {@code patchStep1} on submit; this wrapper just hands it the loaded
 * draft and the callback that advances the wizard cursor.
 */
function IdentityStep({ draft, partnerCode, onSaved }) {
  return <IdentityForm draft={draft} partnerCode={partnerCode} onSaved={onSaved} />;
}

/**
 * Step 2 (Contacts) body — delegates to {@link ContactsForm} (agent 2A.2).
 * On mount we lazily fetch the current contact list from the BFF so the
 * form pre-populates for a returning operator; the contacts are merged onto
 * the draft view before being handed to the form.
 */
function ContactsStep({ draft, partnerCode, onSaved, dispatch }) {
  const { contacts, contactsLoading } = useAppSelector((s) => s.drafts);

  // Fetch existing contacts when this step first mounts.
  useEffect(() => {
    if (partnerCode) dispatch(fetchContacts(partnerCode));
  }, [partnerCode, dispatch]);

  // Merge persisted contacts onto the draft view so ContactsForm can
  // initialise its field-array from the BFF's current state.
  const draftWithContacts = { ...draft, contacts };

  if (contactsLoading) {
    return <LoadingSkeleton variant="page" />;
  }

  return (
    <ContactsForm
      draft={draftWithContacts}
      partnerCode={partnerCode}
      onSaved={onSaved}
    />
  );
}

/**
 * Wizard shell. `activeStep` is the 1-based step the operator is viewing;
 * passed in by the per-step route file (.../step-1/page.jsx) so the URL
 * always agrees with the active stepper position.
 */
export function PartnerDraftWizard({ activeStep = 1 }) {
  const params = useParams();
  const router = useRouter();
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();
  const { current, loading, saving, error } = useAppSelector((s) => s.drafts);

  const partnerCode = decodePartnerCode(params?.draftCode);

  // Local cursor so Next/Back doesn't require a route navigation for every
  // click — the page-level routes (/step-1) set the initial cursor and the
  // wizard re-renders entirely client-side as the operator moves between
  // steps. This keeps the URL as a stable resume anchor without bouncing
  // through next/navigation on every Next.
  const [cursor, setCursor] = useState(clampStep(activeStep));

  // Sync the cursor whenever the route's activeStep prop changes (deep-
  // links to /step-3 etc. once those routes ship).
  useEffect(() => {
    setCursor(clampStep(activeStep));
  }, [activeStep]);

  const reload = useCallback(() => {
    if (partnerCode) dispatch(fetchDraft(partnerCode));
  }, [dispatch, partnerCode]);

  useEffect(() => {
    reload();
    return () => {
      dispatch(resetCurrent());
    };
    // resetCurrent on unmount so a different draft loaded next doesn't
    // briefly flash the previous draft's stamps under the new URL.
  }, [reload, dispatch]);

  const isFirst = cursor === 1;
  const isLast = cursor === STEPS.length;
  const stepDef = STEPS[cursor - 1];
  // Steps 1, 2, 3, and 4 each own their own RHF-validated submit button, so the
  // wizard shell hides its generic "Save & next" affordance for those steps.
  // Later steps that use the shell's own Next can set this false once they
  // land with their slice.
  const stepHasOwnSubmit = cursor === 1 || cursor === 2 || cursor === 3 || cursor === 4 || cursor === 5 || cursor === 6;

  const onBack = () => {
    dispatch(clearError());
    setCursor((c) => Math.max(1, c - 1));
  };

  const onCancel = () => {
    dispatch(resetCurrent());
    router.push('/partners');
  };

  /**
   * Advance the cursor by one step. Bound to the IdentityForm's onSaved
   * callback so a successful Step-1 PATCH moves the wizard to Step 2
   * without a route navigation. Also used by the wizard-level Next button
   * for steps 2..8 (which today only show ComingSoon placeholders).
   */
  const advanceCursor = useCallback(() => {
    setCursor((c) => Math.min(STEPS.length, c + 1));
  }, []);

  const onNext = async () => {
    if (isLast) return;
    if (!partnerCode) {
      snackbar.error('No draft is loaded — return to /partners and pick one.');
      return;
    }
    // Step 1 has its own form submit — see step1HasOwnSubmit; the wizard's
    // Next button is hidden in that case. For steps 2..8 we just advance
    // the cursor so the operator can preview the wizard structure; their
    // real PATCH calls land with each later slice.
    advanceCursor();
  };

  if (!partnerCode) {
    return (
      <ErrorAlert
        title="No draft code in URL"
        message="The wizard URL should be /partners/draft/{partnerCode}."
        onRetry={() => router.push('/partners')}
      />
    );
  }

  if (loading && !current) {
    return <LoadingSkeleton variant="page" />;
  }

  return (
    <Box>
      <Breadcrumbs
        crumbs={[
          { label: 'Partners', href: '/partners' },
          { label: `Draft ${partnerCode}` },
        ]}
      />
      <Typography variant="h1" gutterBottom>
        Partner setup
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Editing draft <strong>{partnerCode}</strong>. Server-side persistence
        per ADR-012 — close the tab any time, return by URL to resume.
      </Typography>

      <ErrorAlert
        message={error}
        title="Wizard error"
        onRetry={() => dispatch(clearError())}
      />

      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Stepper activeStep={cursor - 1} alternativeLabel>
            {STEPS.map((s, idx) => (
              <Step key={s.key} completed={idx + 1 < cursor}>
                <StepLabel>{s.label}</StepLabel>
              </Step>
            ))}
          </Stepper>
        </CardContent>
      </Card>

      <Card>
        <CardContent>
          {renderStep(cursor, stepDef, current, partnerCode, advanceCursor, dispatch)}

          <Divider sx={{ my: 3 }} />

          <Box sx={{ display: 'flex', gap: 1, justifyContent: 'space-between' }}>
            <Button onClick={onCancel} color="inherit">
              Cancel
            </Button>
            <Box sx={{ display: 'flex', gap: 1 }}>
              <Button onClick={onBack} disabled={isFirst || saving}>
                Back
              </Button>
              {!stepHasOwnSubmit ? (
                <Button
                  variant="contained"
                  onClick={onNext}
                  disabled={isLast || saving}
                >
                  Next
                </Button>
              ) : null}
            </Box>
          </Box>
        </CardContent>
      </Card>
    </Box>
  );
}

/**
 * Step 4 (Banking & Settlement) body — renders BankAccountsSection and
 * SettlementPanel stacked. BankAccountsSection has its own "Save & next"
 * submit; SettlementPanel has a separate "Save settlement config" submit.
 * The wizard cursor advances after BankAccountsSection's onSaved fires
 * (operators save both sections independently during setup).
 */
function BankingStep({ draft, partnerCode, onSaved }) {
  return (
    <Box>
      <BankAccountsSection
        draft={draft}
        partnerCode={partnerCode}
        onSaved={onSaved}
      />
      <Divider sx={{ my: 4 }} />
      <SettlementPanel
        draft={draft}
        partnerCode={partnerCode}
      />
    </Box>
  );
}

/** Map cursor → step body. Slice 1 fills Step 1; Slice 2 fills Step 2. */
function renderStep(cursor, stepDef, draft, partnerCode, advanceCursor, dispatch) {
  if (cursor === 1) {
    return (
      <IdentityStep
        draft={draft}
        partnerCode={partnerCode}
        onSaved={advanceCursor}
      />
    );
  }
  if (cursor === 2) {
    return (
      <ContactsStep
        draft={draft}
        partnerCode={partnerCode}
        onSaved={advanceCursor}
        dispatch={dispatch}
      />
    );
  }
  if (cursor === 3) {
    return (
      <KybForm
        draft={draft}
        partnerCode={partnerCode}
        onSaved={advanceCursor}
      />
    );
  }
  if (cursor === 4) {
    return (
      <BankingStep
        draft={draft}
        partnerCode={partnerCode}
        onSaved={advanceCursor}
      />
    );
  }
  if (cursor === 5) {
    return (
      <PrefundingForm
        draft={draft}
        partnerCode={partnerCode}
        onSaved={advanceCursor}
      />
    );
  }
  if (cursor === 6) {
    return (
      <Step6CommercialForm
        draft={draft}
        partnerCode={partnerCode}
        onSaved={advanceCursor}
      />
    );
  }
  return (
    <ComingSoonStep
      stepNumber={cursor}
      label={stepDef.label}
      sliceNumber={stepDef.slice}
    />
  );
}

function clampStep(n) {
  const v = Number(n);
  if (!Number.isFinite(v) || v < 1) return 1;
  if (v > STEPS.length) return STEPS.length;
  return Math.floor(v);
}

/**
 * Next.js dynamic-route params arrive URI-encoded; we keep the operator's
 * exact code (which often contains underscores or hyphens) by decoding
 * defensively.
 */
function decodePartnerCode(raw) {
  if (typeof raw !== 'string' || raw.length === 0) return null;
  try {
    return decodeURIComponent(raw);
  } catch {
    return raw;
  }
}

export default function PartnerDraftWizardPage() {
  return <PartnerDraftWizard activeStep={1} />;
}
