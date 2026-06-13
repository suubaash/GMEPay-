'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  Box,
  Button,
  Card,
  CardContent,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  Tab,
  Tabs,
  Typography,
} from '@mui/material';
import EditIcon from '@mui/icons-material/Edit';
import { useParams } from 'next/navigation';
import RoundingModeSelect from '@/components/RoundingModeSelect';
import ErrorAlert from '@/components/ErrorAlert';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import Breadcrumbs from '@/components/Breadcrumbs';
import PrefundingTile from '@/components/PrefundingTile';
import { useSnackbar } from '@/components/SnackbarProvider';
import { useAppDispatch, useAppSelector } from '@/store';
import { getPartner, updatePartnerRoundingMode } from '@/store/partnersSlice';
import StatusHeader from './StatusHeader';
import CredentialRotationPanel from './CredentialRotationPanel';
import AuditLogPanel from './AuditLogPanel';
import RegulatorySettingsTab from './RegulatorySettingsTab';
import StatusActionHistory from './StatusActionHistory';

/**
 * Partner detail page — post-activation surface.
 *
 * Tab layout:
 *   0: Overview  — StatusHeader + summary cards + rounding-mode edit
 *   1: Credentials — CredentialRotationPanel
 *   2: Schemes & Corridors — (placeholder, full build in Slice 7)
 *   3: Regulatory — RegulatorySettingsTab
 *   4: Audit — AuditLogPanel (per-partner audit trail)
 *   5: Lifecycle history — StatusActionHistory
 *
 * For OVERSEAS partners a "Prefunding" sub-card is shown in the Overview tab.
 *
 * GET /v1/admin/partners/{id} returns a PartnerSummary (404 when unknown):
 *   { partnerId, partnerCode, type, settlementCurrency, settlementRoundingMode,
 *     status, terminatedAt?, terminationReason?, regulatory? }
 */

const TAB_OVERVIEW = 0;
const TAB_CREDENTIALS = 1;
const TAB_SCHEMES = 2;
const TAB_REGULATORY = 3;
const TAB_AUDIT = 4;
const TAB_LIFECYCLE = 5;

