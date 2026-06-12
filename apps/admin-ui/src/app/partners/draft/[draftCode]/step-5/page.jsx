'use client';

import { PartnerDraftWizard } from '../page';

/**
 * Deep-link route: /partners/draft/{partnerCode}/step-5
 *
 * Renders the same wizard shell as the root draft page but with the
 * cursor pre-set to step 5 (Prefunding). The shell re-fetches the draft
 * on mount so a browser refresh lands on the correct step.
 *
 * Step 5 only renders the PrefundingForm for OVERSEAS partners; the wizard
 * shell handles the non-OVERSEAS guard inside PrefundingForm itself.
 */
export default function Step5Page() {
  return <PartnerDraftWizard activeStep={5} />;
}
