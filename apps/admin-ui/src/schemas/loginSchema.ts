import * as yup from 'yup';

/**
 * Yup schema for the operator login form.
 *
 * Username + password are both required, length-bounded to match the
 * auth-identity service's bcrypt input limit (72 bytes) — the BFF will
 * re-validate, this is just a UX guard.
 */
export const loginSchema = yup.object({
  username: yup
    .string()
    .required('Username is required')
    .min(3, 'Username must be at least 3 characters')
    .max(64, 'Username is too long'),
  password: yup
    .string()
    .required('Password is required')
    .min(4, 'Password must be at least 4 characters')
    .max(72, 'Password is too long'),
});

export type LoginFormValues = yup.InferType<typeof loginSchema>;
