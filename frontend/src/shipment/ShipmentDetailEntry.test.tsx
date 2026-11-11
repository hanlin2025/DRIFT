import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useNavigate, useParams, MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Session } from '../session/session';
import { ScenarioApiError } from './scenarioApi';
import * as scenarioApi from './scenarioApi';
import * as api from './api';
import { ShipmentDetailEntry } from './ShipmentDetailEntry';
import { RESET_EPOCH_ID, SESSION_ID, scenarioEnvelope } from './controlledScenarioTestFixtures';

const SESSION_ID_B = 'c13842ec-12ab-4e86-9b5a-2fcb8bd4f2a1';

const mapLifecycle = vi.hoisted(() => ({ mounts: 0, unmounts: 0 }));

vi.mock('./api', async importOriginal => {
  const actual = await importOriginal<typeof import('./api')>();
  return {
    ...actual,
    getShipment: vi.fn(), getShipmentTracking: vi.fn(), getVesselTrack: vi.fn(), getShipmentHistory: vi.fn(),
    getCdg201ArrivalEvidence: vi.fn(), getMaritimeContext: vi.fn(), getMaritimeChangeEvents: vi.fn(), listMaritimeAiAssessments: vi.fn(),
    generateMaritimeAiAssessment: vi.fn(), updateTransshipmentArrivalDeadline: vi.fn(), archiveShipment: vi.fn(),
    restoreShipment: vi.fn(), createShipment: vi.fn(), updateShipment: vi.fn(), listShipments: vi.fn(),
  };
});

vi.mock('./scenarioApi', async importOriginal => {
  const actual = await importOriginal<typeof import('./scenarioApi')>();
  return {
    ...actual,
    createScenarioSessionId: vi.fn(), createScenarioSession: vi.fn(), getScenarioSession: vi.fn(),
    startScenarioSession: vi.fn(), advanceScenarioSession: vi.fn(), resetScenarioSession: vi.fn(),
  };
});

vi.mock('./RouteMap', async () => {
  const React = await import('react');
  return {
    RouteMap: ({ displayPositions }: { displayPositions?: Array<{ role: string; label: string; name: string; latitude: number; longitude: number }> }) => {
      React.useEffect(() => {
        mapLifecycle.mounts += 1;
        return () => { mapLifecycle.unmounts += 1; };
      }, []);
      return <figure aria-label="Planned route">
        {displayPositions?.map(position => <p key={position.role}>{position.role} {position.label} {position.name} · {position.latitude}, {position.longitude}</p>)}
      </figure>;
    },
  };
});

const viewer: Session = {
  token: 'controlled-demo-token', expiresAt: '2099-01-01T00:00:00Z', id: 8,
  fullName: 'Demo Viewer', email: 'viewer@example.test', role: 'IMPORTER',
  company: { id: 2, code: 'DEMO', name: 'Demo Company' },
};

function anchor(id: number): api.Shipment {
  const key = id === 143 ? 'B' : 'A';
  return {
    id, shipmentReference: `DEMO-${key}-PORTFOLIO`, origin: 'Live Origin', destination: 'Live Destination',
    transshipmentPort: 'Live Port', motherVessel: 'Live Mother', plannedMotherArrivalAt: '2099-05-06T07:08:09Z',
    feederVessel: 'Live Feeder', plannedFeederDepartureAt: '2099-05-07T07:08:09Z', createdAt: '2025-01-01T00:00:00Z',
    version: 4, companyId: 2, connectionWindow: { duration: 'Live window', totalSeconds: 1 },
    remainingConnectionWindow: null, risk: { level: 'CRITICAL', explanation: 'LIVE RISK sentinel' }, status: 'ACTIVE',
    motherVesselPosition: { mmsi: '123456789', vesselName: 'LIVE MOTHER', latitude: 50, longitude: 60,
      speedOverGroundKnots: 90, courseOverGroundDegrees: 1, trueHeadingDegrees: 2, ingestedAt: '2099-05-06T07:08:09Z', source: 'AIS_STREAM' },
    feederVesselPosition: null, arrivalComparison: null,
  };
}

