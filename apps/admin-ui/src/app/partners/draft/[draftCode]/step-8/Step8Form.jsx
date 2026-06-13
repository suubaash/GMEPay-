'use client';

import { useState } from 'react';
import {
  Box,
  Divider,
  Tab,
  Tabs,
  Typography,
} from '@mui/material';
import { useAppSelector } from '@/store';
import ReviewSection from './ReviewSection';
import RegulatorySection from './RegulatorySection';
import WebhookSubscriptionSection from './WebhookSubscriptionSection';
import IpAllowlistSection from './IpAllowlistSection';
import MtlsCertSection from './MtlsCertSection';
import PreconditionPanel from './PreconditionPanel';
import ActivateButton from './ActivateButton';
import ActivationCredentialModal from './ActivationCredentialModal';

const TABS = [
  { id: 'review', label: 'Review' },
  { id: 'regulatory', label: 'Regulatory' },
  { id: 'webhook', label: 'Webhook' },
  { id: 'ip-allowlist', label: 'IP allowlist' },
  { id: 'mtls', label: 'mTLS' },
  { id: 'activate', label: 'Activate' },
];

/**
 * Step 8 (Review & Activate) composite form.
 *
 * Tabs:
 *   Review     — read-only summary of steps 1–7 with "Edit" anchor links.
 *   Regulatory — BOK / Hometax / KoFIU / PIPA / Travel Rule fields.
 *   Webhook    — webhook URL + event-type multi-select.
 *   IP allowlist — CIDR rows, env toggle, 10-cap.
 *   mTLS       — PEM paste + parsed cert display.
 *   Activate   — PreconditionPanel + ActivateButton (4-eyes flow).
 *               After successful activation: ActivationCredentialModal.
 *
 * @param {object}   props
 * @param {object}   props.draft        PartnerView the wizard is editing.
 * @param {string}   props.partnerCode  URL-pinned identifier.
 * @param {Function} [props.onSaved]    Called on successful activation (no-op; step 8 is terminal).
 */
export function Step8Form({ draft, partnerCode, onSaved }) {
  const [activeTab, setActiveTab] = useState('review');

  // Credential bundle from lifecycle slice (one-time, in-memory only)
  const issuedBundle = useAppSelector((s) => s.lifecycle?.issuedBundle ?? null);

  // Compute whether all preconditions are met for the Activate tab button.
  const preconditions = useAppSelector(
    (s) => s.lifecycle?.preconditionsByCode?.[partnerCode] ?? null,
  );
  const allMet =
    preconditions !== null &&
    preconditions.length > 0 &&
    preconditions.every((p) => p.met);

  const handleActivated = (bundle) => {
    // bundle is also written to Redux store by executeActivate.fulfilled;
    // the modal reads it from there. This callback is a no-op in practice
    // but allows callers to react if needed.
    if (typeof onSaved === 'function') onSaved(bundle);
  };

  return (
    <Box aria-label="step-8-form">
      <Box>
        <Typography variant="h5" gutterBottom>
          Review &amp; Activate
        </Typography>
        <Typography variant="body2" color="text.secondary">
          Step 8 — review all wizard configuration, complete regulatory and
          credential settings, then activate the partner (4-eyes required).
        </Typography>
      </Box>

      <Tabs
        value={activeTab}
        onChange={(_, v) => setActiveTab(v)}
        aria-label="step-8-tabs"
        sx={{ borderBottom: 1, borderColor: 'divider', mt: 2, mb: 3 }}
      >
        {TABS.map((t) => (
          <Tab
            key={t.id}
            value={t.id}
            label={t.label}
            aria-label={`tab-${t.id}`}
          />
        ))}
      </Tabs>

      {/* ── Review ──────────────────────────────────────────────────────── */}
      {activeTab === 'review' && (
        <ReviewSection draft={draft} partnerCode={partnerCode} />
      )}

      {/* ── Regulatory ──────────────────────────────────────────────────── */}
      {activeTab === 'regulatory' && (
        <RegulatorySection draft={draft} partnerCode={partnerCode} />
      )}

      {/* ── Webhook ─────────────────────────────────────────────────────── */}
      {activeTab === 'webhook' && (
        <WebhookSubscriptionSection draft={draft} partnerCode={partnerCode} />
      )}

      {/* ── IP allowlist ────────────────────────────────────────────────── */}
      {activeTab === 'ip-allowlist' && (
        <IpAllowlistSection partnerCode={partnerCode} />
      )}

      {/* ── mTLS ────────────────────────────────────────────────────────── */}
      {activeTab === 'mtls' && (
        <MtlsCertSection partnerCode={partnerCode} />
      )}

      {/* ── Activate ────────────────────────────────────────────────────── */}
      {activeTab === 'activate' && (
        <Box>
          <PreconditionPanel partnerCode={partnerCode} />
          <Divider sx={{ my: 3 }} />
          <ActivateButton
            partnerCode={partnerCode}
            allMet={allMet}
            onActivated={handleActivated}
          />
        </Box>
      )}

      {/* One-time credential modal — shown after successful activation */}
      <ActivationCredentialModal bundle={issuedBundle} />
    </Box>
  );
}

export default Step8Form;
