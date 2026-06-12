'use client';

import { PartnerDraftWizard } from '../page';

/**
 * Deep-link route: /partners/draft/{partnerCode}/step-2
 *
 * Renders the same wizard shell as the root draft page but with the
 * cursor pre-set to step 2 (Contacts). The shell re-fetches the draft and
 * the current contact list on mount so a browser refresh lands on the
 * correct step with pre-populated data.
 */
export default function Step2Page() {
  return <PartnerDraftWizard activeStep={2} />;
}
