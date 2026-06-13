'use client';

import { useState } from 'react';
import {
  Box,
  Button,
  CircularProgress,
  Divider,
  FormControl,
  InputLabel,
  MenuItem,
  OutlinedInput,
  Select,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useAppDispatch, useAppSelector } from '@/store';
import { patchStep8WebhookSubscription } from '@/store/lifecycleSlice';
import { useSnackbar } from '@/components/SnackbarProvider';

/**
 * Webhook event types sourced from the notification-webhook service contract.
 * Expand this list as new event types land in the notification-webhook module.
 */
export const WEBHOOK_EVENT_TYPES = [
  'PARTNER_ACTIVATED',
  'PARTNER_SUSPENDED',
  'PARTNER_TERMINATED',
  'TRANSACTION_COMMITTED',
  'TRANSACTION_REVERSED',
  'SETTLEMENT_BOOKED',
  'SETTLEMENT_FAILED',
  'PREFUND_LOW_BALANCE',
  'PREFUND_CRITICAL_BALANCE',
  'KYB_SCREENING_COMPLETED',
];

const ITEM_HEIGHT = 48;
const ITEM_PADDING_TOP = 8;
const MenuProps = {
  PaperProps: {
    style: { maxHeight: ITEM_HEIGHT * 4.5 + ITEM_PADDING_TOP, width: 280 },
  },
};

/**
 * WebhookSubscriptionSection — configure the partner's webhook endpoint and
 * event-type subscriptions.
 *
 * Saves via PATCH /v1/admin/partners/draft/{code}/step-8/webhook-subscription.
 *
 * @param {object}   props
 * @param {object}   props.draft        PartnerView (seed existing values).
 * @param {string}   props.partnerCode  URL-pinned identifier.
 */
export function WebhookSubscriptionSection({ draft, partnerCode }) {
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();
  const saving = useAppSelector((s) => s.lifecycle?.saving ?? false);

  const [url, setUrl] = useState(draft?.webhookUrl ?? '');
  const [eventTypes, setEventTypes] = useState(
    Array.isArray(draft?.webhookEventTypes) ? draft.webhookEventTypes : [],
  );

  const handleEventTypesChange = (e) => {
    const val = e.target.value;
    setEventTypes(typeof val === 'string' ? val.split(',') : val);
  };

  const handleSave = async () => {
    if (!partnerCode) {
      snackbar.error('No partner code — cannot save.');
      return;
    }
    const trimmedUrl = url.trim();
    if (!trimmedUrl) {
      snackbar.error('Webhook URL is required.');
      return;
    }
    try {
      await dispatch(
        patchStep8WebhookSubscription({
          partnerCode,
          body: { url: trimmedUrl, eventTypes },
        }),
      ).unwrap();
      snackbar.success('Webhook subscription saved.');
    } catch (e) {
      const message = e?.message ?? (typeof e === 'string' ? e : 'unknown error');
      snackbar.error(`Save failed: ${message}`);
    }
  };

  return (
    <Box aria-label="webhook-subscription-section">
      <Typography variant="h6" gutterBottom>
        Webhook Subscription
      </Typography>

      <Stack spacing={2}>
        <TextField
          label="Webhook URL"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          size="small"
          fullWidth
          placeholder="https://partner.example.com/webhooks/gmepay"
          inputProps={{ 'aria-label': 'webhook-url' }}
        />

        <FormControl size="small" fullWidth>
          <InputLabel id="event-types-label">Event types</InputLabel>
          <Select
            labelId="event-types-label"
            multiple
            value={eventTypes}
            onChange={handleEventTypesChange}
            input={<OutlinedInput label="Event types" />}
            MenuProps={MenuProps}
            inputProps={{ 'aria-label': 'webhook-event-types' }}
            renderValue={(selected) => selected.join(', ')}
          >
            {WEBHOOK_EVENT_TYPES.map((et) => (
              <MenuItem key={et} value={et}>
                {et}
              </MenuItem>
            ))}
          </Select>
        </FormControl>

        <Divider />

        <Box sx={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button
            variant="contained"
            onClick={handleSave}
            disabled={saving}
            startIcon={saving ? <CircularProgress size={16} color="inherit" /> : null}
            aria-label="save-webhook-subscription"
          >
            Save webhook
          </Button>
        </Box>
      </Stack>
    </Box>
  );
}

export default WebhookSubscriptionSection;
