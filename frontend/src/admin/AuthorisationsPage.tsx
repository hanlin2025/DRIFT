import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { loadSession } from '../login/api';
import { endSession, homePath, isExpired, readSession, sessionHasEnded, writeSession, type Session } from '../session/session';
import { searchImporterOrganisations, type ImporterOrganisation } from '../shipment/api';
import { ApiError } from '../signup/api';
import {
  endImporterAuthorisation,
  grantImporterAuthorisation,
  listImporterAuthorisations,
  type ImporterAuthorisation,
} from './api';

function Wordmark() {
  return (
    <a className="wordmark" href="/admin" aria-label="DRIFT home">
      <svg viewBox="0 0 32 32" aria-hidden="true"><path d="M4 25 14 7h14L18 25H4Zm6-5h8l5-9h-8l-5 9Z" fill="currentColor" /></svg>
      DRIFT<span className="wordmark-dot">.</span>
    </a>
  );
}

export function AuthorisationsPage({ account, onSignOut, onSessionEnded }: {
  account: Session;
  onSignOut: () => void;
  onSessionEnded: () => void;
}) {
  const [authorisations, setAuthorisations] = useState<ImporterAuthorisation[] | null>(null);
  const [matches, setMatches] = useState<ImporterOrganisation[]>([]);
  const [query, setQuery] = useState('');
  const [problem, setProblem] = useState('');
  const [saved, setSaved] = useState('');
  const [pending, setPending] = useState(false);

  const load = useCallback(async () => {
    setProblem('');
    try {
      setAuthorisations(await listImporterAuthorisations(account.token));
    } catch (reason: unknown) {
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      setAuthorisations(null);
      setProblem(reason instanceof Error ? reason.message : 'We could not reach DRIFT. Check your connection and try again.');
    }
  }, [account.token, onSessionEnded]);

  useEffect(() => { void load(); }, [load]);

  async function search(event: FormEvent) {
    event.preventDefault();
    setPending(true);
    setProblem('');
    setSaved('');
    try {
      const page = await searchImporterOrganisations(account.token, query);
      setMatches(page.items);
    } catch (reason: unknown) {
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      setProblem(reason instanceof Error ? reason.message : 'We could not reach DRIFT. Check your connection and try again.');
    } finally {
      setPending(false);
    }
  }

  async function grant(importer: ImporterOrganisation) {
    const previous = authorisations;
    setPending(true);
    setProblem('');
    setSaved('');
    try {
      const granted = await grantImporterAuthorisation(account.token, importer.id);
      setAuthorisations(current => {
        const rest = (current ?? []).filter(item => item.id !== granted.id);
        return [...rest, granted].sort((left, right) => left.importer.name.localeCompare(right.importer.name));
      });
      setSaved(`${importer.name} can receive shipments from your company.`);
    } catch (reason: unknown) {
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      setAuthorisations(previous);
      setProblem(reason instanceof Error ? reason.message : 'We could not reach DRIFT. Check your connection and try again.');
    } finally {
      setPending(false);
    }
  }

  async function end(authorisation: ImporterAuthorisation) {
    const previous = authorisations;
    setPending(true);
    setProblem('');
    setSaved('');
    try {
      await endImporterAuthorisation(account.token, authorisation.id);
      setAuthorisations(current => (current ?? []).filter(item => item.id !== authorisation.id));
      setSaved(`${authorisation.importer.name} can no longer receive new shipments. Shipments already linked stay visible.`);
    } catch (reason: unknown) {
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      setAuthorisations(previous);
      setProblem(reason instanceof Error ? reason.message : 'We could not reach DRIFT. Check your connection and try again.');
    } finally {
      setPending(false);
    }
  }

  const grantedIds = new Set((authorisations ?? []).filter(item => item.active).map(item => item.importer.id));

  return (
    <div className="workspace">
      <header className="workspace-bar">
        <Wordmark />
        <Link className="workspace-nav" to="/admin/shipments">Company shipments</Link>
        <Link className="workspace-nav" to="/admin">User management</Link>
        <Link className="workspace-nav" to="/admin/authorisations" aria-current="page">Importer authorisations</Link>
        <Link className="workspace-nav" to="/profile">Profile</Link>
        <button type="button" className="sign-out" onClick={onSignOut}>Sign out</button>
      </header>
      <main className="workspace-main">
        <p className="eyebrow">ADMINISTRATOR</p>
        <h2>Importer authorisations</h2>
        <p className="intro">Grant your company permission to link and import shipments for an importer. Ending that permission blocks new links and imports. Shipments that are already linked stay visible to that importer.</p>
        {problem && authorisations === null ? <div className="error-notice" role="alert">{problem}<br /><button type="button" className="retry-button" onClick={() => void load()}>Try again</button></div> : null}
        {authorisations === null && !problem ? <div className="notice" role="status">Loading authorisations...</div> : null}
        {authorisations !== null ? (
          <div className="admin-layout">
            {saved ? <div className="success-notice" role="status">{saved}</div> : null}
            {problem ? <div className="error-notice" role="alert">{problem}</div> : null}
            <section aria-label="Active authorisations">
              <h3>Active authorisations</h3>
              {authorisations.length === 0 ? <p>No importer is authorised yet.</p> : (
                <ul className="user-choices">
                  {authorisations.map(item => (
                    <li className="user-choice" key={item.id}>
                      <strong>{item.importer.name}</strong>
                      <span>{item.importer.code}</span>
                      <button type="button" className="retry-button" disabled={pending} onClick={() => void end(item)}>End authorisation</button>
                    </li>
                  ))}
                </ul>
              )}
            </section>
            <form className="admin-form" onSubmit={event => void search(event)}>
              <div className="field">
                <label htmlFor="importer-search">Find an importer</label>
                <input id="importer-search" value={query} onChange={event => setQuery(event.target.value)} />
              </div>
              <button type="submit" className="primary-button" disabled={pending}>Search</button>
            </form>
            {matches.length > 0 ? (
              <ul className="user-choices" aria-label="Importer search results">
                {matches.map(importer => (
                  <li className="user-choice" key={importer.id}>
                    <strong>{importer.name}</strong>
                    <span>{importer.code}</span>
                    <button type="button" className="primary-button" disabled={pending || grantedIds.has(importer.id)} onClick={() => void grant(importer)}>
                      {grantedIds.has(importer.id) ? 'Authorised' : 'Grant authorisation'}
                    </button>
                  </li>
                ))}
              </ul>
            ) : null}
          </div>
        ) : null}
      </main>
    </div>
  );
}