export default function PartnerDetailPage() {
  const params = useParams();
  const id = params?.id;
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();
  const { details, detailLoading, saving, error } = useAppSelector((s) => s.partners);

  const partner = id ? details[id] : undefined;

  const reload = useCallback(() => {
    if (id) dispatch(getPartner(id));
  }, [dispatch, id]);

  useEffect(() => {
    reload();
  }, [reload]);

  const [dialogOpen, setDialogOpen] = useState(false);
  const [draftMode, setDraftMode] = useState('HALF_UP');
  const [activeTab, setActiveTab] = useState(0);

  const openDialog = () => {
    if (!partner) return;
    setDraftMode(partner.settlementRoundingMode ?? 'HALF_UP');
    setDialogOpen(true);
  };

  const onSave = async () => {
    if (!id || !partner) return;
    try {
      const updated = await dispatch(
        updatePartnerRoundingMode({ id, mode: draftMode }),
      ).unwrap();
      snackbar.success(`Rounding mode set to ${updated.settlementRoundingMode}`);
      setDialogOpen(false);
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      snackbar.error(`Update failed: ${message}`);
    }
  };

  if (error && !partner) {
    return <ErrorAlert message={error} onRetry={reload} title="Could not load partner" />;
  }
  if (!partner) {
    return <LoadingSkeleton variant="page" />;
  }

  // The partnerCode used as the aggregateId for the audit trail. The detail
  // response may carry a `partnerCode` field (PartnerView from Slice 1); fall
  // back to the URL `id` param which is what the route is keyed on.
  const partnerCode = partner.partnerCode ?? id ?? '';

  // Only OVERSEAS partners have a prefunding balance — show the Prefunding tile
  // in the Overview tab when the loaded partner data confirms the type.
  const isOverseas = partner.type === 'OVERSEAS';

  return (
    <Box>
      <Breadcrumbs
        crumbs={[
          { label: 'Partners', href: '/partners' },
          { label: partner.partnerId ?? id ?? '' },
        ]}
      />
      <Typography variant="h1" gutterBottom>
        {partner.partnerId}
      </Typography>

      <ErrorAlert message={error} onRetry={reload} />

      {/* Status header — FSM transitions + 4-eyes flow */}
      <Box sx={{ mb: 2 }}>
        <StatusHeader
          partnerCode={partnerCode}
          status={partner.status}
          terminatedAt={partner.terminatedAt}
          terminationReason={partner.terminationReason}
          onTransitionDone={reload}
        />
      </Box>

      {/* Tab bar */}
      <Box sx={{ borderBottom: 1, borderColor: 'divider', mb: 2 }}>
        <Tabs
          value={activeTab}
          onChange={(_e, v) => setActiveTab(v)}
          aria-label="Partner detail tabs"
          variant="scrollable"
          scrollButtons="auto"
        >
          <Tab label="Overview" id="partner-tab-0" aria-controls="partner-tabpanel-0" />
          <Tab label="Credentials" id="partner-tab-1" aria-controls="partner-tabpanel-1" />
          <Tab label="Schemes & Corridors" id="partner-tab-2" aria-controls="partner-tabpanel-2" />
          <Tab label="Regulatory" id="partner-tab-3" aria-controls="partner-tabpanel-3" />
          <Tab label="Audit" id="partner-tab-4" aria-controls="partner-tabpanel-4" />
          <Tab label="Lifecycle history" id="partner-tab-5" aria-controls="partner-tabpanel-5" />
        </Tabs>
      </Box>

      {/* Overview tab */}
      {activeTab === TAB_OVERVIEW && (
        <Box role="tabpanel" id="partner-tabpanel-0" aria-labelledby="partner-tab-0">
          <Stack spacing={2}>
            <Card sx={{ maxWidth: 720 }}>
              <CardContent>
                <Stack spacing={2}>
                  <Box>
                    <Typography variant="body2" color="text.secondary">
                      Partner type
                    </Typography>
                    <Typography>{partner.type ?? '—'}</Typography>
                  </Box>
                  <Box>
                    <Typography variant="body2" color="text.secondary">
                      Settlement currency
                    </Typography>
                    <Typography>{partner.settlementCurrency ?? '—'}</Typography>
                  </Box>
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
                    <Box sx={{ flexGrow: 1 }}>
                      <Typography variant="body2" color="text.secondary">
                        Settlement rounding mode
                      </Typography>
                      <Typography>{partner.settlementRoundingMode ?? '—'}</Typography>
                    </Box>
                    <Button
                      variant="outlined"
                      startIcon={<EditIcon />}
                      onClick={openDialog}
                      disabled={detailLoading}
                    >
                      Edit
                    </Button>
                  </Box>
                </Stack>
              </CardContent>
            </Card>

            {/* Prefunding tile for OVERSEAS partners */}
            {isOverseas && (
              <PrefundingTile partnerCode={partnerCode} />
            )}
          </Stack>
        </Box>
      )}

      {/* Credentials tab */}
      {activeTab === TAB_CREDENTIALS && (
        <Box role="tabpanel" id="partner-tabpanel-1" aria-labelledby="partner-tab-1">
          <CredentialRotationPanel partnerCode={partnerCode} />
        </Box>
      )}

      {/* Schemes & Corridors tab */}
      {activeTab === TAB_SCHEMES && (
        <Box role="tabpanel" id="partner-tabpanel-2" aria-labelledby="partner-tab-2">
          <Typography variant="body2" color="text.secondary">
            Schemes and corridor configuration — see the wizard Step 7 for the
            enrolled schemes and active corridors.
          </Typography>
        </Box>
      )}

      {/* Regulatory tab */}
      {activeTab === TAB_REGULATORY && (
        <Box role="tabpanel" id="partner-tabpanel-3" aria-labelledby="partner-tab-3">
          <RegulatorySettingsTab
            regulatory={partner.regulatory ?? null}
            onSaved={reload}
          />
        </Box>
      )}

      {/* Audit tab */}
      {activeTab === TAB_AUDIT && (
        <Box role="tabpanel" id="partner-tabpanel-4" aria-labelledby="partner-tab-4">
          <AuditLogPanel partnerCode={partnerCode} />
        </Box>
      )}

      {/* Lifecycle history tab */}
      {activeTab === TAB_LIFECYCLE && (
        <Box role="tabpanel" id="partner-tabpanel-5" aria-labelledby="partner-tab-5">
          <StatusActionHistory partnerCode={partnerCode} />
        </Box>
      )}

      {/* Rounding-mode edit dialog */}
      <Dialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        aria-labelledby="edit-rounding-title"
        maxWidth="xs"
        fullWidth
      >
        <DialogTitle id="edit-rounding-title">Edit rounding mode</DialogTitle>
        <DialogContent>
          <Box sx={{ mt: 1 }}>
            <RoundingModeSelect
              value={draftMode}
              onChange={setDraftMode}
              helperText="Changing this affects ONLY transactions created after the change. Audit-logged in config-registry."
            />
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDialogOpen(false)}>Cancel</Button>
          <Button
            onClick={onSave}
            variant="contained"
            disabled={saving || draftMode === partner.settlementRoundingMode}
          >
            Save
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
