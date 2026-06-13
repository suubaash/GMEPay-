'use client';

import { PartnerDraftWizard } from '../page';

/**
 * Step 1 (Identity) route — `/partners/draft/{partnerCode}/step-1`.
 *
 * Slice 1 ships only the wizard shell; the real Identity form (RHF + Yup
 * with the regional tax-ID matrix) is agent 1D.2's scope. This file
 * exists so the step-anchored URL resolves today — deep-links from emails
 * or operator handoffs land on the right stepper position from the start.
 *
 * The shell-level page (../page.jsx) already defaults `activeStep=1`;
 * keeping this thin wrapper makes it cheap to switch later steps to their
 * own routes once Slices 2..8 land.
 */
export default function PartnerDraftStep1Page() {
  return <PartnerDraftWizard activeStep={1} />;
}
