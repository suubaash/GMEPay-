'use client';

import {
  Box,
  Button,
  Chip,
  Divider,
  Stack,
  Typography,
} from '@mui/material';
import { useRouter, useParams } from 'next/navigation';
import { useAppSelector } from '@/store';

/**
 * Step labels and the route segment for each step in the wizard.
 * Each group maps to one of steps 1..7.
 */
const STEP_GROUPS = [
  { step: 1, label: 'Identity', route: 'step-1' },
  { step: 2, label: 'Contacts', route: 'step-2' },
  { step: 3, label: 'KYB', route: 'step-3' },
  { step: 4, label: 'Banking', route: 'step-4' },
  { step: 5, label: 'Prefunding', route: 'step-5' },
  { step: 6, label: 'Commercial', route: 'step-6' },
  { step: 7, label: 'Schemes & Corridors', route: 'step-7' },
  { step: 8, label: 'Regulatory & Credentials', route: 'step-8' },
];

/**
 * A single review row for a field.
 */
function FieldRow({ label, value, empty }) {
  return (
    <Box sx={{ display: 'flex', gap: 2, py: 0.5 }}>
      <Typography
        variant="body2"
        color="text.secondary"
        sx={{ minWidth: 200, flexShrink: 0 }}
      >
        {label}
      </Typography>
      <Typography
        variant="body2"
        color={empty ? 'text.disabled' : 'text.primary'}
        sx={{ fontStyle: empty ? 'italic' : 'normal' }}
      >
        {empty ? '(not set)' : String(value ?? '')}
      </Typography>
    </Box>
  );
}

function isEmpty(val) {
  return val === null || val === undefined || val === '';
}

/**
 * A single step group card: header with "Edit" link + field rows.
 */
function StepGroup({ step, label, route, partnerCode, children }) {
  const router = useRouter();
  const handleEdit = () => {
    router.push(`/partners/draft/${encodeURIComponent(partnerCode)}/${route}`);
  };

  return (
    <Box aria-label={`review-group-step-${step}`}>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          mb: 1,
        }}
      >
        <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
          Step {step}: {label}
        </Typography>
        <Button
          size="small"
          variant="outlined"
          onClick={handleEdit}
          aria-label={`edit-step-${step}`}
        >
          Edit
        </Button>
      </Box>
      <Stack spacing={0.25}>{children}</Stack>
    </Box>
  );
}

/**
 * Read-only summary of the partner draft across all 7 data steps.
 * Groups are shown in wizard order. Each has an "Edit" anchor that
 * navigates the operator back to the corresponding step route.
 *
 * @param {object}   props
 * @param {object}   props.draft        Current PartnerView from the wizard.
 * @param {string}   props.partnerCode  URL-pinned identifier.
 */
