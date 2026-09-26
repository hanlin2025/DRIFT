import type { ReactNode } from 'react';

export function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="auth-layout">
      <aside className="brand-panel" aria-label="About DRIFT">
        <a className="wordmark" href="/signup" aria-label="DRIFT home">
          <svg viewBox="0 0 32 32" aria-hidden="true"><path d="M4 25 14 7h14L18 25H4Zm6-5h8l5-9h-8l-5 9Z" fill="currentColor" /></svg>
          DRIFT<span className="wordmark-dot">.</span>
        </a>
        <div className="brand-copy">
          <p className="eyebrow"><span className="status-dot" /> A CLEARER COURSE</p>
          <h1>Every connection<br /> starts here<span>.</span></h1>
          <p>Bring your shipments, your team, and your next move into one shared view.</p>
        </div>
        <div className="route-illustration" aria-hidden="true">
          <div className="map-grid" />
          <svg viewBox="0 0 480 280" className="route-map">
            <path className="shoreline" d="M-30 220 70 185 115 125 145 115 205 165 260 145 295 75 335 90 380 30 500 0" />
            <path className="route-line" d="M65 205C140 205 100 95 205 115S285 215 355 115 420 90 455 60" />
            <circle className="port-halo" cx="205" cy="115" r="22" />
            <circle className="port" cx="65" cy="205" r="5" />
            <circle className="port port-main" cx="205" cy="115" r="6" />
            <circle className="port" cx="355" cy="115" r="5" />
            <text x="40" y="232">ORIGIN</text><text x="171" y="80">SINGAPORE</text><text x="326" y="148">ONWARD</text>
          </svg>
          <div className="cargo cargo-one" /><div className="cargo cargo-two" /><div className="cargo cargo-three" />
          <span className="map-coordinate">1.3521 N / 103.8198 E</span>
        </div>
        <footer className="brand-footer"><span>Built for the connections that matter.</span><span>SINGAPORE / DRIFT</span></footer>
      </aside>
      <main className="form-panel">
        <div className="panel-topline"><span>YOUR DRIFT WORKSPACE</span><span className="secure-label"><span aria-hidden="true">&#9671;</span> INVITATION ACCESS</span></div>
        <div className="form-content">{children}</div>
        <footer className="form-footer">A shared view. A more certain next step.</footer>
      </main>
    </div>
  );
}
