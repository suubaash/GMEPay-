'use client';

import AuditTrail from '@/components/AuditTrail';

/**
 * AuditLogPanel — embeds the shared AuditTrail component scoped to a single
 * partner aggregate. AuditTrail handles paging, chain-valid display, and
 * loading/error states internally.
 *
 * Props:
 *   partnerCode: string — used as aggregateId
 *   pageSize:    number (default 20)
 */
export default function AuditLogPanel({ partnerCode, pageSize = 20 }) {
  return (
    <AuditTrail
      aggregateType="partner"
      aggregateId={partnerCode}
      pageSize={pageSize}
    />
  );
}
