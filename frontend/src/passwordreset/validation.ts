import { passwordRules } from '../signup/validation';

export const RESET_LINK_MESSAGE = 'This reset link is invalid or has expired. Please request a new one.';
export const PASSWORD_POLICY_MESSAGE =
  'Password must be at least 8 characters and include an uppercase letter, a lowercase letter, and a number, with no spaces';

const TOKEN = /^[A-Za-z0-9_-]{43}$/;

export function resetToken(hash: string) {
  const token = new URLSearchParams(hash.startsWith('#') ? hash.slice(1) : hash).get('token') ?? '';
  return TOKEN.test(token) ? token : '';
}

export function validateEmail(email: string) {
  if (!email.trim()) return 'Email is required';
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) return 'Email must be a valid email address';
  return '';
}

export function validateReset(fields: { password: string; confirmation: string }) {
  const errors: { password?: string; confirmation?: string } = {};
  if (!fields.password) errors.password = 'Password is required';
  else if (new TextEncoder().encode(fields.password).length > 72) errors.password = 'Password must be at most 72 UTF-8 bytes';
  else if (Array.from(fields.password).length < 8) errors.password = 'Password must be at least 8 characters';
  else if (/[\p{White_Space}\u001c-\u001f]/u.test(fields.password)) errors.password = 'Password must not contain spaces';
  else if (passwordRules(fields.password).some(rule => !rule.met)) errors.password = PASSWORD_POLICY_MESSAGE;
  if (!fields.confirmation) errors.confirmation = 'Enter your password again.';
  else if (fields.confirmation !== fields.password) errors.confirmation = 'Passwords do not match';
  return errors;
}
