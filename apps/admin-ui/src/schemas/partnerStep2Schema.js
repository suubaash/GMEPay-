import * as yup from 'yup';

/**
 * Yup schema for the Slice 2 Contacts step of the Partner Setup wizard.
 *
 * Mirrors the BFF's DraftPartnerStep2Request / PartnerContactRequest shape:
 *   {
 *     contacts: [
 *       {
 *         role: string,               // one of CONTACT_ROLES
 *         name: string,
 *         email: string,              // RFC-5322 email
 *         phoneE164: string,          // E.164 format: +<country><number>
 *         isAuthorizedSignatory: boolean,
 *         notes: string?,             // optional free-text
 *       }
 *     ]
 *   }
 *
 * A minimum of one contact is required for wizard progress; a soft warning
 * (shown in ContactsForm) alerts the operator when fewer than 4 role-distinct
 * contacts are present (Ops 24x7, Finance, Compliance, Tech). The activation
 * gate (Step 8) hard-blocks on the 4-role requirement; here it is advisory only.
 */

/** Six contact roles the BFF accepts. */
export const CONTACT_ROLES = [
  'OPS_24X7',
  'FINANCE',
  'COMPLIANCE',
  'TECH',
  'LEGAL',
  'EXECUTIVE',
];

/** Human-readable labels for the role dropdown. */
export const CONTACT_ROLE_LABELS = {
  OPS_24X7: 'Operations 24x7',
  FINANCE: 'Finance',
  COMPLIANCE: 'Compliance',
  TECH: 'Technology',
  LEGAL: 'Legal',
  EXECUTIVE: 'Executive',
};

/**
 * The four roles required before a partner can be activated. Used in the
 * ContactsForm warning chip.
 */
export const REQUIRED_ACTIVATION_ROLES = ['OPS_24X7', 'FINANCE', 'COMPLIANCE', 'TECH'];

/**
 * E.164 phone-number regex: optional leading +, then 7..15 digits. The BFF
 * stores the canonical E.164 form (with leading +). The form PATCH sends
 * whatever the operator typed; the BFF normalises further.
 */
const E164_PATTERN = /^\+?[1-9]\d{6,14}$/;

/** Single contact row schema. */
export const contactRowSchema = yup.object({
  role: yup
    .string()
    .oneOf(CONTACT_ROLES, 'Pick a contact role')
    .required('Role is required'),

  name: yup
    .string()
    .trim()
    .required('Name is required')
    .max(200, 'Name must be 200 characters or fewer'),

  email: yup
    .string()
    .trim()
    .required('Email is required')
    .email('Must be a valid email address'),

  phoneE164: yup
    .string()
    .trim()
    .required('Phone is required')
    .matches(E164_PATTERN, 'Phone must be in E.164 format (e.g. +82101234567)'),

  isAuthorizedSignatory: yup.boolean().default(false),

  notes: yup
    .string()
    .trim()
    .nullable()
    .transform((v) => (v === '' ? null : v))
    .max(1000, 'Notes must be 1000 characters or fewer'),
});

/** Top-level Step-2 schema — wraps the contacts array. */
const partnerStep2Schema = yup.object({
  contacts: yup
    .array()
    .of(contactRowSchema)
    .min(1, 'At least one contact is required')
    .required('Contacts are required'),
});

export default partnerStep2Schema;