export function AuthorisationsRoute() {
  const navigate = useNavigate();
  const [account, setAccount] = useState<Session | null>(null);
  const [problem, setProblem] = useState('');
  const sessionEnded = useCallback(() => {
    endSession();
    navigate('/login', { replace: true, state: { expired: true } });
  }, [navigate]);

  useEffect(() => {
    const saved = readSession();
    if (!saved || isExpired(saved.expiresAt)) {
      const expired = Boolean(saved) || sessionHasEnded();
      if (saved) endSession();
      navigate('/login', { replace: true, state: expired ? { expired: true } : undefined });
      return;
    }
    let active = true;
    loadSession(saved.token).then(current => {
      if (!active) return;
      const next = { ...current, token: saved.token };
      writeSession(next);
      if (next.role !== 'ADMIN') {
        navigate(homePath(next.role), { replace: true });
        return;
      }
      setAccount(next);
    }).catch((reason: unknown) => {
      if (!active) return;
      if (reason instanceof ApiError && reason.status === 401) {
        sessionEnded();
        return;
      }
      setProblem(reason instanceof Error ? reason.message : 'We could not reach DRIFT. Check your connection and try again.');
    });
    return () => { active = false; };
  }, [navigate, sessionEnded]);

  if (problem) return <div className="error-notice" role="alert">{problem}</div>;
  if (!account) return <div className="notice" role="status">Loading authorisations...</div>;
  return <AuthorisationsPage account={account} onSessionEnded={sessionEnded} onSignOut={() => { endSession(); navigate('/login', { replace: true }); }} />;
}
