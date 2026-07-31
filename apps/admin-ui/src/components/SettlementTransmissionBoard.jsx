'use client';

import { Box, Chip, Stack, Tooltip, Typography } from '@mui/material';
import { transmissionStateMeta } from '@/api/settlementStatus';

/**
 * Whether GMEPay+ can transmit a settlement file to the scheme AT ALL (GAP T4-5).
 *
 * The settlement counterpart of `FilingChannelBoard` (T5-2), and deliberately the same
 * shape: three different facts get conflated in settlement UIs, and this component exists
 * to keep the middle one visible.
 *
 *   1. **generated**   — the file was produced from the persisted settlement lines.
 *   2. **channel live** — a real transmission channel to the scheme exists. It does not
 *                         today: the only `SftpTransport` bean is `LocalDirSftpTransport`,
 *                         which writes to a temp directory and sends nothing, and
 *                         settlement-reconciliation's channel registry classifies a local
 *                         endpoint (`file:`, `/tmp`, `./`, `local…`) as **no channel** for
 *                         exactly that reason. Real SFTP to ZeroPay/KFTC needs scheme
 *                         credentials plus a certification run — an external gate.
 *   3. **transmitted**  — the scheme actually received the file. Requires (2), so it has
 *                         never happened.
 *
 * Rendered straight from `GET /v1/admin/settlement/transmission-channel`
 * (`{ live, reachableState, reason }`); nothing is inferred here, so this banner
 * self-removes the day a real channel is configured. A **missing** board renders as
 * unknown, never as availability — an absent board means we do not know, which is not the
 * same as "a channel exists".
 *
 * Props:
 *   channel: { live: boolean, reachableState: string, reason: string|null } | null
 *   dense:   boolean  — drop the explanatory caption (for tight banners)
 */
export default function SettlementTransmissionBoard({ channel, dense = false }) {
  if (!channel || typeof channel !== 'object') {
    return (
      <Typography
        variant="caption"
        color="text.secondary"
        data-testid="settlement-channel-unknown"
      >
        Settlement transmission channel not reported by settlement-reconciliation — nothing
        may be assumed transmitted.
      </Typography>
    );
  }

  const live = channel.live === true;
  const reachable = transmissionStateMeta(channel.reachableState);
  const reason = typeof channel.reason === 'string' ? channel.reason.trim() : '';
  const tip = live
    ? `A transmission channel to the scheme is configured. Most advanced reachable state: ${reachable.state}.`
    : `No transmission channel to the scheme, so batches stop at ${reachable.state}.`
      + (reason ? ` ${reason}` : '');

  return (
    <Box data-testid="settlement-transmission-board">
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center">
        <Tooltip title={tip}>
          <Chip
            size="small"
            variant="outlined"
            color={live ? 'success' : 'warning'}
            label={`Scheme channel: ${live ? 'live' : 'not configured'}`}
            aria-label={`Settlement transmission channel ${live ? 'live' : 'unavailable'}`}
          />
        </Tooltip>
        <Tooltip title={reachable.description}>
          <Chip
            size="small"
            variant="outlined"
            color={reachable.transmitted ? 'success' : 'default'}
            label={`Best reachable: ${reachable.state}`}
            aria-label={`Most advanced reachable transmission state ${reachable.state}`}
          />
        </Tooltip>
      </Stack>
      {reason && (
        <Typography variant="caption" color="text.secondary" component="p" sx={{ mt: 0.75 }}>
          {reason}
        </Typography>
      )}
      {!dense && (
        <Typography variant="caption" color="text.secondary" component="p" sx={{ mt: 0.5 }}>
          {live
            ? 'A channel is configured, so a batch may genuinely be sent; read each batch’s '
              + 'transmission column for whether it was.'
            : 'Nothing can be transmitted from this deployment, so no settlement file has reached '
              + 'a scheme. Files are written to a local directory and a local directory is not a '
              + 'channel.'}
        </Typography>
      )}
    </Box>
  );
}
