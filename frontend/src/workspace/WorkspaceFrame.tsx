import { useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { shipmentListPath, type Session } from '../session/session';

export type WorkspaceSection = 'shipments' | 'shipment' | 'register' | 'import' | 'users' | 'profile';

function Wordmark({ href }: { href: string }) {
  return (
    <a className="wordmark" href={href} aria-label="DRIFT home">
      <svg viewBox="0 0 32 32" aria-hidden="true"><path d="M4 25 14 7h14L18 25H4Zm6-5h8l5-9h-8l-5 9Z" fill="currentColor" /></svg>
      <span className="wordmark-name">DRIFT<span className="wordmark-dot">.</span></span>
    </a>
  );
}

type NavIconName = 'shipments' | 'import' | 'users' | 'profile' | 'sign-out' | 'panel';

function NavIcon({ name }: { name: NavIconName }) {
  const common = { viewBox: '0 0 24 24', 'aria-hidden': true as const, className: 'nav-icon' };
  switch (name) {
    case 'shipments':
      return <svg {...common}><path d="M4 7h16M4 12h16M4 17h10" /></svg>;
    case 'import':
      return <svg {...common}><path d="M12 4v10m0 0 3.5-3.5M12 14 8.5 10.5M5 20h14" /></svg>;
    case 'users':
      return <svg {...common}><circle cx="9" cy="8" r="2.5" /><circle cx="16" cy="9" r="2" /><path d="M4.5 18c.8-2.4 2.6-3.5 4.5-3.5s3.7 1.1 4.5 3.5M13 14.6c1.2-.7 2.5-1 3.6-1 1.6 0 3 .8 3.8 2.6" /></svg>;
    case 'profile':
      return <svg {...common}><circle cx="12" cy="8" r="3" /><path d="M5.5 19c1.2-2.8 3.3-4 6.5-4s5.3 1.2 6.5 4" /></svg>;
    case 'sign-out':
      return <svg {...common}><path d="M10 7V5h9v14h-9v-2M4 12h11m0 0-3-3m3 3-3 3" /></svg>;
    case 'panel':
      return <svg {...common}><rect x="4" y="4" width="16" height="16" rx="2" /><path d="M9 4v16" /></svg>;
    default: {
      const exhaustive: never = name;
      return exhaustive;
    }
  }
}

function readSidebarCollapsed() {
  try {
    return sessionStorage.getItem('drift.sidebar') === 'icons';
  } catch {
    return false;
  }
}

function currentPage(section: WorkspaceSection, item: 'shipments' | 'import' | 'users' | 'profile') {
  switch (section) {
    case 'shipments':
    case 'shipment':
    case 'register':
      return item === 'shipments' ? 'page' as const : undefined;
    case 'import':
      return item === 'import' ? 'page' as const : undefined;
    case 'users':
      return item === 'users' ? 'page' as const : undefined;
    case 'profile':
      return item === 'profile' ? 'page' as const : undefined;
    default: {
      const exhaustive: never = section;
      return exhaustive;
    }
  }
}

export function WorkspaceFrame({ account, onSignOut, section, children }: {
  account: Session;
  onSignOut: () => void;
  section: WorkspaceSection;
  children: ReactNode;
}) {
  const [sidebarCollapsed, setSidebarCollapsed] = useState(readSidebarCollapsed);
  const listPath = shipmentListPath(account.role);
  const shipmentsLabel = account.role === 'ADMIN' ? 'Company shipments' : 'Shipments';

  return (
    <div className={sidebarCollapsed ? 'workspace is-sidebar-collapsed' : 'workspace'}>
      <header className="workspace-bar">
        <div className="workspace-bar-head">
          <Wordmark href={listPath} />
          <button
            type="button"
            className="sidebar-toggle"
            aria-expanded={!sidebarCollapsed}
            aria-label={sidebarCollapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            onClick={() => setSidebarCollapsed(current => {
              const next = !current;
              sessionStorage.setItem('drift.sidebar', next ? 'icons' : 'open');
              return next;
            })}
          >
            <NavIcon name="panel" />
          </button>
        </div>
        <p className="workspace-identity">
          <strong>{account.fullName}</strong>
          <span>{account.company?.name ?? 'No company assigned'}</span>
        </p>
        <Link className="workspace-nav" to={listPath} aria-current={currentPage(section, 'shipments')}><NavIcon name="shipments" /><span className="nav-label">{shipmentsLabel}</span></Link>
        {account.role === 'FREIGHT_FORWARDER' ? <Link className="workspace-nav" to="/freight-forwarder/import" aria-current={currentPage(section, 'import')}><NavIcon name="import" /><span className="nav-label">Import shipments</span></Link> : null}
        {account.role === 'ADMIN' ? <Link className="workspace-nav" to="/admin" aria-current={currentPage(section, 'users')}><NavIcon name="users" /><span className="nav-label">User management</span></Link> : null}
        <Link className="workspace-nav" to="/profile" aria-current={currentPage(section, 'profile')}><NavIcon name="profile" /><span className="nav-label">Profile</span></Link>
        <button type="button" className="sign-out" onClick={onSignOut}><NavIcon name="sign-out" /><span className="nav-label">Sign out</span></button>
      </header>
      <main className="workspace-main">{children}</main>
    </div>
  );
}
