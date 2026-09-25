export type SignupFields = { fullName: string; password: string; confirmation: string };
export type FieldErrors = Partial<Record<keyof SignupFields | 'email', string>>;

export function passwordRules(password: string) {
  const characters = Array.from(password);
  return [
    { label: 'At least 8 characters', met: characters.length >= 8 },
    { label: 'Uppercase and lowercase', met: /\p{Uppercase}/u.test(password) && /\p{Lowercase}/u.test(password) },
    { label: 'At least one number', met: /\p{Nd}/u.test(password) },
    { label: 'No spaces', met: password.length > 0 && !/[\p{White_Space}\u001c-\u001f]/u.test(password) },
  ];
}

export function validateSignup(fields: SignupFields): FieldErrors {
  const errors: FieldErrors = {};
  if (!fields.fullName.trim()) errors.fullName = 'Enter your full name.';
  else if (fields.fullName.trim().length > 200) errors.fullName = 'Use at most 200 characters.';
  if (!fields.password) errors.password = 'Create a password.';
  else if (new TextEncoder().encode(fields.password).length > 72) errors.password = 'Use at most 72 UTF-8 bytes. Accented characters and symbols may use more than one byte.';
  else if (passwordRules(fields.password).some(rule => !rule.met)) errors.password = 'Your password must meet all four requirements below.';
  if (!fields.confirmation) errors.confirmation = 'Enter your password again.';
  else if (fields.confirmation !== fields.password) errors.confirmation = 'Your passwords do not match.';
  return errors;
}
