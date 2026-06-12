'use client';

import { PartnerDraftWizard } from '../page';

/**
 * Deep-link route: /partners/draft/{partnerCode}/step-7
 *
 * Renders the same wizard shell as the root draft page but with the cursor
 * pre-set to step 7 (Schemes & Corridors). The shell re-fetches the draft on
 * mount so a browser refresh lands on the correct step.
 */
export default function Step7Page() {
  return <PartnerDraftWizard activeStep={7} />;
}
