import { describe, expect, it } from 'vitest';
import { validateSignup } from './validation';

const valid = { fullName: 'Alice Tan', password: 'Example123', confirmation: 'Example123' };

describe('signup validation', () => {
  it('accepts a complete form', () => expect(validateSignup(valid)).toEqual({}));
  it('requires a name and matching confirmation', () => {
    expect(validateSignup({ ...valid, fullName: '  ', confirmation: 'Different123' })).toEqual({
      fullName: 'Enter your full name.', confirmation: 'Your passwords do not match.',
    });
  });
  it.each(['short', 'lowercase123', 'UPPERCASE123', 'NoDigitsHere', 'Has space1', 'NoSpace\u00a01'])(
    'rejects a password that violates the backend policy: %s', password => {
      expect(validateSignup({ ...valid, password }).password).toBeDefined();
    },
  );
  it('measures the BCrypt limit in UTF-8 bytes', () => {
    expect(validateSignup({ ...valid, password: 'Ab1' + '\u00e9'.repeat(35) }).password).toContain('72 UTF-8 bytes');
  });
  it('accepts Unicode uppercase, lowercase and decimal digits', () => {
    expect(validateSignup({ ...valid, password: '\u00c9\u00e9abcdef\u0661', confirmation: '\u00c9\u00e9abcdef\u0661' })).toEqual({});
  });
});
