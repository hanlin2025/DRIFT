import { useEffect, useState, type FormEvent } from 'react';
import { registerAccount, resolveInvitation, type Invitation } from './api';

const roleNames = { IMPORTER: 'Importer', FREIGHT_FORWARDER: 'Freight forwarder' };

export function SignupPage() {
  const [token, setToken] = useState(() => new URLSearchParams(window.location.hash.slice(1)).get('invite') ?? '');
  const [code, setCode] = useState('');
  const [invitation, setInvitation] = useState<Invitation | null>(null);
  const [loading, setLoading] = useState(Boolean(token));
  const [error, setError] = useState('');
  const [fullName, setFullName] = useState('');
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [created, setCreated] = useState(false);

  useEffect(() => {
    if (!token) return;
    let active = true;
    setLoading(true);
    setError('');
    setInvitation(null);
    resolveInvitation(token).then(value => { if (active) setInvitation(value); })
      .catch((reason: unknown) => { if (active) setError(reason instanceof Error ? reason.message : 'Unable to load your invitation.'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [token]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!invitation || submitting) return;
    setSubmitting(true);
    setError('');
    try {
      await registerAccount({ fullName, email: invitation.email, password, invitationToken: token });
      setPassword(''); setConfirmation(''); setCreated(true);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Registration could not be completed.');
    } finally { setSubmitting(false); }
  }

  return (
    <>
      <p className="eyebrow form-eyebrow">WELCOME ABOARD</p>
      <h2>Create your account</h2>
      <p className="intro">Your team is already here. Let's get you connected.</p>
      {loading ? <div className="notice" role="status">Checking your invitation...</div> : !invitation ? (
        <section className="invitation-entry">
          <span className="invitation-icon" aria-hidden="true">&#8599;</span>
          <h3>It starts with an invitation.</h3>
          <p>Open the link shared by your administrator, or enter your invitation code below.</p>
          <form onSubmit={event => { event.preventDefault(); setToken(code.trim()); }}>
            <label htmlFor="invitation-code">Invitation code</label>
            <input id="invitation-code" value={code} onChange={event => setCode(event.target.value)} required autoComplete="off" />
            <button className="primary-button" type="submit">Continue with invitation <span aria-hidden="true">&#8594;</span></button>
          </form>
          <p className="invitation-help">No invitation? Ask your company administrator to invite you to DRIFT.</p>
        </section>
      ) : (
        <form onSubmit={submit} className="signup-form">
          <div className="company-card"><span className="company-symbol" aria-hidden="true">&#9637;</span><div><span className="overline">YOU'RE JOINING</span><strong>{invitation.companyName}</strong></div><span className="verified-tag">Invited</span></div>
          <div className="field"><label htmlFor="full-name">Full name</label><input id="full-name" name="fullName" autoComplete="name" placeholder="e.g. Alex Tan" value={fullName} onChange={event => setFullName(event.target.value)} required maxLength={200} disabled={submitting || created} /></div>
          <div className="field"><label htmlFor="email">Work email <span className="locked-label">FROM YOUR INVITATION</span></label><input id="email" name="email" type="email" value={invitation.email} autoComplete="email" readOnly /><p className="field-hint">Your email connects you to your company's workspace.</p></div>
          <div className="field"><label htmlFor="role">Your role <span className="locked-label">ASSIGNED</span></label><input id="role" value={roleNames[invitation.role]} readOnly /></div>
          <div className="field"><label htmlFor="password">Password</label><div className="password-input"><input id="password" name="password" type={showPassword ? 'text' : 'password'} autoComplete="new-password" placeholder="Create a strong password" value={password} onChange={event => setPassword(event.target.value)} required minLength={8} disabled={submitting || created} /><button type="button" className="visibility-button" aria-label={showPassword ? 'Hide password' : 'Show password'} aria-pressed={showPassword} onClick={() => setShowPassword(value => !value)}>{showPassword ? 'Hide' : 'Show'}</button></div><p className="field-hint">8+ characters, uppercase, lowercase and a number. No spaces.</p></div>
          <div className="field"><label htmlFor="confirm-password">Confirm password</label><input id="confirm-password" name="confirmation" type={showPassword ? 'text' : 'password'} autoComplete="new-password" placeholder="Enter your password again" value={confirmation} onChange={event => setConfirmation(event.target.value)} required disabled={submitting || created} /></div>
          {created ? <div className="success-notice" role="status">Your account has been created.</div> : <button type="submit" className="primary-button" disabled={submitting}>{submitting ? 'Creating your account...' : 'Create account'}<span aria-hidden="true">&#8594;</span></button>}
          <p className="membership-note">Your company and role are set by your invitation.</p>
        </form>
      )}
      {error && <div className="error-notice" role="alert">{error}</div>}
      <p className="login-prompt">Already part of the team? <a href="/login">Log in <span aria-hidden="true">&#8599;</span></a></p>
    </>
  );
}