const liveTrack: api.VesselTrack = {
  vessel: 'MOTHER', vesselName: 'Live Mother', mmsi: '123456789', windowHours: 24,
  from: '2026-10-01T00:00:00Z', to: '2026-10-02T00:00:00Z', slowdownThresholdKnots: 3,
  pointLimit: 500, truncated: false, points: [], context: 'LIVE_POSITION',
};
const disabledContext: api.MaritimeContext = {
  contractVersion: 'cdg246.maritime-evidence-bundle.v1', enabled: false, sourceMode: 'DISABLED',
  assessmentCutoffUtc: '2026-10-01T00:00:00Z', shipmentAssociationConfidence: 'UNAVAILABLE',
  limitation: 'Disabled in test.', evidence: [], notices: [], aiExplanation: null, suggestedActions: null,
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function RouteHarness({ enabled = true, onSessionEnded = vi.fn() }: { enabled?: boolean; onSessionEnded?: () => void }) {
  const { shipmentId = '142' } = useParams();
  const navigate = useNavigate();
  return <>
    <button type="button" onClick={() => navigate('/importer/shipments/142', { state: { controlledScenario: true } })}>Return to A scenario</button>
    <output data-testid="route">{shipmentId}</output>
    <ShipmentDetailEntry key={shipmentId} viewer={viewer} shipmentId={shipmentId} basePath="/importer"
      onSessionEnded={onSessionEnded} canEdit={false} controlledScenarioEnabled={enabled} />
  </>;
}

function open(initial: { pathname: string; state?: unknown } = { pathname: '/importer/shipments/142' }, options: { enabled?: boolean; onSessionEnded?: () => void } = {}) {
  return render(<MemoryRouter initialEntries={[initial]}><Routes>
    <Route path="/importer/shipments/:shipmentId" element={<RouteHarness {...options} />} />
  </Routes></MemoryRouter>);
}

let server: scenarioApi.ScenarioSessionEnvelope;

beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  mapLifecycle.mounts = 0;
  mapLifecycle.unmounts = 0;
  server = scenarioEnvelope(null);

  vi.mocked(scenarioApi.createScenarioSessionId).mockReturnValueOnce(SESSION_ID).mockReturnValue(SESSION_ID_B);
  vi.mocked(scenarioApi.createScenarioSession).mockImplementation(async (_token, sessionId, request) => {
    if (request.anchorShipmentId === 143) server = scenarioEnvelope(null);
    return { ...server, sessionId };
  });
  vi.mocked(scenarioApi.getScenarioSession).mockImplementation(async (_token, sessionId) => ({ ...server, sessionId }));
  vi.mocked(scenarioApi.startScenarioSession).mockImplementation(async (_token, sessionId) => {
    server = scenarioEnvelope('BASELINE'); return { ...server, sessionId };
  });
  vi.mocked(scenarioApi.advanceScenarioSession).mockImplementation(async (_token, sessionId) => {
    server = scenarioEnvelope(server.snapshot?.stage === 'BASELINE' ? 'CHANGE' : 'WORSENED');
    return { ...server, sessionId };
  });
  vi.mocked(scenarioApi.resetScenarioSession).mockImplementation(async (_token, sessionId) => {
    server = scenarioEnvelope(null, RESET_EPOCH_ID); return { ...server, sessionId };
  });

  vi.mocked(api.getShipment).mockImplementation(async (_token, id) => anchor(Number(id)));
  vi.mocked(api.getShipmentTracking).mockResolvedValue({
    motherVesselName: 'Live Mother', motherVessel: null, motherVesselNameWarning: null,
    feederVesselName: 'Live Feeder', feederVessel: null, feederVesselNameWarning: null,
  });
  vi.mocked(api.getVesselTrack).mockResolvedValue(liveTrack);
  vi.mocked(api.getShipmentHistory).mockResolvedValue({ shipmentId: 142, earlierChangesUnrecorded: false, entries: [] });
  vi.mocked(api.getCdg201ArrivalEvidence).mockResolvedValue(null);
  vi.mocked(api.getMaritimeContext).mockResolvedValue(disabledContext);
  vi.mocked(api.getMaritimeChangeEvents).mockResolvedValue({ availability: 'AVAILABLE', events: [] });
  vi.mocked(api.listMaritimeAiAssessments).mockResolvedValue([]);
  vi.mocked(api.generateMaritimeAiAssessment).mockRejectedValue(new Error('AI generation must not be called.'));
  vi.stubGlobal('fetch', vi.fn(() => { throw new Error('Unexpected raw fetch.'); }));
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  sessionStorage.clear();
});

