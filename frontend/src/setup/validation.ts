import { passwordRules } from '../signup/validation';

export type SetupFields = {
  organisationId: string;
  fullName: string;
  email: string;
  password: string;
  confirmation: string;
};

export type SetupFieldErrors = Partial<Record<keyof SetupFields, string>>;

export function validateSetup(fields: SetupFields, organisations: number): SetupFieldErrors {
  const errors: SetupFieldErrors = {};
  if (organisations === 0) errors.organisationId = 'No eligible organisation is available.';
  else if (!fields.organisationId) errors.organisationId = 'Choose an organisation.';
  if (!fields.fullName.trim()) errors.fullName = 'Enter your full name.';
  else if (fields.fullName.trim().length > 200) errors.fullName = 'Use at most 200 characters.';
  if (!fields.email.trim()) errors.email = 'Email is required';
  else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(fields.email.trim())) errors.email = 'Email must be a valid email address';
  if (!fields.password) errors.password = 'Create a password.';
  else if (new TextEncoder().encode(fields.password).length > 72) errors.password = 'Use at most 72 UTF-8 bytes. Accented characters and symbols may use more than one byte.';
  else if (passwordRules(fields.password).some(rule => !rule.met)) errors.password = 'Your password must meet all four requirements below.';
  if (!fields.confirmation) errors.confirmation = 'Enter your password again.';
  else if (fields.confirmation !== fields.password) errors.confirmation = 'Your passwords do not match.';
  return errors;
}
