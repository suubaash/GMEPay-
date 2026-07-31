'use client';

import { Box, Chip, Stack, Tooltip, Typography } from '@mui/material';
import { filingStatusMeta } from '@/api/filingStatus';

/**
 * Per-lane regulatory FILING CHANNEL availability (GAP T5-2).
 *
 * Three different facts get conflated in compliance UIs, and this component exists to
 * keep the middle one visible:
 *
 *   1. **configured**       — a per-partner regulatory attribute was entered (a BOK txn
 *                             code, an NTS cert id, a KoFIU entity id). Says nothing
 *                             about whether we can transmit.
 *   2. **channel available** — a real transmission channel exists for the lane. Every
 *                             lane is dark today: BOK SFTP endpoint (OI-03), NTS mTLS
 *                             certificate (OI-02), KoFIU endpoint + file layout.
 *   3. **filed**            — an authority actually received the artifact. Requires (2),
 *                             so it has never happened.
 *
 * Rendered straight from what the backend reports (`filingChannels[]` on every
 * `ReportRun`); nothing is inferred here. A lane only gets a success colour when the
 * service says `channelLive: true`.
 *
 * Props:
 *   channels: Array<{ lane, channelLive, reachableStatus, reason }> | null | undefined
 *   reason:   string | null   — run-level filingChannelUnavailableReason, used as the
 *                               fallback tooltip when a lane reports none
 *   dense:    boolean         — drop the explanatory caption (for tight banners)
 */
export default function FilingChannelBoard({ channels, reason, dense = false }) {
  const lanes = Array.isArray(channels) ? channels : null;

  if (!lanes || lanes.length === 0) {
    return (
      <Typography variant="caption" color="text.secondary" data-testid="filing-channels-unknown">
        Filing channel status not reported by the reporting service — nothing may be assumed
        filed.
        {reason ? ` ${reason}` : ''}
      </Typography>
    );
  }

  const anyLive = lanes.some((l) => l.channelLive === true);

  return (
    <Box data-testid="filing-channel-board">
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
        {lanes.map((lane) => {
          const live = lane.channelLive === true;
          const reachable = filingStatusMeta(lane.reachableStatus);
          const tip = live
            ? `${lane.lane}: transmission channel configured. Most advanced reachable status: `
              + `${reachable.status}.`
            : `${lane.lane}: no transmission channel, so filings stop at ${reachable.status}. `
              + `${lane.reason || reason || ''}`;
          return (
            <Tooltip key={lane.lane} title={tip.trim()}>
              <Chip
                size="small"
                variant="outlined"
                color={live ? 'success' : 'warning'}
                label={`${lane.lane}: ${live ? 'channel live' : 'no filing channel'}`}
                aria-label={`${lane.lane} filing channel ${live ? 'live' : 'unavailable'}`}
              />
            </Tooltip>
          );
        })}
      </Stack>
      {!dense && (
        <Typography variant="caption" color="text.secondary" component="p" sx={{ mt: 0.75 }}>
          {anyLive
            ? 'Lanes marked "channel live" can transmit; the others generate locally only.'
            : 'No lane can transmit, so nothing has been filed with any authority. Reports are '
              + 'generated and validated against our own format checks only.'}
        </Typography>
      )}
    </Box>
  );
}