async function enterFromLive(user: ReturnType<typeof userEvent.setup>) {
  await screen.findByRole('button', { name: 'Enter controlled scenario' });
  expect(scenarioApi.createScenarioSession).not.toHaveBeenCalled();
  await user.click(screen.getByRole('button', { name: 'Enter controlled scenario' }));
  await waitFor(() => expect(screen.getByRole('button', { name: 'Start' })).toBeEnabled());
}

describe('mounted controlled scenario in shipment details', () => {
  it('runs Start → BASELINE → CHANGE → WORSENED, switches to independently authorized B, and exits Live explicitly', async () => {
    const user = userEvent.setup();
    open();
    await enterFromLive(user);

    expect(scenarioApi.createScenarioSession).toHaveBeenCalledExactlyOnceWith(
      viewer.token, SESSION_ID, { anchorShipmentId: 142 }, expect.anything(),
    );
    await user.click(screen.getByRole('button', { name: 'Start' }));
    await waitFor(() => expect(screen.getByRole('region', { name: 'Scenario snapshot facts' })).toHaveTextContent('BASELINE'));
    const controlledMapMounts = mapLifecycle.mounts;
    let facts = screen.getByRole('region', { name: 'Scenario snapshot facts' });
    expect(within(facts).getByText('Low')).toBeInTheDocument();
    expect(screen.getByText(/MOTHER SCENARIO CDG279 SYNTHETIC MOTHER · 2.26, 103.82/)).toBeInTheDocument();
    expect(mapLifecycle.mounts).toBe(controlledMapMounts);

    await user.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(screen.getByRole('region', { name: 'Scenario snapshot facts' })).toHaveTextContent('CHANGE'));
    facts = screen.getByRole('region', { name: 'Scenario snapshot facts' });
    expect(within(facts).getByText('Medium')).toBeInTheDocument();
    expect(facts.querySelector('time[datetime="2026-10-01T16:00:00Z"]')).toBeInTheDocument();
    expect(screen.getByText(/MOTHER SCENARIO CDG279 SYNTHETIC MOTHER · 1.86, 103.82/)).toBeInTheDocument();
    expect(mapLifecycle.mounts).toBe(controlledMapMounts);

    await user.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(screen.getByRole('region', { name: 'Scenario snapshot facts' })).toHaveTextContent('WORSENED'));
    facts = screen.getByRole('region', { name: 'Scenario snapshot facts' });
    expect(within(facts).getByText('High')).toBeInTheDocument();
    expect(facts.querySelector('time[datetime="2026-10-02T08:00:00Z"]')).toBeInTheDocument();
    expect(screen.getByText(/MOTHER SCENARIO CDG279 SYNTHETIC MOTHER · 1.66, 103.82/)).toBeInTheDocument();
    expect(mapLifecycle.mounts).toBe(controlledMapMounts);

    await user.click(screen.getByRole('button', { name: 'Feeder vessel' }));
    expect(screen.getByRole('button', { name: 'Feeder vessel' })).toHaveAttribute('aria-pressed', 'true');
    const trackReadsBeforeRecordedRequest = vi.mocked(api.getVesselTrack).mock.calls.length;
    await user.click(screen.getByRole('button', { name: 'Recorded AIS' }));
    expect(screen.getByText(/Recorded AIS is unavailable in this synthetic scenario/)).toBeInTheDocument();
    expect(api.getVesselTrack).toHaveBeenCalledTimes(trackReadsBeforeRecordedRequest);
    expect(screen.getByText(/FEEDER SCENARIO CDG279 SYNTHETIC FEEDER/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Scenario B · DEMO-B-PORTFOLIO/ }));
    await waitFor(() => expect(screen.getByTestId('route')).toHaveTextContent('143'));
    await screen.findByRole('heading', { name: 'DEMO-B-PORTFOLIO' });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Start' })).toBeEnabled());
    expect(screen.queryByRole('region', { name: 'Scenario snapshot facts' })).not.toBeInTheDocument();
    expect(scenarioApi.createScenarioSession).toHaveBeenCalledTimes(2);
    expect(scenarioApi.createScenarioSession).toHaveBeenNthCalledWith(
      2, viewer.token, SESSION_ID_B, { anchorShipmentId: 143 }, expect.anything(),
    );
    await user.click(screen.getByRole('button', { name: 'Start' }));
    await waitFor(() => expect(screen.getByRole('region', { name: 'Scenario snapshot facts' })).toHaveTextContent('BASELINE'));
    facts = screen.getByRole('region', { name: 'Scenario snapshot facts' });
    expect(within(facts).getByText('Low')).toBeInTheDocument();
    expect(within(facts).queryByText('High')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'DEMO-B-PORTFOLIO' })).toBeInTheDocument();
    expect(mapLifecycle.mounts).toBe(controlledMapMounts + 1);

    await user.click(screen.getByRole('button', { name: 'Live' }));
    await screen.findByRole('button', { name: 'Enter controlled scenario' });
    expect(screen.queryByRole('region', { name: 'Scenario snapshot facts' })).not.toBeInTheDocument();
    expect(screen.getByRole('article', { name: 'Shipment DEMO-B-PORTFOLIO' })).toBeInTheDocument();
    expect(mapLifecycle.unmounts).toBeGreaterThanOrEqual(1);
    expect(api.generateMaritimeAiAssessment).not.toHaveBeenCalled();
  });

  it('clears the previous stage immediately while Reset is pending, then shows UNSTARTED and returns to live details', async () => {
    const user = userEvent.setup();
    open({ pathname: '/importer/shipments/142', state: { controlledScenario: true } });
    await screen.findByRole('button', { name: 'Start' });
    await user.click(screen.getByRole('button', { name: 'Start' }));
    await waitFor(() => expect(screen.getByRole('region', { name: 'Scenario snapshot facts' })).toHaveTextContent('BASELINE'));
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(screen.getByRole('region', { name: 'Scenario snapshot facts' })).toHaveTextContent('CHANGE'));

    const reset = deferred<scenarioApi.ScenarioSessionEnvelope>();
    vi.mocked(scenarioApi.resetScenarioSession).mockReturnValueOnce(reset.promise);
    await user.click(screen.getByRole('button', { name: 'Reset' }));
    await waitFor(() => expect(screen.getByText(/previous snapshot is cleared from view/)).toBeInTheDocument());
    expect(screen.queryByRole('region', { name: 'Scenario snapshot facts' })).not.toBeInTheDocument();
    expect(screen.queryByText(/MOTHER SCENARIO/)).not.toBeInTheDocument();
    expect(screen.queryByRole('figure', { name: 'Planned route' })).not.toBeInTheDocument();

    await act(async () => {
      server = scenarioEnvelope(null, RESET_EPOCH_ID);
      reset.resolve(server);
    });
    await waitFor(() => expect(screen.getByText(/Last confirmed state: UNSTARTED — no scenario snapshot\./)).toBeInTheDocument());
    expect(screen.queryByRole('region', { name: 'Scenario snapshot facts' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Exit to live Shipment Details' })).toBeEnabled();

    await user.click(screen.getByRole('button', { name: 'Exit to live Shipment Details' }));
    await screen.findByRole('button', { name: 'Enter controlled scenario' });
    expect(screen.getByRole('article', { name: 'Shipment DEMO-A-PORTFOLIO' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Scenario snapshot facts' })).not.toBeInTheDocument();
  });

  it('clears protected output on scenario access denial and makes no ordinary tracking, history, or AI calls', async () => {
    vi.mocked(scenarioApi.createScenarioSession).mockRejectedValueOnce(
      new ScenarioApiError('Scenario access denied.', 403, 'api', 'SCENARIO_ACCESS_DENIED'),
    );
    open({ pathname: '/importer/shipments/142', state: { controlledScenario: true } });
    expect(await screen.findByText('Scenario access denied.')).toBeInTheDocument();
    expect(screen.getByText('Scenario access is denied. Protected scenario positions and facts have been cleared.')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Scenario snapshot facts' })).not.toBeInTheDocument();
    expect(screen.queryByText(/MOTHER SCENARIO/)).not.toBeInTheDocument();
    expect(api.getShipment).toHaveBeenCalledExactlyOnceWith(viewer.token, '142');
    expect(api.getShipmentTracking).not.toHaveBeenCalled();
    expect(api.getVesselTrack).not.toHaveBeenCalled();
    expect(api.getShipmentHistory).not.toHaveBeenCalled();
    expect(api.getMaritimeContext).not.toHaveBeenCalled();
    expect(api.listMaritimeAiAssessments).not.toHaveBeenCalled();
    expect(api.generateMaritimeAiAssessment).not.toHaveBeenCalled();
  });

  it('does not enter scenario mode unless the independently fetched anchor belongs to the viewer company', async () => {
    vi.mocked(api.getShipment).mockImplementation(async (_token, id) => ({ ...anchor(Number(id)), companyId: 999 }));
    open();
    await screen.findByRole('article', { name: 'Shipment DEMO-A-PORTFOLIO' });
    expect(screen.queryByRole('button', { name: 'Enter controlled scenario' })).not.toBeInTheDocument();
    expect(scenarioApi.createScenarioSession).not.toHaveBeenCalled();

    cleanup();
    open({ pathname: '/importer/shipments/142', state: { controlledScenario: true } });
    expect(await screen.findByText('This shipment is not authorized for the selected viewer and company.')).toBeInTheDocument();
    expect(scenarioApi.createScenarioSession).not.toHaveBeenCalled();
    expect(scenarioApi.getScenarioSession).not.toHaveBeenCalled();
  });

  it('fences a delayed B authorization after switching back to A', async () => {
    server = scenarioEnvelope('CHANGE');
    const deferredB = deferred<api.Shipment>();
    vi.mocked(api.getShipment).mockImplementation(async (_token, id) => id === '143' ? deferredB.promise : anchor(Number(id)));
    const user = userEvent.setup();
    open({ pathname: '/importer/shipments/142', state: { controlledScenario: true } });
    await screen.findByRole('region', { name: 'Scenario snapshot facts' });

    await user.click(screen.getByRole('button', { name: /Scenario B · DEMO-B-PORTFOLIO/ }));
    expect(await screen.findByText(/Verifying shipment authorization/)).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Scenario snapshot facts' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Return to A scenario' }));
    await screen.findByRole('heading', { name: 'DEMO-A-PORTFOLIO' });
    await screen.findByRole('region', { name: 'Scenario snapshot facts' });

    await act(async () => deferredB.resolve(anchor(143)));
    expect(await screen.findByRole('heading', { name: 'DEMO-A-PORTFOLIO' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'DEMO-B-PORTFOLIO' })).not.toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Scenario snapshot facts' })).toHaveTextContent('DEMO-CDG279-Api-A');
    expect(screen.queryByText(/2026-10-02T10:00:00Z/)).not.toBeInTheDocument();
  });

  it('does not expose the entry point when the deployment visibility flag is off', async () => {
    open({ pathname: '/importer/shipments/142' }, { enabled: false });
    await screen.findByRole('article', { name: 'Shipment DEMO-A-PORTFOLIO' });
    expect(screen.queryByRole('button', { name: 'Enter controlled scenario' })).not.toBeInTheDocument();
    expect(scenarioApi.createScenarioSession).not.toHaveBeenCalled();
  });
});
