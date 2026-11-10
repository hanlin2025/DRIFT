import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { passwordRules } from '../signup/validation';
import { ApiError } from '../signup/api';
import { getProfile, RISK_LEVELS, roleLabel, updateProfile, type Profile, type RiskLevel } from './api';

function riskLevelLabel(level: RiskLevel) {
  switch (level) {
    case 'LOW':
      return 'Low';
    case 'MEDIUM':
      return 'Medium';
    case 'HIGH':
      return 'High';
    case 'CRITICAL':
      return 'Critical';
    default: {
      const exhaustive: never = level;
      return exhaustive;
    }
  }
}

function RiskLevelField({ value, disabled, onChange }: {
  value: RiskLevel;
  disabled: boolean;
  onChange: (level: RiskLevel) => void;
}) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const listId = useId();

  useEffect(() => {
    if (!open) return;
    function close(event: MouseEvent) {
      if (event.target instanceof Node && root.current?.contains(event.target)) return;
      setOpen(false);
    }
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [open]);

  return (
    <div className="risk-select" ref={root}>
      <button
        id="profile-risk-level"
        type="button"
        className="risk-select-button"
        role="combobox"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={listId}
        disabled={disabled}
        onClick={() => setOpen(current => !current)}
      >
        {riskLevelLabel(value)}
      </button>
      {open ? (
        <ul id={listId} className="risk-select-menu" role="listbox" aria-label="Minimum risk level">
          {RISK_LEVELS.map(level => (
            <li key={level}>
              <button type="button" role="option" aria-selected={level === value} onClick={() => { onChange(level); setOpen(false); }}>{riskLevelLabel(level)}</button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

export function ProfilePage({ token, onSessionEnded, onPasswordChanged, onNameSaved }: {
  token: string;
  onSessionEnded: () => void;
  onPasswordChanged: () => void;
  onNameSaved: (fullName: string) => void;
}) {
  const [profile, setProfile] = useState<Profile | null>(null);
  const [fullName, setFullName] = useState('');
  const [notifications, setNotifications] = useState(false);
  const [minimumLevel, setMinimumLevel] = useState<RiskLevel>('HIGH');
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [problem, setProblem] = useState('');
  const [saved, setSaved] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => { document.title = 'Profile | DRIFT'; }, []);

  useEffect(() => {
    let active = true;
    setProblem('');
    setProfile(null);
    getProfile(token).then(found => {
      if (!active) return;
      setProfile(found);
      setFullName(found.fullName);
      setNotifications(found.riskEmailNotificationsEnabled);
      setMinimumLevel(found.minimumRiskNotificationLevel);
    }).catch((reason: unknown) => {
      if (!active) return;
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      setProblem(reason instanceof Error ? reason.message : 'We could not reach DRIFT. Check your connection and try again.');
    });
    return () => { active = false; };
  }, [token, attempt, onSessionEnded]);

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!profile || submitting) return;
    setSubmitting(true);
    setProblem('');
    setSaved('');
    const changingPassword = currentPassword !== '' || newPassword !== '';
    try {
      const updated = await updateProfile(token, {
        fullName,
        riskEmailNotificationsEnabled: notifications,
        minimumRiskNotificationLevel: minimumLevel,
        ...(changingPassword ? { currentPassword, newPassword } : {}),
      });
      setProfile(updated);
      setFullName(updated.fullName);
      setNotifications(updated.riskEmailNotificationsEnabled);
      setMinimumLevel(updated.minimumRiskNotificationLevel);
      setCurrentPassword('');
      setNewPassword('');
      onNameSaved(updated.fullName);
      if (changingPassword) {
        onPasswordChanged();
        return;
      }
      setSaved('Profile saved.');
    } catch (reason: unknown) {
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      setProblem(reason instanceof Error ? reason.message : 'We could not reach DRIFT. Check your connection and try again.');
    } finally {
      setSubmitting(false);
    }
  }

  if (!profile && problem) {
    return <div className="error-notice" role="alert">{problem}<br /><button type="button" className="retry-button" onClick={() => setAttempt(value => value + 1)}>Try again</button></div>;
  }
  if (!profile) return <div className="notice" role="status">Loading your profile...</div>;

  return (
    <>
      <div className="page-heading">
        <div>
          <p className="eyebrow">YOUR ACCOUNT</p>
          <h2>Profile</h2>
        </div>
      </div>
      <form className="profile-page" onSubmit={save}>
      {saved ? <div className="success-notice" role="status">{saved}</div> : null}
      {problem ? <div className="error-notice" role="alert">{problem}</div> : null}
      <div className="field">
        <label htmlFor="profile-name">Full name</label>
        <input id="profile-name" name="fullName" value={fullName} onChange={event => setFullName(event.target.value)} autoComplete="name" disabled={submitting} />
      </div>
      <div className="field">
        <label htmlFor="profile-email">Email</label>
        <input id="profile-email" name="email" value={profile.email} readOnly />
      </div>
      <div className="field">
        <label htmlFor="profile-company">Company</label>
        <input id="profile-company" name="company" value={profile.company?.name ?? 'No company assigned'} readOnly />
      </div>
      <div className="field">
        <label htmlFor="profile-role">Role</label>
        <input id="profile-role" name="role" value={roleLabel(profile.role)} readOnly />
      </div>
      <fieldset>
        <legend>Shipment-risk email</legend>
        <label className="profile-check" htmlFor="profile-notifications">
          <input id="profile-notifications" type="checkbox" checked={notifications} onChange={event => setNotifications(event.target.checked)} disabled={submitting} />
          Email me when a shipment risk reaches this level
        </label>
        <div className="field">
          <label htmlFor="profile-risk-level">Minimum risk level</label>
          <RiskLevelField value={minimumLevel} disabled={submitting} onChange={setMinimumLevel} />
        </div>
      </fieldset>
      <fieldset>
        <legend>Change password</legend>
        <div className="field">
          <label htmlFor="profile-current-password">Current password</label>
          <div className="password-input">
            <input id="profile-current-password" name="currentPassword" type={showPassword ? 'text' : 'password'} autoComplete="current-password" value={currentPassword} onChange={event => setCurrentPassword(event.target.value)} disabled={submitting} />
            <button type="button" className="visibility-button" aria-label={showPassword ? 'Hide password' : 'Show password'} aria-pressed={showPassword} onClick={() => setShowPassword(value => !value)}>{showPassword ? 'Hide' : 'Show'}</button>
          </div>
        </div>
        <div className="field">
          <label htmlFor="profile-new-password">New password</label>
          <input id="profile-new-password" name="newPassword" type={showPassword ? 'text' : 'password'} autoComplete="new-password" value={newPassword} onChange={event => setNewPassword(event.target.value)} disabled={submitting} aria-describedby="profile-password-rules" />
          <ul id="profile-password-rules" className="password-rules" aria-label="Password requirements">{passwordRules(newPassword).map(rule => <li key={rule.label} data-met={rule.met}><span className="sr-only">{rule.met ? 'Met: ' : 'Required: '}</span>{rule.label}</li>)}</ul>
        </div>
      </fieldset>
      <button type="submit" className="primary-button" disabled={submitting}>{submitting ? 'Saving...' : 'Save profile'} <span aria-hidden="true">&#8594;</span></button>
    </form>
    </>
  );
}
