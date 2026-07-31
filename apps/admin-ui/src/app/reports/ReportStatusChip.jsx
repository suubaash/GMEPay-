'use client';

import { Chip, Tooltip } from '@mui/material';
import { filingStatusMeta } from '@/api/filingStatus';

/**
 * Renders a report run's FILING status honestly (GAP T5-2).
 *
 * This chip used to colour `GENERATED` and `SUBMITTED` both green and describe
 * `SUBMITTED` as "file delivered to regulator". `SUBMITTED` was a fabricated state
 * (stubs wrote it for reports that never left the JVM) and no longer exists; more
 * importantly, green is now reserved for `TRANSMITTED`/`ACKNOWLEDGED` only — the two
 * states that mean an authority actually received the file, and the two states that
 * are unreachable until the BOK/NTS/KoFIU channels are configured.
 *
 * The wire status is never re-derived here; @/api/filingStatus only decides wording
 * and colour, and refuses to upgrade an unknown or retired claim into a success.
 *
 * Props:
 *   status: string | null
 *   reason: string | null   — filingChannelUnavailableReason from the run, if any
 */
export default function ReportStatusChip({ status, reason }) {
  if (!status) {
    return <Chip size="small" label="—" color="default" />;
  }
  const meta = filingStatusMeta(status);
  const tip = reason ? `${meta.description} — ${reason}` : meta.description;
  return (
    <Tooltip title={tip}>
      <Chip
        size="small"
        label={meta.label}
        color={meta.color}
        variant={meta.filed ? 'filled' : 'outlined'}
        aria-label={`Filing status ${meta.status}`}
      />
    </Tooltip>
  );
}