export function ReviewSection({ draft, partnerCode }) {
  const d = draft ?? {};

  // Contacts from drafts slice (loaded by ContactsStep in the wizard)
  const contacts = useAppSelector((s) => s.drafts?.contacts ?? []);

  // KYB from kyb slice
  const kybByCode = useAppSelector((s) => s.kyb?.kybByCode ?? {});
  const kyb = kybByCode[partnerCode] ?? null;

  // Bank accounts
  const accountsByCode = useAppSelector((s) => s.bankAccounts?.accountsByCode ?? {});
  const accounts = accountsByCode[partnerCode] ?? [];

  // Prefunding
  const prefundingByCode = useAppSelector((s) => s.prefundingConfig?.configByCode ?? {});
  const prefunding = prefundingByCode[partnerCode] ?? null;

  // Commercial terms
  const commercialByCode = useAppSelector((s) => s.commercialTerms?.configByCode ?? {});
  const commercial = commercialByCode[partnerCode] ?? null;

  // Scheme enrollments
  const schemesByCode = useAppSelector((s) => s.partnerSchemes?.schemesByCode ?? {});
  const schemes = schemesByCode[partnerCode] ?? [];
  const enabledSchemes = schemes.filter((s) => s.enabled);

  return (
    <Box aria-label="review-section">
      <Typography variant="h6" gutterBottom>
        Review Partner Setup
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Review all configuration below before activating. Click &quot;Edit&quot; on any
        section to return to that step.
      </Typography>

      <Stack spacing={3} divider={<Divider />}>
        {/* Step 1 — Identity */}
        <StepGroup
          step={1}
          label={STEP_GROUPS[0].label}
          route={STEP_GROUPS[0].route}
          partnerCode={partnerCode}
        >
          <FieldRow label="Partner code" value={d.partnerCode} empty={isEmpty(d.partnerCode)} />
          <FieldRow label="Legal name (local)" value={d.legalNameLocal} empty={isEmpty(d.legalNameLocal)} />
          <FieldRow label="Legal name (romanized)" value={d.legalNameRomanized} empty={isEmpty(d.legalNameRomanized)} />
          <FieldRow label="Tax ID" value={d.taxId ? `${d.taxId} (${d.taxIdType ?? ''})` : null} empty={isEmpty(d.taxId)} />
          <FieldRow label="Country" value={d.countryOfIncorporation} empty={isEmpty(d.countryOfIncorporation)} />
          <FieldRow label="Legal form" value={d.legalForm} empty={isEmpty(d.legalForm)} />
          <FieldRow label="LEI" value={d.lei} empty={isEmpty(d.lei)} />
          <FieldRow label="Type" value={d.type} empty={isEmpty(d.type)} />
          <FieldRow label="Settlement currency" value={d.settlementCurrency} empty={isEmpty(d.settlementCurrency)} />
        </StepGroup>

        {/* Step 2 — Contacts */}
        <StepGroup
          step={2}
          label={STEP_GROUPS[1].label}
          route={STEP_GROUPS[1].route}
          partnerCode={partnerCode}
        >
          {contacts.length === 0 ? (
            <FieldRow label="Contacts" value={null} empty />
          ) : (
            contacts.map((c, i) => (
              <FieldRow
                key={c.id ?? i}
                label={c.role ?? `Contact ${i + 1}`}
                value={`${c.name ?? ''} <${c.email ?? ''}>`}
                empty={isEmpty(c.name) && isEmpty(c.email)}
              />
            ))
          )}
        </StepGroup>

        {/* Step 3 — KYB */}
        <StepGroup
          step={3}
          label={STEP_GROUPS[2].label}
          route={STEP_GROUPS[2].route}
          partnerCode={partnerCode}
        >
          <FieldRow label="Risk rating" value={kyb?.riskRating} empty={isEmpty(kyb?.riskRating)} />
          <FieldRow label="License type" value={kyb?.licenseType} empty={isEmpty(kyb?.licenseType)} />
          <FieldRow label="License number" value={kyb?.licenseNumber} empty={isEmpty(kyb?.licenseNumber)} />
          <FieldRow label="License authority" value={kyb?.licenseAuthority} empty={isEmpty(kyb?.licenseAuthority)} />
          <FieldRow label="Screening status" value={kyb?.screeningStatus} empty={isEmpty(kyb?.screeningStatus)} />
        </StepGroup>

        {/* Step 4 — Banking */}
        <StepGroup
          step={4}
          label={STEP_GROUPS[3].label}
          route={STEP_GROUPS[3].route}
          partnerCode={partnerCode}
        >
          {accounts.length === 0 ? (
            <FieldRow label="Bank accounts" value={null} empty />
          ) : (
            accounts.map((a, i) => (
              <FieldRow
                key={a.id ?? i}
                label={a.purpose ?? `Account ${i + 1}`}
                value={`${a.bankName ?? ''} ${a.ibanOrAccountNumber ?? ''} (${a.currency ?? ''})`}
                empty={false}
              />
            ))
          )}
          <FieldRow label="Settlement cycle" value={d.settlementConfig?.cycleTPlusN != null ? `T+${d.settlementConfig.cycleTPlusN}` : null} empty={d.settlementConfig == null} />
        </StepGroup>

        {/* Step 5 — Prefunding */}
        <StepGroup
          step={5}
          label={STEP_GROUPS[4].label}
          route={STEP_GROUPS[4].route}
          partnerCode={partnerCode}
        >
          <FieldRow label="Funding model" value={prefunding?.fundingModel} empty={isEmpty(prefunding?.fundingModel)} />
          <FieldRow label="Opening balance (USD)" value={prefunding?.openingBalanceUsd} empty={isEmpty(prefunding?.openingBalanceUsd)} />
          <FieldRow label="Low-balance threshold (USD)" value={prefunding?.lowBalanceThresholdUsd} empty={isEmpty(prefunding?.lowBalanceThresholdUsd)} />
        </StepGroup>

        {/* Step 6 — Commercial */}
        <StepGroup
          step={6}
          label={STEP_GROUPS[5].label}
          route={STEP_GROUPS[5].route}
          partnerCode={partnerCode}
        >
          <FieldRow label="Fee scheme" value={commercial?.feeSchedule?.scheme} empty={isEmpty(commercial?.feeSchedule?.scheme)} />
          <FieldRow label="Fixed fee (USD)" value={commercial?.feeSchedule?.fixedFeeUsd} empty={isEmpty(commercial?.feeSchedule?.fixedFeeUsd)} />
          <FieldRow label="BPS fee" value={commercial?.feeSchedule?.bpsFee} empty={isEmpty(commercial?.feeSchedule?.bpsFee)} />
          <FieldRow label="FX margin (bps)" value={commercial?.fxConfig?.marginBps} empty={isEmpty(commercial?.fxConfig?.marginBps)} />
          <FieldRow label="Contract from" value={commercial?.contract?.effectiveFrom} empty={isEmpty(commercial?.contract?.effectiveFrom)} />
        </StepGroup>

        {/* Step 7 — Schemes & Corridors */}
        <StepGroup
          step={7}
          label={STEP_GROUPS[6].label}
          route={STEP_GROUPS[6].route}
          partnerCode={partnerCode}
        >
          {enabledSchemes.length === 0 ? (
            <FieldRow label="Enrolled schemes" value={null} empty />
          ) : (
            <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', py: 0.5 }}>
              {enabledSchemes.map((s) => (
                <Chip key={s.schemeId} label={s.schemeId} size="small" />
              ))}
            </Box>
          )}
        </StepGroup>

        {/* Step 8 — Regulatory & Credentials (self-reference for completeness) */}
        <StepGroup
          step={8}
          label={STEP_GROUPS[7].label}
          route={STEP_GROUPS[7].route}
          partnerCode={partnerCode}
        >
          <FieldRow label="BOK txn code" value={d.bokTxnCode} empty={isEmpty(d.bokTxnCode)} />
          <FieldRow label="KoFIU entity ID" value={d.kofiuEntityId} empty={isEmpty(d.kofiuEntityId)} />
          <FieldRow label="Travel Rule protocol" value={d.travelRuleProtocol} empty={isEmpty(d.travelRuleProtocol)} />
          <FieldRow label="Webhook URL" value={d.webhookUrl} empty={isEmpty(d.webhookUrl)} />
        </StepGroup>
      </Stack>
    </Box>
  );
}

export default ReviewSection;
