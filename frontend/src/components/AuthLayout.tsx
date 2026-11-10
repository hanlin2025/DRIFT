import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';

function Wordmark() {
  return (
    <Link className="wordmark" to="/login" aria-label="DRIFT home">
      <svg viewBox="0 0 32 32" aria-hidden="true"><path d="M4 25 14 7h14L18 25H4Zm6-5h8l5-9h-8l-5 9Z" fill="currentColor" /></svg>
      DRIFT<span className="wordmark-dot">.</span>
    </Link>
  );
}

export function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="auth-shell">
      <div className="auth-visual">
        <img src="/auth-ship.jpg" alt="" />
      </div>
      <main className="auth-panel">
        <div className="auth-frame">
          <Wordmark />
          <div className="auth-card">{children}</div>
        </div>
      </main>
    </div>
  );
}
