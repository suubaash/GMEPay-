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
  fetchDraft,
  resetCurrent,
} from '@/store/draftsSlice';
import IdentityForm from './step-1/IdentityForm';

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
  // Slice 1 only persists Step 1, and the IdentityForm component owns the
  // submit + PATCH for it (so RHF validation runs before we round-trip).
  // The wizard's own "Save & next" button therefore stays hidden on cursor
  // 1 — the form provides its own submit affordance and advances the
  // cursor via the {@link advanceCursor} callback below.
  const step1HasOwnSubmit = cursor === 1;

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
          {renderStep(cursor, stepDef, current, partnerCode, advanceCursor)}

          <Divider sx={{ my: 3 }} />

          <Box sx={{ display: 'flex', gap: 1, justifyContent: 'space-between' }}>
            <Button onClick={onCancel} color="inherit">
              Cancel
            </Button>
            <Box sx={{ display: 'flex', gap: 1 }}>
              <Button onClick={onBack} disabled={isFirst || saving}>
                Back
              </Button>
              {!step1HasOwnSubmit ? (
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

/** Map cursor → step body. Slice 1 only fills Step 1. */
function renderStep(cursor, stepDef, draft, partnerCode, advanceCursor) {
  if (cursor === 1) {
    return (
      <IdentityStep
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
