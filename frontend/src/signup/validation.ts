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

export type CompanySignupFields = {
  companyCode: string; companyName: string; fullName: string; email: string; password: string; confirmation: string;
};
export type CompanySignupErrors = Partial<Record<keyof CompanySignupFields, string>>;

// Mirrors OrganisationIdentity on the server, which upper-cases the stored code.
const COMPANY_CODE = /^[A-Za-z][A-Za-z0-9_]{0,49}$/;
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/;

export function validateCompanySignup(fields: CompanySignupFields): CompanySignupErrors {
  const errors: CompanySignupErrors = {};
  const code = fields.companyCode.trim();
  if (!COMPANY_CODE.test(code)) errors.companyCode = 'Enter an organisation code using letters, digits, and underscores.';
  const name = fields.companyName.trim();
  if (!name || CONTROL.test(name)) errors.companyName = 'Enter an organisation name.';
  else if (name.length > 200) errors.companyName = 'Use at most 200 characters.';
  const person = validateSignup({ fullName: fields.fullName, password: fields.password, confirmation: fields.confirmation });
  if (person.fullName) errors.fullName = person.fullName;
  const email = fields.email.trim();
  if (!email) errors.email = 'Enter your work email.';
  else if (email.length > 320) errors.email = 'Use at most 320 characters.';
  else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) errors.email = 'Enter a valid email address.';
  if (person.password) errors.password = person.password;
  if (person.confirmation) errors.confirmation = person.confirmation;
  return errors;
}

/** Reads an invitation token from a pasted link (…#invite=TOKEN) or from the token itself. */
export function invitationTokenFrom(value: string) {
  const trimmed = value.trim();
  const marker = trimmed.indexOf('#');
  if (marker >= 0) return new URLSearchParams(trimmed.slice(marker + 1)).get('invite')?.trim() ?? '';
  return trimmed;
}
