import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { loadSession } from '../login/api';
import { clearSession, writeSession, type Session } from '../session/session';
import { ApiError } from '../signup/api';
import { archiveShipment, createShipment, getCdg201ArrivalEvidence, getShipment, getShipmentHistory, getShipmentTracking, getVesselTrack, listShipments, restoreShipment, updateShipment, type ArrivalClassification, type ArrivalComparison, type Shipment, type ShipmentRecommendation, type ShipmentTracking, type VesselTrack } from './api';
import { ShipmentDetail, TRACKING_POLL_MS } from './ShipmentDetail';
import { SELECT_POINT, TRACK_LOAD_FAILED } from './TrackHistory';
import { formatWhen } from './ShipmentForm';

vi.mock('../login/api', () => ({ login: vi.fn(), loadSession: vi.fn() }));
vi.mock('../shipment/api', () => ({ createShipment: vi.fn(), updateShipment: vi.fn(), listShipments: vi.fn(), getShipment: vi.fn(), getShipmentTracking: vi.fn(), getCdg201ArrivalEvidence: vi.fn(), archiveShipment: vi.fn(), restoreShipment: vi.fn(), getVesselTrack: vi.fn(), getShipmentHistory: vi.fn() }));

const importer: Session = {
  token: 'importer-token',
  expiresAt: '2099-01-01T00:00:00Z',
  id: 8,
  fullName: 'Ivan Lim',
  email: 'ivan@example.com',
  role: 'IMPORTER',
  company: { id: 2, code: 'STRAITS_FRESH_DEMO', name: 'Straits Fresh Imports (Demo)' },
};

const forwarder: Session = { ...importer, token: 'forwarder-token', id: 4, role: 'FREIGHT_FORWARDER' };
const admin: Session = { ...forwarder, token: 'admin-token', id: 2, role: 'ADMIN' };
const manager: Session = { ...forwarder, token: 'manager-token', id: 3, role: 'LOGISTICS_MANAGER' };

const shipment: Shipment = {
  id: 7,
  shipmentReference: 'HL-1001',
  origin: 'Singapore',
  destination: 'Jakarta',
  transshipmentPort: 'Singapore',
  motherVessel: 'Ever Steady',
  plannedMotherArrivalAt: '2026-10-02T00:00:00Z',
  feederVessel: 'Straits Feeder',
  plannedFeederDepartureAt: '2026-10-03T10:00:00Z',
  createdAt: '2026-09-29T04:00:00Z',
  version: 3,
  companyId: 2,
  connectionWindow: { duration: '1 day 10 hours', totalSeconds: 122400 },
  remainingConnectionWindow: null,
  risk: { level: 'LOW', explanation: 'At least 24 hours to transfer cargo between vessels.' },
  status: null,
  motherVesselPosition: {
    mmsi: '563001234',
    vesselName: 'PACIFIC HORIZON',
    latitude: 1.264,
    longitude: 103.82,
    speedOverGroundKnots: 12.4,
    courseOverGroundDegrees: 175,
    trueHeadingDegrees: 176,
    ingestedAt: '2026-10-05T03:00:00Z',
  },
  feederVesselPosition: null,
  arrivalComparison: null,
};

function Location() { return <output data-testid="location">{useLocation().pathname}</output>; }

function factValue(record: HTMLElement, label: string) {
  const fact = within(record).getByText(label, { exact: true, selector: 'dt' }).closest('div');
  expect(fact).not.toBeNull();
  return within(fact as HTMLElement).getAllByRole('definition')[0];
}

function open(account: Session, path: string) {
  writeSession(account);
  vi.mocked(loadSession).mockResolvedValue({ ...account, token: account.token });
  return render(<MemoryRouter initialEntries={[path]}><AppRoutes /><Location /></MemoryRouter>);
}

const MOTHER_WARNING = 'The AIS vessel name does not match the stored mother vessel.';
const FEEDER_WARNING = 'The AIS vessel name does not match the stored feeder vessel.';

function namedFix(vesselName: string, latitude: number) {
  return { ...shipment.motherVesselPosition!, vesselName, latitude };
}

function positionText(record: HTMLElement, text: string) {
  return within(record).getByText((_, node) => node?.tagName === 'DD' && node.textContent === text);
}

async function findPosition(record: HTMLElement, text: string) {
  return within(record).findByText((_, node) => node?.tagName === 'DD' && node.textContent === text);
}

function tracked(motherName: string, motherWarning: string | null, feederName: string, feederWarning: string | null, motherLatitude: number, feederLatitude: number): ShipmentTracking {
  return {
    motherVesselName: shipment.motherVessel,
    motherVessel: namedFix(motherName, motherLatitude),
    motherVesselNameWarning: motherWarning,
    feederVesselName: shipment.feederVessel,
    feederVessel: namedFix(feederName, feederLatitude),
    feederVesselNameWarning: feederWarning,
  };
}

const tracking: ShipmentTracking = {
  motherVesselName: shipment.motherVessel,
  motherVessel: shipment.motherVesselPosition,
  motherVesselNameWarning: null,
  feederVesselName: shipment.feederVessel,
  feederVessel: shipment.feederVesselPosition,
  feederVesselNameWarning: null,
};

const track: VesselTrack = {
  vessel: 'MOTHER',
  vesselName: 'Ever Steady',
  mmsi: '563001234',
  windowHours: 24,
  from: '2026-10-04T03:00:00Z',
  to: '2026-10-05T03:00:00Z',
  slowdownThresholdKnots: 3,
  pointLimit: 500,
  truncated: false,
  points: [
    { id: 101, latitude: 1.1, longitude: 103.6, speedOverGroundKnots: 11, courseOverGroundDegrees: 90, trueHeadingDegrees: 91, ingestedAt: '2026-10-05T01:00:00Z' },
    { id: 102, latitude: 1.2, longitude: 103.7, speedOverGroundKnots: 1.5, courseOverGroundDegrees: 180, trueHeadingDegrees: 182, ingestedAt: '2026-10-05T02:00:00Z' },
    { id: 103, latitude: 1.25, longitude: 103.75, speedOverGroundKnots: 0.4, courseOverGroundDegrees: null, trueHeadingDegrees: null, ingestedAt: '2026-10-05T03:00:00Z' },
  ],
};

beforeEach(() => {
  vi.resetAllMocks();
  clearSession();
  vi.mocked(getShipmentHistory).mockResolvedValue({ shipmentId: 1, earlierChangesUnrecorded: false, entries: [] });
  vi.mocked(listShipments).mockResolvedValue([shipment]);
  vi.mocked(getShipment).mockResolvedValue(shipment);
  vi.mocked(getShipmentTracking).mockResolvedValue(tracking);
  vi.mocked(getCdg201ArrivalEvidence).mockResolvedValue(null);
  vi.mocked(getVesselTrack).mockResolvedValue(track);
});

describe('connection window on the shipment detail', () => {
  it('shows the planned window on the shipment detail', async () => {
    open(forwarder, '/freight-forwarder');
    const user = userEvent.setup();
    const link = await screen.findByRole('link', { name: 'HL-1001' });
    expect(link).toHaveAttribute('href', '/freight-forwarder/shipments/7');
    await user.click(link);
    expect(await screen.findByRole('heading', { name: 'HL-1001' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/freight-forwarder/shipments/7');
    expect(getShipment).toHaveBeenCalledWith('forwarder-token', '7');
    expect(getShipmentTracking).toHaveBeenCalledWith('forwarder-token', '7');
    const record = screen.getByRole('article', { name: 'Shipment HL-1001' });
    expect(within(record).getAllByText('Singapore')).toHaveLength(2);
    expect(within(record).getByText('Origin')).toBeInTheDocument();
    expect(within(record).getByText('Destination')).toBeInTheDocument();
    expect(within(record).getByText('Registered')).toBeInTheDocument();
    expect(within(record).getByText('Connection window')).toBeInTheDocument();
    expect(within(record).getByText('1 day 10 hours')).toBeInTheDocument();
    expect(factValue(record, 'Remaining connection window')).toHaveTextContent('No remaining connection window');
    expect(factValue(record, 'Remaining connection window')).toHaveClass('is-missing');
    expect(within(record).getByText('Low')).toHaveClass('risk-badge', 'risk-low');
    expect(within(record).getByText(/At least 24 hours to transfer cargo between vessels\./)).toBeInTheDocument();
    expect(within(record).getByText('PACIFIC HORIZON · 1.264, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(within(record).getByText('No live AIS position')).toBeInTheDocument();
    expect(screen.getByRole('figure', { name: 'Planned route' })).toBeInTheDocument();
    expect(await screen.findByText(/MOTHER LIVE PACIFIC HORIZON · 1.264, 103.82 · 12.4 kn · Last updated/)).toBeInTheDocument();
    expect(screen.queryByText(/FEEDER LIVE/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: 'Back' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/freight-forwarder');
    expect(await screen.findByRole('heading', { name: 'Shipment portfolio' })).toBeInTheDocument();
  });

  it('shows when the shipment has no planned connection window', async () => {
    vi.mocked(getShipment).mockResolvedValue({
      ...shipment,
      connectionWindow: null,
      motherVesselPosition: null,
    });
    vi.mocked(getShipmentTracking).mockResolvedValue({
      motherVesselName: shipment.motherVessel,
      motherVessel: null,
      motherVesselNameWarning: null,
      feederVesselName: shipment.feederVessel,
      feederVessel: null,
      feederVesselNameWarning: null,
    });
    open(importer, '/importer/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(within(record).getByText('No planned connection window')).toBeInTheDocument();
    expect(factValue(record, 'Remaining connection window')).toHaveTextContent('No remaining connection window');
    expect(within(record).getAllByText('No live AIS position')).toHaveLength(2);
    expect(screen.getByRole('link', { name: 'Back' })).toHaveAttribute('href', '/importer');
  });

  it('shows a pending feeder vessel and departure when none is assigned', async () => {
    vi.mocked(getShipment).mockResolvedValue({
      ...shipment,
      feederVessel: null,
      plannedFeederDepartureAt: null,
      connectionWindow: null,
      risk: null,
    });
    vi.mocked(getShipmentTracking).mockResolvedValue({ ...tracking, feederVesselName: null, feederVessel: null });
    open(importer, '/importer/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    const pending = within(record).getAllByText('Pending assignment');
    expect(pending).toHaveLength(2);
    pending.forEach(field => expect(field).toHaveClass('is-missing'));
    expect(factValue(record, 'Risk')).toHaveClass('is-missing');
    expect(within(factValue(record, 'Risk')).getByText('Not assessed')).toHaveClass('risk-badge', 'risk-unassessed');
    expect(within(record).getByText('No planned connection window')).toBeInTheDocument();
    expect(factValue(record, 'Remaining connection window')).toHaveTextContent('No remaining connection window');
    expect(factValue(record, 'Remaining connection window')).toHaveClass('is-missing');
    expect(within(record).queryByText(/planned connection window is being used/)).not.toBeInTheDocument();
    expect(within(record).getByText('No live AIS position')).toBeInTheDocument();
    expect(screen.getByText('FEEDER Pending assignment')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows the remaining window beside the planned window and keeps the returned risk', async () => {
    vi.mocked(getShipment).mockResolvedValue({
      ...shipment,
      remainingConnectionWindow: { status: 'AVAILABLE', duration: '4 hours', totalSeconds: 14400, missedBy: null, missedBySeconds: null },
      risk: { level: 'HIGH', explanation: "Less than 12 hours remain to transfer cargo, based on the mother vessel's estimated arrival." },
    });
    open(forwarder, '/freight-forwarder/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    const labels = within(record).getAllByRole('term').map(term => term.textContent);
    expect(labels.indexOf('Remaining connection window')).toBe(labels.indexOf('Connection window') + 1);
    expect(factValue(record, 'Connection window')).toHaveTextContent('1 day 10 hours');
    expect(factValue(record, 'Remaining connection window')).toHaveTextContent('4 hours');
    expect(factValue(record, 'Remaining connection window')).not.toHaveClass('is-missing');
    expect(factValue(record, 'Risk')).toHaveTextContent("High Less than 12 hours remain to transfer cargo, based on the mother vessel's estimated arrival.");
  });

  it('shows the feed status, report times, and a seeded position', async () => {
    const seeded = { ...shipment.motherVesselPosition!, source: 'SEEDED' as const, vesselName: 'STRAIT RUNNER', mmsi: '563177002', ingestedAt: '2026-10-05T03:01:00Z' };
    vi.mocked(getShipment).mockResolvedValue({
      ...shipment,
      aisFeed: { status: 'OFFLINE', lastReceivedAt: '2026-10-05T03:00:00Z' },
      motherOperationalState: { label: 'At anchor', description: 'At anchor near Singapore', unreliable: false, ingestedAt: '2026-10-05T03:00:00Z' },
      feederOperationalState: { label: 'Moored', description: 'Moored', unreliable: false, ingestedAt: '2026-10-05T03:01:00Z' },
      feederVesselPosition: seeded,
      arrivalComparison: availableComparison('ON_SCHEDULE', 'On schedule · estimated 1h 30m later than planned'),
      remainingConnectionWindow: { status: 'AVAILABLE', duration: '6 hours', totalSeconds: 21600, missedBy: null, missedBySeconds: null },
    });
    vi.mocked(getShipmentTracking).mockResolvedValue({
      ...tracking,
      feederVesselName: 'STRAIT RUNNER',
      feederVessel: seeded,
    });
    const shown = open(forwarder, '/freight-forwarder/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    const feed = factValue(record, 'AIS feed');
    expect(feed).toHaveTextContent('Offline');
    expect(feed).toHaveTextContent('The analysis is based on the last data received.');
    expect(within(feed).getByText('Last received')).toBeInTheDocument();
    expect(within(feed).getByText(formatWhen('2026-10-05T03:00:00Z'))).toBeInTheDocument();
    const mother = factValue(record, 'Mother vessel operation');
    expect(mother).toHaveTextContent('At anchor near Singapore');
    expect(within(mother).getByText(formatWhen('2026-10-05T03:00:00Z'))).toBeInTheDocument();
    const feeder = factValue(record, 'Feeder vessel operation');
    expect(feeder).toHaveTextContent('Moored');
    expect(within(feeder).getByText(formatWhen('2026-10-05T03:01:00Z'))).toBeInTheDocument();
    const remaining = factValue(record, 'Remaining connection window');
    expect(remaining).toHaveTextContent('6 hours');
    expect(within(remaining).getByText(formatWhen('2026-10-01T18:00:00Z'))).toBeInTheDocument();
    expect(factValue(record, 'Feeder vessel position')).toHaveTextContent('Seeded position');
    expect(screen.getByText(/FEEDER SEEDED STRAIT RUNNER/)).toBeInTheDocument();
    expect(screen.getAllByText(/Last updated/).length).toBeGreaterThan(0);
    shown.unmount();

    for (const status of ['LIVE', 'DELAYED'] as const) {
      vi.mocked(getShipment).mockResolvedValue({
        ...shipment,
        aisFeed: { status, lastReceivedAt: '2026-10-22T09:00:00Z' },
      });
      const view = open(forwarder, '/freight-forwarder/shipments/7');
      const next = await screen.findByRole('article', { name: 'Shipment HL-1001' });
      const nextFeed = factValue(next, 'AIS feed');
      expect(nextFeed).toHaveTextContent(status === 'LIVE' ? 'Live' : 'Delayed');
      expect(nextFeed).not.toHaveTextContent('The analysis is based on the last data received.');
      expect(within(nextFeed).getByText(formatWhen('2026-10-22T09:00:00Z'))).toBeInTheDocument();
      view.unmount();
    }
  });

  it('updates the AIS feed while the page stays open', async () => {
    vi.mocked(getShipment)
      .mockResolvedValueOnce({ ...shipment, aisFeed: { status: 'LIVE', lastReceivedAt: '2026-10-22T09:00:00Z' } })
      .mockResolvedValueOnce({ ...shipment, aisFeed: { status: 'DELAYED', lastReceivedAt: '2026-10-22T08:00:00Z' } });
    open(forwarder, '/freight-forwarder/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(factValue(record, 'AIS feed')).toHaveTextContent('Live');
    expect(factValue(record, 'AIS feed')).toHaveTextContent(formatWhen('2026-10-22T09:00:00Z'));
    expect(factValue(record, 'AIS feed')).not.toHaveTextContent('The analysis is based on the last data received.');

    await userEvent.setup().click(screen.getByRole('button', { name: 'Refresh' }));
    expect(factValue(record, 'AIS feed')).toHaveTextContent('Delayed');
    expect(factValue(record, 'AIS feed')).toHaveTextContent(formatWhen('2026-10-22T08:00:00Z'));
    expect(factValue(record, 'AIS feed')).not.toHaveTextContent('The analysis is based on the last data received.');
    expect(screen.getByRole('heading', { name: 'HL-1001' })).toBeInTheDocument();
    expect(screen.queryByText('Loading shipment...')).not.toBeInTheDocument();
    expect(getShipment).toHaveBeenCalledTimes(2);
  });

  it('updates the AIS feed when the open page is polled', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    try {
      vi.mocked(getShipment)
        .mockResolvedValueOnce({ ...shipment, aisFeed: { status: 'DELAYED', lastReceivedAt: '2026-10-22T08:00:00Z' } })
        .mockResolvedValueOnce({ ...shipment, aisFeed: { status: 'OFFLINE', lastReceivedAt: '2026-10-22T07:00:00Z' } });
      open(forwarder, '/freight-forwarder/shipments/7');
      const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
      expect(factValue(record, 'AIS feed')).toHaveTextContent('Delayed');
      expect(factValue(record, 'AIS feed')).not.toHaveTextContent('The analysis is based on the last data received.');
      await vi.advanceTimersByTimeAsync(TRACKING_POLL_MS);
      expect(factValue(record, 'AIS feed')).toHaveTextContent('Offline');
      expect(factValue(record, 'AIS feed')).toHaveTextContent('The analysis is based on the last data received.');
      expect(factValue(record, 'AIS feed')).toHaveTextContent(formatWhen('2026-10-22T07:00:00Z'));
      expect(screen.getByRole('heading', { name: 'HL-1001' })).toBeInTheDocument();
      expect(screen.queryByText('Loading shipment...')).not.toBeInTheDocument();
      expect(getShipment).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it('keeps the AIS feed already shown when a refresh of the detail fails', async () => {
    vi.mocked(getShipment)
      .mockResolvedValueOnce({ ...shipment, aisFeed: { status: 'LIVE', lastReceivedAt: '2026-10-22T09:00:00Z' } })
      .mockRejectedValueOnce(new ApiError('We could not reach DRIFT. Check your connection and try again.', 0));
    open(forwarder, '/freight-forwarder/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(factValue(record, 'AIS feed')).toHaveTextContent('Live');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Refresh' }));
    expect(await screen.findByText('The latest shipment detail could not be loaded. The page is still showing the last detail it received.')).toBeInTheDocument();
    expect(factValue(record, 'AIS feed')).toHaveTextContent('Live');
    expect(factValue(record, 'AIS feed')).toHaveTextContent(formatWhen('2026-10-22T09:00:00Z'));
    expect(screen.getByRole('heading', { name: 'HL-1001' })).toBeInTheDocument();
  });

  it('says a missed connection is expected and by how much', async () => {
    vi.mocked(getShipment).mockResolvedValue({
      ...shipment,
      remainingConnectionWindow: { status: 'MISSED', duration: null, totalSeconds: null, missedBy: '2 hours', missedBySeconds: 7200 },
      risk: { level: 'CRITICAL', explanation: 'The connection is expected to be missed by 2 hours.' },
    });
    open(importer, '/importer/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(factValue(record, 'Connection window')).toHaveTextContent('1 day 10 hours');
    expect(factValue(record, 'Remaining connection window')).toHaveTextContent('The connection is expected to be missed by 2 hours.');
    expect(factValue(record, 'Risk')).toHaveTextContent('Critical The connection is expected to be missed by 2 hours.');
  });

  it('says a sub-second miss and an equal arrival by the amount returned', async () => {
    vi.mocked(getShipment).mockResolvedValue({
      ...shipment,
      remainingConnectionWindow: { status: 'MISSED', duration: null, totalSeconds: null, missedBy: 'less than 1 second', missedBySeconds: 0 },
      risk: { level: 'CRITICAL', explanation: 'The connection is expected to be missed by less than 1 second.' },
    });
    const first = open(forwarder, '/freight-forwarder/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(factValue(record, 'Remaining connection window')).toHaveTextContent('The connection is expected to be missed by less than 1 second.');
    first.unmount();

    vi.mocked(getShipment).mockResolvedValue({
      ...shipment,
      remainingConnectionWindow: { status: 'MISSED', duration: null, totalSeconds: null, missedBy: '0 seconds', missedBySeconds: 0 },
      risk: { level: 'CRITICAL', explanation: "The connection is expected to be missed. The mother vessel's estimated arrival is at the planned feeder departure." },
    });
    open(forwarder, '/freight-forwarder/shipments/7');
    const equal = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(factValue(equal, 'Remaining connection window')).toHaveTextContent('The connection is expected to be missed by 0 seconds.');
    expect(factValue(equal, 'Risk')).toHaveTextContent("Critical The connection is expected to be missed. The mother vessel's estimated arrival is at the planned feeder departure.");
  });

  it('says the planned window is being used, and why, when the estimate cannot be used', async () => {
    vi.mocked(getShipment).mockResolvedValue({
      ...shipment,
      remainingConnectionWindow: null,
      arrivalComparison: {
        estimatedArrivalAt: null,
        limitation: null,
        speedOverGroundKnots: null,
        classification: null,
        difference: null,
        differenceSeconds: null,
        differenceNanos: null,
        lastReceivedAt: null,
        possiblyOutdated: null,
        unavailableReason: { code: 'UNUSABLE_SPEED', message: "The mother vessel's speed is missing or below 0.5 knots." },
      },
      risk: { level: 'LOW', explanation: 'At least 24 hours to transfer cargo between vessels. The planned connection window is being used.' },
    });
    open(forwarder, '/freight-forwarder/shipments/7');
    const unavailable = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(factValue(unavailable, 'Connection window')).toHaveTextContent('1 day 10 hours');
    expect(factValue(unavailable, 'Remaining connection window')).toHaveTextContent("The planned connection window is being used. The mother vessel's speed is missing or below 0.5 knots.");
    expect(factValue(unavailable, 'Risk')).toHaveTextContent('Low At least 24 hours to transfer cargo between vessels. The planned connection window is being used.');
  });

  it('says the planned window is being used when the position is possibly outdated', async () => {
    vi.mocked(getShipment).mockResolvedValue({
      ...shipment,
      remainingConnectionWindow: null,
      arrivalComparison: availableComparison('ON_SCHEDULE', 'On schedule · estimated at the planned time', true),
      risk: { level: 'HIGH', explanation: 'Less than 12 hours to transfer cargo between vessels. The planned connection window is being used.' },
    });
    open(forwarder, '/freight-forwarder/shipments/7');
    const outdated = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(factValue(outdated, 'Remaining connection window')).toHaveTextContent("The planned connection window is being used because the mother vessel's position is possibly outdated.");
    expect(factValue(outdated, 'Risk')).toHaveTextContent('High Less than 12 hours to transfer cargo between vessels. The planned connection window is being used.');
  });

  it('shows the same windows on an archived shipment without adding it to the active list', async () => {
    vi.mocked(listShipments).mockResolvedValue([]);
    vi.mocked(getShipment).mockResolvedValue({
      ...shipment,
      status: 'ARCHIVED',
      remainingConnectionWindow: { status: 'AVAILABLE', duration: 'less than 1 second', totalSeconds: 0, missedBy: null, missedBySeconds: null },
      risk: { level: 'HIGH', explanation: "Less than 12 hours remain to transfer cargo, based on the mother vessel's estimated arrival." },
    });
    const portfolio = open(forwarder, '/freight-forwarder');
    expect(await screen.findByRole('heading', { name: 'Shipment portfolio' })).toBeInTheDocument();
    expect(listShipments).toHaveBeenCalledWith('forwarder-token');
    expect(screen.queryByText('HL-1001')).not.toBeInTheDocument();
    expect(screen.queryByText('Remaining connection window')).not.toBeInTheDocument();
    portfolio.unmount();

    open(forwarder, '/freight-forwarder/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(factValue(record, 'Connection window')).toHaveTextContent('1 day 10 hours');
    expect(factValue(record, 'Remaining connection window')).toHaveTextContent('less than 1 second');
    expect(factValue(record, 'Risk')).toHaveTextContent("High Less than 12 hours remain to transfer cargo, based on the mother vessel's estimated arrival.");
  });

  it('keeps the recorded position in the facts when tracking cannot be loaded', async () => {
    vi.mocked(getShipmentTracking).mockRejectedValue(new ApiError('Shipment not found', 404));
    open(forwarder, '/freight-forwarder/shipments/7');
    expect(await screen.findByText('PACIFIC HORIZON · 1.264, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(screen.queryByText(/MOTHER LIVE/)).not.toBeInTheDocument();
    expect(screen.queryByText(/FEEDER LIVE/)).not.toBeInTheDocument();
  });

  it('shows a loading skeleton until the shipment arrives', async () => {
    let arrive: (found: Shipment) => void = () => {};
    vi.mocked(getShipment).mockReturnValue(new Promise(resolve => { arrive = resolve; }));
    open(importer, '/importer/shipments/7');
    const loading = (await screen.findByText('Loading shipment...')).closest('[role="status"]');
    expect(loading).toHaveAttribute('aria-busy', 'true');
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
    arrive(shipment);
    expect(await screen.findByRole('article', { name: 'Shipment HL-1001' })).toBeInTheDocument();
    expect(screen.queryByText('Loading shipment...')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit shipment' })).toBeInTheDocument();
  });

  it('replaces the loading skeleton with the error when the shipment is forbidden', async () => {
    vi.mocked(getShipment).mockRejectedValue(new ApiError('You do not have access to this shipment', 403));
    open(importer, '/importer/shipments/8');
    expect(await screen.findByRole('alert')).toHaveTextContent('You do not have access to this shipment');
    expect(screen.queryByText('AIS feed')).not.toBeInTheDocument();
    expect(screen.queryByText('Last received')).not.toBeInTheDocument();
    expect(screen.queryByText('Seeded')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Access denied' })).toBeInTheDocument();
    expect(screen.queryByText('Connection window')).not.toBeInTheDocument();
    expect(screen.queryByText('Remaining connection window')).not.toBeInTheDocument();
    expect(screen.queryByText('Risk')).not.toBeInTheDocument();
    expect(screen.queryByText('Recommendation')).not.toBeInTheDocument();
    expect(screen.queryByText('No action')).not.toBeInTheDocument();
    expect(screen.queryByText('Loading shipment...')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
    expect(screen.queryByRole('complementary', { name: 'Mother vessel AIS' })).not.toBeInTheDocument();
  });

  it('reports a missing shipment without a retry', async () => {
    vi.mocked(getShipment).mockRejectedValue(new ApiError('Shipment not found', 404));
    open(importer, '/importer/shipments/99');
    expect(await screen.findByRole('alert')).toHaveTextContent('Shipment not found or has been removed.');
    expect(screen.getByRole('heading', { name: 'Shipment not found' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
  });

  it('shows the not found page for an invalid shipment id', async () => {
    vi.mocked(getShipment).mockRejectedValue(new ApiError('Shipment not found', 404));
    open(importer, '/importer/shipments/not-a-number');
    expect(await screen.findByRole('heading', { name: 'Shipment not found' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Back/ })).toHaveAttribute('href', '/importer');
  });

  it('retries a failed detail request', async () => {
    vi.mocked(getShipment)
      .mockRejectedValueOnce(new ApiError('We could not reach DRIFT. Check your connection and try again.', 0))
      .mockResolvedValueOnce(shipment);
    open(forwarder, '/freight-forwarder/shipments/7');
    expect(await screen.findByRole('alert')).toHaveTextContent('We could not reach DRIFT.');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('article', { name: 'Shipment HL-1001' })).toBeInTheDocument();
  });

  it('ends the session when the detail request is rejected', async () => {
    vi.mocked(getShipment).mockRejectedValue(new ApiError('Your session has ended. Log in again.', 401));
    open(forwarder, '/freight-forwarder/shipments/7');
    expect(await screen.findByRole('heading', { name: 'Welcome to DRIFT.' })).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Your session has ended. Log in again.');
    expect(sessionStorage.getItem('drift.session')).toBeNull();
  });

  it("sends another role's shipment link to that role's shipment address", async () => {
    open(importer, '/freight-forwarder/shipments/7');
    expect(await screen.findByRole('heading', { name: 'HL-1001' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/importer/shipments/7');
    expect(getShipment).toHaveBeenCalledWith('importer-token', '7');
  });

  it('refreshes the vessel position without leaving the detail page', async () => {
    vi.mocked(getShipmentTracking)
      .mockResolvedValueOnce(tracking)
      .mockResolvedValueOnce({
        ...tracking,
        motherVessel: { ...tracking.motherVessel!, latitude: 2.5 },
      });
    open(forwarder, '/freight-forwarder/shipments/7');
    expect(await screen.findByText('PACIFIC HORIZON · 1.264, 103.82 · 12.4 kn')).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Refresh' }));
    expect(await screen.findByText('PACIFIC HORIZON · 2.5, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/freight-forwarder/shipments/7');
    expect(getShipment).toHaveBeenCalledTimes(2);
    expect(getShipmentTracking).toHaveBeenCalledTimes(2);
  });

  it('polls tracking while the detail page stays open', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    open(forwarder, '/freight-forwarder/shipments/7');
    expect(await screen.findByRole('heading', { name: 'HL-1001' })).toBeInTheDocument();
    expect(getShipmentTracking).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(TRACKING_POLL_MS);
    expect(getShipmentTracking).toHaveBeenCalledTimes(2);
    vi.useRealTimers();
  });

  it('keeps the newer position when an older tracking response arrives later', async () => {
    let resolveInitial: (value: ShipmentTracking) => void = () => {};
    const initial = new Promise<ShipmentTracking>(resolve => { resolveInitial = resolve; });
    let resolveRefresh: (value: ShipmentTracking) => void = () => {};
    const refreshed = new Promise<ShipmentTracking>(resolve => { resolveRefresh = resolve; });
    vi.mocked(getShipmentTracking).mockReturnValueOnce(initial).mockReturnValueOnce(refreshed);
    open(forwarder, '/freight-forwarder/shipments/7');
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Refresh' }));
    await act(async () => {
      resolveRefresh({ ...tracking, motherVessel: { ...tracking.motherVessel!, latitude: 2.5 } });
    });
    expect(await screen.findByText('PACIFIC HORIZON · 2.5, 103.82 · 12.4 kn')).toBeInTheDocument();
    await act(async () => { resolveInitial(tracking); });
    expect(screen.getByText('PACIFIC HORIZON · 2.5, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(screen.queryByText('PACIFIC HORIZON · 1.264, 103.82 · 12.4 kn')).not.toBeInTheDocument();
  });

  it('does not apply a refresh from the previous shipment', async () => {
    let resolvePrevious: (value: ShipmentTracking) => void = () => {};
    const previous = new Promise<ShipmentTracking>(resolve => { resolvePrevious = resolve; });
    vi.mocked(getShipmentTracking)
      .mockResolvedValueOnce(tracking)
      .mockReturnValueOnce(previous)
      .mockResolvedValueOnce({ ...tracking, motherVessel: { ...tracking.motherVessel!, latitude: 9 } });
    const view = render(
      <MemoryRouter>
        <ShipmentDetail token="forwarder-token" shipmentId="7" basePath="/freight-forwarder" onSessionEnded={() => {}} />
      </MemoryRouter>,
    );
    expect(await screen.findByText('PACIFIC HORIZON · 1.264, 103.82 · 12.4 kn')).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Refresh' }));
    view.rerender(
      <MemoryRouter>
        <ShipmentDetail token="forwarder-token" shipmentId="8" basePath="/freight-forwarder" onSessionEnded={() => {}} />
      </MemoryRouter>,
    );
    expect(await screen.findByText('PACIFIC HORIZON · 9, 103.82 · 12.4 kn')).toBeInTheDocument();
    await act(async () => {
      resolvePrevious({ ...tracking, motherVessel: { ...tracking.motherVessel!, latitude: 3 } });
    });
    expect(screen.getByText('PACIFIC HORIZON · 9, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(screen.queryByText('PACIFIC HORIZON · 3, 103.82 · 12.4 kn')).not.toBeInTheDocument();
  });

  it('shows no live position when tracking has loaded without a fix', async () => {
    vi.mocked(getShipmentTracking).mockResolvedValue({
      motherVesselName: shipment.motherVessel,
      motherVessel: null,
      motherVesselNameWarning: null,
      feederVesselName: shipment.feederVessel,
      feederVessel: null,
      feederVesselNameWarning: null,
    });
    open(forwarder, '/freight-forwarder/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(within(record).getAllByText('No live AIS position')).toHaveLength(2);
  });

  it('shows the mother vessel speed, course and heading next to the map and updates them on each poll', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    vi.mocked(getShipmentTracking)
      .mockResolvedValueOnce(tracking)
      .mockResolvedValueOnce({
        ...tracking,
        motherVessel: { ...tracking.motherVessel!, speedOverGroundKnots: 9.1, courseOverGroundDegrees: 268, trueHeadingDegrees: 270 },
      });
    open(forwarder, '/freight-forwarder/shipments/7');
    const panel = await screen.findByRole('complementary', { name: 'Mother vessel AIS' });
    expect(await within(panel).findByText('12.4 kn')).toBeInTheDocument();
    expect(within(panel).getByText('175° (S)')).toBeInTheDocument();
    expect(within(panel).getByText('176° (S)')).toBeInTheDocument();
    expect(within(panel).getByText(/^Updated /)).toBeInTheDocument();
    await vi.advanceTimersByTimeAsync(TRACKING_POLL_MS);
    expect(await within(panel).findByText('9.1 kn')).toBeInTheDocument();
    expect(within(panel).getByText('268° (W)')).toBeInTheDocument();
    expect(within(panel).getByText('270° (W)')).toBeInTheDocument();
    expect(getShipment).toHaveBeenCalledTimes(2);
    vi.useRealTimers();
  });

  it('says no mother vessel position has been received when tracking has no fix', async () => {
    vi.mocked(getShipmentTracking).mockResolvedValue({ ...tracking, motherVessel: null });
    open(forwarder, '/freight-forwarder/shipments/7');
    const panel = await screen.findByRole('complementary', { name: 'Mother vessel AIS' });
    expect(await within(panel).findByText('No position received yet')).toBeInTheDocument();
    expect(within(panel).getByText('Ever Steady')).toBeInTheDocument();
  });

  it('lets an importer edit all shipment fields and sends the hidden version token', async () => {
    const updated = { ...shipment, shipmentReference: 'HL-UPDATED', origin: 'Busan, KR', version: 4 };
    const refreshedTracking: ShipmentTracking = {
      motherVesselName: updated.motherVessel,
      motherVessel: { ...shipment.motherVesselPosition!, vesselName: 'NEW MOTHER POSITION', latitude: 1.31 },
      motherVesselNameWarning: null,
      feederVesselName: updated.feederVessel,
      feederVessel: null,
      feederVesselNameWarning: null,
    };
    vi.mocked(updateShipment).mockResolvedValue(updated);
    vi.mocked(getShipment).mockReset();
    vi.mocked(getShipment).mockResolvedValueOnce(shipment).mockResolvedValueOnce(updated);
    vi.mocked(getShipmentTracking).mockReset();
    vi.mocked(getShipmentTracking).mockResolvedValueOnce(tracking).mockResolvedValueOnce(refreshedTracking);
    open(importer, '/importer/shipments/7');
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Edit shipment' }));
    expect(screen.getByRole('heading', { name: 'Edit shipment' })).toBeInTheDocument();
    expect(screen.getByLabelText('Shipment reference')).toHaveValue('HL-1001');
    expect(screen.getByLabelText('Origin')).toHaveValue('Singapore');
    expect(screen.queryByLabelText(/version/i)).not.toBeInTheDocument();

    await user.clear(screen.getByLabelText('Shipment reference'));
    await user.type(screen.getByLabelText('Shipment reference'), 'HL-UPDATED');
    await user.clear(screen.getByLabelText('Origin'));
    await user.type(screen.getByLabelText('Origin'), 'Busan, KR');
    await user.click(screen.getByRole('button', { name: 'Save shipment' }));

    expect(await screen.findByRole('heading', { name: 'HL-UPDATED' })).toBeInTheDocument();
    expect(updateShipment).toHaveBeenCalledWith('importer-token', 7, expect.objectContaining({
      shipmentReference: 'HL-UPDATED', origin: 'Busan, KR', version: 3,
    }));
    expect(getShipmentTracking).toHaveBeenCalledTimes(2);
    expect(await screen.findAllByText(/NEW MOTHER POSITION/)).not.toHaveLength(0);
  });

  it('preserves seconds when saving an unchanged itinerary', async () => {
    const precise = {
      ...shipment,
      plannedMotherArrivalAt: '2026-10-02T00:00:30Z',
      plannedFeederDepartureAt: '2026-10-03T10:00:45Z',
      version: 4,
    };
    vi.mocked(getShipment).mockResolvedValue(precise);
    vi.mocked(updateShipment).mockResolvedValue(precise);
    open(importer, '/importer/shipments/7');
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Edit shipment' }));
    expect(screen.getByLabelText('Planned mother-vessel arrival')).toHaveAttribute('step', '1');
    expect(screen.getByLabelText('Planned feeder-vessel departure')).toHaveAttribute('step', '1');
    await user.click(screen.getByRole('button', { name: 'Save shipment' }));

    const request = vi.mocked(updateShipment).mock.calls[0][2];
    expect(Date.parse(request.plannedMotherArrivalAt)).toBe(Date.parse(precise.plannedMotherArrivalAt));
    expect(Date.parse(request.plannedFeederDepartureAt)).toBe(Date.parse(precise.plannedFeederDepartureAt));
  });

  it('shows duplicate-reference and stale-version conflicts differently', async () => {
    open(forwarder, '/freight-forwarder/shipments/7');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Edit shipment' }));
    vi.mocked(updateShipment).mockRejectedValueOnce(new ApiError('A shipment with this reference already exists for your company', 409));
    await user.click(screen.getByRole('button', { name: 'Save shipment' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('A shipment with this reference already exists for your company');
    expect(screen.getByLabelText('Shipment reference')).toHaveAttribute('aria-invalid', 'true');

    vi.mocked(updateShipment).mockRejectedValueOnce(new ApiError('This shipment was updated by another user. Refresh it and try again.', 409));
    await user.click(screen.getByRole('button', { name: 'Save shipment' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('This shipment was updated by someone else while you were editing it. Reload the latest details before trying again.');
    await user.click(screen.getByRole('button', { name: 'Reload latest details' }));
    expect(await screen.findByRole('heading', { name: 'HL-1001' })).toBeInTheDocument();
  });

  it('reloads and displays the server state after a stale update conflict', async () => {
    const refreshed = { ...shipment, shipmentReference: 'HL-SERVER-CURRENT', origin: 'Busan, KR', version: 4 };
    vi.mocked(getShipment).mockReset();
    vi.mocked(getShipment).mockResolvedValueOnce(shipment).mockResolvedValueOnce(refreshed);
    vi.mocked(updateShipment).mockRejectedValueOnce(new ApiError('This shipment was updated by another user. Refresh it and try again.', 409));
    open(forwarder, '/freight-forwarder/shipments/7');
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Edit shipment' }));
    await user.clear(screen.getByLabelText('Shipment reference'));
    await user.type(screen.getByLabelText('Shipment reference'), 'HL-STALE-LOCAL');
    await user.click(screen.getByRole('button', { name: 'Save shipment' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('This shipment was updated by someone else while you were editing it.');
    expect(screen.getByLabelText('Shipment reference')).toHaveValue('HL-STALE-LOCAL');
    await user.click(screen.getByRole('button', { name: 'Reload latest details' }));

    expect(await screen.findByRole('heading', { name: 'HL-SERVER-CURRENT' })).toBeInTheDocument();
    expect(screen.queryByDisplayValue('HL-STALE-LOCAL')).not.toBeInTheDocument();
    expect(getShipment).toHaveBeenCalledTimes(2);
    expect(updateShipment).toHaveBeenCalledTimes(1);
  });

  it('keeps edit mode and unsaved values when the backend rejects the update', async () => {
    vi.mocked(updateShipment).mockRejectedValueOnce(new ApiError('Shipment information is missing or invalid', 400, {
      shipmentReference: 'Shipment reference is required',
    }));
    open(importer, '/importer/shipments/7');
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Edit shipment' }));
    await user.clear(screen.getByLabelText('Shipment reference'));
    await user.type(screen.getByLabelText('Shipment reference'), 'HL-UNSAVED');
    await user.click(screen.getByRole('button', { name: 'Save shipment' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Shipment information is missing or invalid');
    expect(screen.getByRole('heading', { name: 'Edit shipment' })).toBeInTheDocument();
    expect(screen.getByLabelText('Shipment reference')).toHaveValue('HL-UNSAVED');
    expect(screen.getByText('Shipment reference is required')).toBeInTheDocument();
  });

  it('returns to the existing unavailable detail state when an edit is no longer visible', async () => {
    vi.mocked(getShipment)
      .mockResolvedValueOnce(shipment)
      .mockRejectedValueOnce(new ApiError('Shipment not found', 404));
    vi.mocked(updateShipment).mockRejectedValue(new ApiError('Shipment not found', 404));
    open(forwarder, '/freight-forwarder/shipments/7');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Edit shipment' }));
    await user.click(screen.getByRole('button', { name: 'Save shipment' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Shipment not found or has been removed.');
  });

  it('shows an archived shipment edit rejection without marking the reference invalid', async () => {
    open(forwarder, '/freight-forwarder/shipments/7');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Edit shipment' }));
    vi.mocked(updateShipment).mockRejectedValueOnce(new ApiError('This shipment cannot be edited in its current status.', 403));
    await user.click(screen.getByRole('button', { name: 'Save shipment' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('This shipment cannot be edited in its current status.');
    expect(screen.getByLabelText('Shipment reference')).not.toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('heading', { name: 'Edit shipment' })).toBeInTheDocument();
  });

  it.each([admin, manager])('shows Remove on an owned shipment opened directly by a $role', async account => {
    open(account, account.role === 'ADMIN' ? '/admin/shipments/7' : '/logistics-manager/shipments/7');
    expect(await screen.findByRole('button', { name: 'Remove HL-1001' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit shipment' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Refresh' })).toBeInTheDocument();
  });

  it.each([admin, manager])('hides Remove when a $role opens an importer-linked shipment directly', async account => {
    vi.mocked(getShipment).mockResolvedValue({ ...shipment, companyId: 11 });
    open(account, account.role === 'ADMIN' ? '/admin/shipments/7' : '/logistics-manager/shipments/7');
    expect(await screen.findByRole('heading', { name: 'HL-1001' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove HL-1001' })).not.toBeInTheDocument();
  });

  it('returns to the admin shipment list with the server message after removal', async () => {
    vi.mocked(archiveShipment).mockResolvedValue('Shipment removed successfully');
    open(admin, '/admin/shipments/7');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Remove HL-1001' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Remove' }));
    expect(archiveShipment).toHaveBeenCalledWith('admin-token', 7);
    expect(await screen.findByText('Shipment removed successfully')).toHaveAttribute('role', 'status');
    expect(screen.getByTestId('location')).toHaveTextContent('/admin/shipments');
    expect(screen.getByRole('heading', { name: 'Company shipments' })).toBeInTheDocument();
  });

  it.each([admin, manager])('shows Restore on an archived shipment owned by a $role', async account => {
    vi.mocked(getShipment).mockResolvedValue({ ...shipment, status: 'ARCHIVED' });
    open(account, account.role === 'ADMIN' ? '/admin/shipments/7' : '/logistics-manager/shipments/7');
    expect(await screen.findByRole('button', { name: 'Restore HL-1001' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove HL-1001' })).not.toBeInTheDocument();
  });

  it('hides Restore when an admin opens an archived shipment owned by another company', async () => {
    vi.mocked(getShipment).mockResolvedValue({ ...shipment, status: 'ARCHIVED', companyId: 11 });
    open(admin, '/admin/shipments/7');
    expect(await screen.findByRole('heading', { name: 'HL-1001' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Restore HL-1001' })).not.toBeInTheDocument();
  });

  it('returns to the shipment list with the server message after restore', async () => {
    vi.mocked(getShipment).mockResolvedValue({ ...shipment, status: 'ARCHIVED' });
    vi.mocked(restoreShipment).mockResolvedValue('Shipment restored successfully');
    vi.mocked(listShipments).mockResolvedValue([{ ...shipment, status: 'ACTIVE' }]);
    open(admin, '/admin/shipments/7');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Restore HL-1001' }));
    await user.click(within(screen.getByRole('dialog', { name: 'Restore shipment' })).getByRole('button', { name: 'Restore' }));
    expect(restoreShipment).toHaveBeenCalledWith('admin-token', 7);
    expect(archiveShipment).not.toHaveBeenCalled();
    expect(await screen.findByText('Shipment restored successfully')).toHaveAttribute('role', 'status');
    expect(screen.getByTestId('location')).toHaveTextContent('/admin/shipments');
    expect(screen.getByRole('heading', { name: 'Company shipments' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'HL-1001' })).toBeInTheDocument();
  });
});

describe('vessel identity on the shipment detail', () => {
  it('shows Not available when IMO, MMSI, and operator were not stored', async () => {
    open(forwarder, '/freight-forwarder/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(within(record).getByText('Mother vessel IMO').closest('div')).toHaveTextContent('Not available');
    expect(within(record).getByText('Mother vessel MMSI').closest('div')).toHaveTextContent('Not available');
    expect(within(record).getByText('Mother vessel operator').closest('div')).toHaveTextContent('Not available');
    expect(within(record).getByText('Feeder vessel IMO').closest('div')).toHaveTextContent('Not available');
    expect(within(record).getByText('Feeder vessel MMSI').closest('div')).toHaveTextContent('Not available');
    expect(within(record).getByText('Feeder vessel operator').closest('div')).toHaveTextContent('Not available');
    expect(within(record).getAllByText('Not available')).toHaveLength(6);
    expect(within(record).queryByText('The AIS vessel name does not match the stored mother vessel.')).not.toBeInTheDocument();
    expect(within(record).getByText('Ever Steady')).toBeInTheDocument();
    expect(within(record).getByText('1 day 10 hours')).toBeInTheDocument();
  });

  it('shows the stored identity and the AIS name warning from the detail', async () => {
    vi.mocked(getShipment).mockResolvedValue({
      ...shipment,
      motherVesselImo: '9387421',
      motherVesselMmsi: '563000111',
      motherVesselOperator: 'Harbourline',
      feederVesselImo: '9411882',
      feederVesselMmsi: '565987654',
      feederVesselOperator: 'Strait Feeders',
      motherVesselNameWarning: 'The AIS vessel name does not match the stored mother vessel.',
      feederVesselNameWarning: null,
    });
    vi.mocked(getShipmentTracking).mockResolvedValue({
      ...tracking,
      motherVesselNameWarning: 'The AIS vessel name does not match the stored mother vessel.',
      feederVesselNameWarning: null,
    });
    open(importer, '/importer/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(within(record).getByText('9387421')).toBeInTheDocument();
    expect(within(record).getByText('563000111')).toBeInTheDocument();
    expect(within(record).getByText('Harbourline')).toBeInTheDocument();
    expect(within(record).getByText('9411882')).toBeInTheDocument();
    expect(within(record).getByText('565987654')).toBeInTheDocument();
    expect(within(record).getByText('Strait Feeders')).toBeInTheDocument();
    expect(within(record).getByText('The AIS vessel name does not match the stored mother vessel.')).toHaveClass('vessel-name-warning');
    expect(within(record).queryByText('The AIS vessel name does not match the stored feeder vessel.')).not.toBeInTheDocument();
    expect(within(record).getByText('Ever Steady')).toBeInTheDocument();
  });

  it('clears a detail name warning when loaded tracking returns an explicit null', async () => {
    vi.mocked(getShipment).mockResolvedValue({
      ...shipment,
      motherVesselNameWarning: MOTHER_WARNING,
      feederVesselNameWarning: FEEDER_WARNING,
    });
    vi.mocked(getShipmentTracking).mockResolvedValue({
      ...tracking,
      motherVesselNameWarning: null,
      feederVesselNameWarning: null,
    });
    open(forwarder, '/freight-forwarder/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(within(record).queryByText(MOTHER_WARNING)).not.toBeInTheDocument();
    expect(within(record).queryByText(FEEDER_WARNING)).not.toBeInTheDocument();
  });

  it('keeps the detail warning beside the recorded position when tracking cannot be loaded', async () => {
    vi.mocked(getShipment).mockResolvedValue({
      ...shipment,
      motherVesselNameWarning: MOTHER_WARNING,
      feederVesselNameWarning: FEEDER_WARNING,
      feederVesselPosition: namedFix('STRAIT RUNNER', 2),
    });
    vi.mocked(getShipmentTracking).mockRejectedValue(new ApiError('Shipment not found', 404));
    open(forwarder, '/freight-forwarder/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(within(record).getByText('PACIFIC HORIZON · 1.264, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(within(record).getByText('STRAIT RUNNER · 2, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(within(record).getByText(MOTHER_WARNING)).toHaveClass('vessel-name-warning');
    expect(within(record).getByText(FEEDER_WARNING)).toHaveClass('vessel-name-warning');
  });

  it('moves both vessel warnings with the position returned by refresh', async () => {
    vi.mocked(getShipmentTracking)
      .mockResolvedValueOnce(tracked('mv   pacific   horizon', null, 'STRAIT RUNNER', FEEDER_WARNING, 1.1, 2.2))
      .mockResolvedValueOnce(tracked('PACIFIC HORIZON', MOTHER_WARNING, 'mv   strait   runner', null, 1.4, 2.4))
      .mockResolvedValueOnce(tracked('mv   pacific   horizon', null, 'STRAIT RUNNER', FEEDER_WARNING, 1.1, 2.2));
    open(forwarder, '/freight-forwarder/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(positionText(record, 'mv   pacific   horizon · 1.1, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(within(record).queryByText(MOTHER_WARNING)).not.toBeInTheDocument();
    expect(positionText(record, 'STRAIT RUNNER · 2.2, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(within(record).getByText(FEEDER_WARNING)).toHaveClass('vessel-name-warning');

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(await findPosition(record, 'PACIFIC HORIZON · 1.4, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(within(record).getByText(MOTHER_WARNING)).toHaveClass('vessel-name-warning');
    expect(positionText(record, 'mv   strait   runner · 2.4, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(within(record).queryByText(FEEDER_WARNING)).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(await findPosition(record, 'mv   pacific   horizon · 1.1, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(within(record).queryByText(MOTHER_WARNING)).not.toBeInTheDocument();
    expect(within(record).getByText(FEEDER_WARNING)).toHaveClass('vessel-name-warning');
    expect(getShipment).toHaveBeenCalledTimes(3);
    expect(getShipmentTracking).toHaveBeenCalledTimes(3);
  });

  it('applies the same warning transition when tracking is polled', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    vi.mocked(getShipmentTracking)
      .mockResolvedValueOnce(tracked('mv   pacific   horizon', null, 'mv   strait   runner', null, 1.1, 2.2))
      .mockResolvedValueOnce(tracked('PACIFIC HORIZON', MOTHER_WARNING, 'STRAIT RUNNER', FEEDER_WARNING, 1.4, 2.4));
    open(forwarder, '/freight-forwarder/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(within(record).queryByText(MOTHER_WARNING)).not.toBeInTheDocument();
    expect(within(record).queryByText(FEEDER_WARNING)).not.toBeInTheDocument();
    await vi.advanceTimersByTimeAsync(TRACKING_POLL_MS);
    expect(await within(record).findByText(MOTHER_WARNING)).toHaveClass('vessel-name-warning');
    expect(within(record).getByText(FEEDER_WARNING)).toHaveClass('vessel-name-warning');
    expect(within(record).getByText('PACIFIC HORIZON · 1.4, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(within(record).getByText('STRAIT RUNNER · 2.4, 103.82 · 12.4 kn')).toBeInTheDocument();
    vi.useRealTimers();
  });

  it('keeps the current warnings when a refresh fails', async () => {
    vi.mocked(getShipmentTracking)
      .mockResolvedValueOnce(tracked('PACIFIC HORIZON', MOTHER_WARNING, 'STRAIT RUNNER', FEEDER_WARNING, 1.4, 2.4))
      .mockRejectedValueOnce(new ApiError('We could not reach DRIFT. Check your connection and try again.', 0));
    open(forwarder, '/freight-forwarder/shipments/7');
    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(within(record).getByText(MOTHER_WARNING)).toBeInTheDocument();
    expect(within(record).getByText(FEEDER_WARNING)).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Refresh' }));
    expect(await screen.findByText('The latest position could not be loaded. The map is still showing the last position it received.')).toBeInTheDocument();
    expect(within(record).getByText('PACIFIC HORIZON · 1.4, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(within(record).getByText('STRAIT RUNNER · 2.4, 103.82 · 12.4 kn')).toBeInTheDocument();
    expect(within(record).getByText(MOTHER_WARNING)).toHaveClass('vessel-name-warning');
    expect(within(record).getByText(FEEDER_WARNING)).toHaveClass('vessel-name-warning');
  });
});

describe('shipment detail decoding', () => {
  async function decode(body: unknown) {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(body), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })));
    const api = await vi.importActual<typeof import('./api')>('./api');
    return api.getShipment('token', '7').finally(() => vi.unstubAllGlobals());
  }

  it('accepts a detail response that omits arrivalComparison', async () => {
    const { arrivalComparison: _comparison, ...omitted } = shipment;
    await expect(decode(omitted)).resolves.toMatchObject({
      arrivalComparison: null,
      connectionWindow: shipment.connectionWindow,
      remainingConnectionWindow: null,
    });
  });

  it('accepts an explicitly unassigned feeder vessel', async () => {
    await expect(decode({ ...shipment, feederVessel: null, plannedFeederDepartureAt: null }))
      .resolves.toMatchObject({ feederVessel: null, plannedFeederDepartureAt: null });
  });

  it('rejects an unknown risk level', async () => {
    await expect(decode({ ...shipment, risk: { level: 'SEVERE', explanation: 'Unknown.' } }))
      .rejects.toMatchObject({ status: 502 });
  });

  it('treats omitted vessel identity as absent and rejects a non-text value', async () => {
    await expect(decode(shipment)).resolves.toMatchObject({
      motherVesselImo: null,
      feederVesselOperator: null,
      motherVesselNameWarning: null,
    });
    await expect(decode({
      ...shipment,
      motherVesselImo: '9387421',
      motherVesselNameWarning: 'The AIS vessel name does not match the stored mother vessel.',
    })).resolves.toMatchObject({
      motherVesselImo: '9387421',
      motherVesselNameWarning: 'The AIS vessel name does not match the stored mother vessel.',
    });
    await expect(decode({ ...shipment, feederVesselMmsi: 565987654 })).rejects.toMatchObject({ status: 502 });
  });

  it('rejects a response that omits the feeder vessel fields', async () => {
    const { feederVessel: _vessel, plannedFeederDepartureAt: _departure, ...omitted } = shipment;
    await expect(decode(omitted)).rejects.toMatchObject({ status: 502 });
  });
});

const LIMITATION = 'Approximate arrival — based on straight-line distance and current speed.';

function availableComparison(classification: ArrivalClassification, difference: string, possiblyOutdated = false): ArrivalComparison {
  return {
    estimatedArrivalAt: '2026-10-02T01:30:00Z',
    limitation: LIMITATION,
    speedOverGroundKnots: 12.375,
    classification,
    difference,
    differenceSeconds: classification === 'AHEAD_OF_PLAN' ? -5400 : 5400,
    differenceNanos: 0,
    lastReceivedAt: '2026-10-01T18:00:00Z',
    possiblyOutdated,
    unavailableReason: null,
  };
}

describe('arrival comparison on the shipment detail', () => {
  async function recordFor(comparison: ArrivalComparison | null) {
    vi.mocked(getShipment).mockResolvedValue({ ...shipment, arrivalComparison: comparison });
    open(forwarder, '/freight-forwarder/shipments/7');
    return screen.findByRole('article', { name: 'Shipment HL-1001' });
  }

  it('keeps the planned arrival when the comparison is omitted', async () => {
    const record = await recordFor(null);
    expect(within(record).queryByText('Estimated arrival')).not.toBeInTheDocument();
    expect(within(record).queryByText(LIMITATION)).not.toBeInTheDocument();
    expect(within(record).queryByText('Possibly outdated')).not.toBeInTheDocument();
    expect(within(record).getByText(formatWhen(shipment.plannedMotherArrivalAt))).toBeInTheDocument();
    expect(within(record).getByText('1 day 10 hours')).toBeInTheDocument();
    expect(within(record).getByText('Low')).toHaveClass('risk-badge', 'risk-low');
  });

  it('shows an on-schedule gap beside the planned arrival', async () => {
    const comparison = availableComparison('ON_SCHEDULE', 'On schedule · estimated 1h 30m later than planned');
    const record = await recordFor(comparison);
    const planned = within(record).getByText('Planned arrival').parentElement;
    expect(planned).toContainElement(within(record).getByText('Estimated arrival'));
    expect(within(record).getByText(formatWhen(shipment.plannedMotherArrivalAt))).toBeInTheDocument();
    expect(within(record).getByText(formatWhen('2026-10-02T01:30:00Z'))).toBeInTheDocument();
    expect(within(record).getByText('On schedule · estimated 1h 30m later than planned')).toBeInTheDocument();
    expect(within(record).getByText(LIMITATION)).toBeInTheDocument();
    expect(within(record).getByText('12.375 knots')).toBeInTheDocument();
    expect(within(record).getByText('Last received')).toBeInTheDocument();
    expect(within(record).getByText(formatWhen('2026-10-01T18:00:00Z'))).toBeInTheDocument();
    expect(within(record).queryByText('Possibly outdated')).not.toBeInTheDocument();
    expect(within(record).getByText('1 day 10 hours')).toBeInTheDocument();
    expect(within(record).getByText(/At least 24 hours to transfer cargo between vessels\./)).toBeInTheDocument();
  });

  it('shows an arrival ahead of plan', async () => {
    const record = await recordFor(availableComparison('AHEAD_OF_PLAN', 'Ahead of plan · estimated 1h 30m earlier than planned'));
    expect(within(record).getByText('Ahead of plan · estimated 1h 30m earlier than planned')).toBeInTheDocument();
  });

  it('shows an arrival behind plan', async () => {
    const record = await recordFor(availableComparison('BEHIND_PLAN', 'Behind plan · estimated 6h later than planned'));
    expect(within(record).getByText('Behind plan · estimated 6h later than planned')).toBeInTheDocument();
  });

  it('explains an unavailable comparison without an arrival time', async () => {
    const record = await recordFor({
      estimatedArrivalAt: null,
      limitation: null,
      speedOverGroundKnots: null,
      classification: null,
      difference: null,
      differenceSeconds: null,
      differenceNanos: null,
      lastReceivedAt: null,
      possiblyOutdated: null,
      unavailableReason: { code: 'NO_POSITION', message: 'No AIS position has been received for the mother vessel.' },
    });
    expect(within(record).getByText('No AIS position has been received for the mother vessel.')).toBeInTheDocument();
    expect(within(record).queryByText(LIMITATION)).not.toBeInTheDocument();
    expect(within(record).queryByText('12.375 knots')).not.toBeInTheDocument();
    expect(within(record).queryByText(formatWhen('2026-10-02T01:30:00Z'))).not.toBeInTheDocument();
    expect(within(record).getByText(formatWhen(shipment.plannedMotherArrivalAt))).toBeInTheDocument();
    expect(within(record).getByText('1 day 10 hours')).toBeInTheDocument();
    expect(within(record).getByText('Low')).toBeInTheDocument();
  });

  it('says when the retained position may be outdated', async () => {
    const record = await recordFor(availableComparison('ON_SCHEDULE', 'On schedule · estimated at the planned time', true));
    expect(within(record).getByText('Possibly outdated')).toBeInTheDocument();
    expect(within(record).getByText('On schedule · estimated at the planned time')).toBeInTheDocument();
    expect(within(record).getByText(formatWhen('2026-10-02T01:30:00Z'))).toBeInTheDocument();
  });
});

describe('vessel track history on the shipment detail', () => {
  it('loads the mother vessel over the last 24 hours and shows a selected point', async () => {
    open(forwarder, '/freight-forwarder/shipments/7');
    const user = userEvent.setup();
    const history = await screen.findByRole('region', { name: 'Track history' });

    expect(getVesselTrack).toHaveBeenCalledWith('forwarder-token', '7', 'MOTHER', 24);
    expect(within(history).getByRole('button', { name: 'Mother vessel' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(history).getByRole('button', { name: '24h' })).toHaveAttribute('aria-pressed', 'true');
    expect(await within(history).findByText('3 positions in the last 24 hours · 1 slowdown segment')).toBeInTheDocument();
    expect(within(history).getByText(/Below 3 kn \(slowdown\)/)).toBeInTheDocument();

    const points = within(history).getByRole('list', { name: 'Track points' });
    await user.click(within(points).getAllByRole('button')[1]);
    const selected = within(history).getByLabelText('Selected track point');
    expect(within(selected).getByText('1.5 kn')).toBeInTheDocument();
    expect(within(selected).getByText('182° (S)')).toBeInTheDocument();
    expect(selected.querySelector('time')).toHaveAttribute('dateTime', '2026-10-05T02:00:00Z');

    await user.click(within(points).getAllByRole('button')[2]);
    const quiet = within(history).getByLabelText('Selected track point');
    expect(within(quiet).getByText('0.4 kn')).toBeInTheDocument();
    expect(within(quiet).getByText('Heading').nextElementSibling).toHaveTextContent('Not reported');
    expect(within(quiet).getByText('Heading').nextElementSibling).toHaveClass('is-missing');
  });

  it('keeps the selected observation across polls and clears it once that observation leaves the window', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    try {
      open(forwarder, '/freight-forwarder/shipments/7');
      const user = userEvent.setup();
      const history = await screen.findByRole('region', { name: 'Track history' });
      const points = await within(history).findByRole('list', { name: 'Track points' });
      await user.click(within(points).getAllByRole('button')[1]);
      expect(within(history).getByLabelText('Selected track point').querySelector('time')).toHaveAttribute('dateTime', '2026-10-05T02:00:00Z');

      const later = { id: 104, latitude: 1.3, longitude: 103.8, speedOverGroundKnots: 7, courseOverGroundDegrees: 45, trueHeadingDegrees: 45, ingestedAt: '2026-10-05T04:00:00Z' };
      vi.mocked(getVesselTrack).mockResolvedValue({ ...track, points: [...track.points.slice(1), later] });
      await act(() => vi.advanceTimersByTimeAsync(TRACKING_POLL_MS));
      expect(getVesselTrack).toHaveBeenCalledTimes(2);
      const selected = within(history).getByLabelText('Selected track point');
      expect(selected.querySelector('time')).toHaveAttribute('dateTime', '2026-10-05T02:00:00Z');
      expect(within(selected).getByText('1.5 kn')).toBeInTheDocument();

      vi.mocked(getVesselTrack).mockResolvedValue({ ...track, points: [track.points[2], later] });
      await act(() => vi.advanceTimersByTimeAsync(TRACKING_POLL_MS));
      expect(getVesselTrack).toHaveBeenCalledTimes(3);
      expect(within(history).queryByLabelText('Selected track point')).not.toBeInTheDocument();
      expect(within(history).getByText(SELECT_POINT)).toBeInTheDocument();

      vi.mocked(getVesselTrack).mockResolvedValue(track);
      await act(() => vi.advanceTimersByTimeAsync(TRACKING_POLL_MS));
      expect(getVesselTrack).toHaveBeenCalledTimes(4);
      expect(within(history).queryByLabelText('Selected track point')).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('tells apart two observations that share a timestamp', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    try {
      const twin = { ...track.points[1], id: 105, speedOverGroundKnots: 6.2, trueHeadingDegrees: 90 };
      vi.mocked(getVesselTrack).mockResolvedValue({ ...track, points: [track.points[0], track.points[1], twin, track.points[2]] });
      open(forwarder, '/freight-forwarder/shipments/7');
      const user = userEvent.setup();
      const history = await screen.findByRole('region', { name: 'Track history' });
      const points = await within(history).findByRole('list', { name: 'Track points' });
      const buttons = within(points).getAllByRole('button');
      await user.click(buttons[2]);

      const selected = within(history).getByLabelText('Selected track point');
      expect(within(selected).getByText('6.2 kn')).toBeInTheDocument();
      expect(within(selected).getByText('90° (E)')).toBeInTheDocument();
      expect(buttons.map(button => button.getAttribute('aria-pressed'))).toEqual(['false', 'false', 'true', 'false']);

      vi.mocked(getVesselTrack).mockResolvedValue({ ...track, points: [twin, track.points[2]] });
      await act(() => vi.advanceTimersByTimeAsync(TRACKING_POLL_MS));
      expect(within(within(history).getByLabelText('Selected track point')).getByText('6.2 kn')).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('does not restore a selection after a failed window switch and a later successful poll', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    try {
      open(forwarder, '/freight-forwarder/shipments/7');
      const user = userEvent.setup();
      const history = await screen.findByRole('region', { name: 'Track history' });
      const points = await within(history).findByRole('list', { name: 'Track points' });
      await user.click(within(points).getAllByRole('button')[1]);
      expect(within(history).getByLabelText('Selected track point')).toBeInTheDocument();

      vi.mocked(getVesselTrack).mockRejectedValueOnce(new ApiError('We could not reach DRIFT. Check your connection and try again.', 0));
      await user.click(within(history).getByRole('button', { name: '6h' }));
      expect(await within(history).findByText(TRACK_LOAD_FAILED)).toBeInTheDocument();

      vi.mocked(getVesselTrack).mockResolvedValue({ ...track, windowHours: 6 });
      await act(() => vi.advanceTimersByTimeAsync(TRACKING_POLL_MS));
      expect(getVesselTrack).toHaveBeenLastCalledWith('forwarder-token', '7', 'MOTHER', 6);
      expect(await within(history).findByText(/3 positions in the last 6 hours/)).toBeInTheDocument();
      expect(within(history).queryByLabelText('Selected track point')).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('reloads the track for another window or vessel', async () => {
    open(forwarder, '/freight-forwarder/shipments/7');
    const user = userEvent.setup();
    const history = await screen.findByRole('region', { name: 'Track history' });
    await within(history).findByText(/3 positions/);

    vi.mocked(getVesselTrack).mockResolvedValue({ ...track, windowHours: 6, points: track.points.slice(1) });
    await user.click(within(history).getByRole('button', { name: '6h' }));
    expect(getVesselTrack).toHaveBeenLastCalledWith('forwarder-token', '7', 'MOTHER', 6);
    expect(await within(history).findByText('2 positions in the last 6 hours · 1 slowdown segment')).toBeInTheDocument();
    expect(within(history).getByRole('button', { name: '6h' })).toHaveAttribute('aria-pressed', 'true');

    vi.mocked(getVesselTrack).mockResolvedValue({ ...track, vessel: 'FEEDER', vesselName: 'Straits Feeder', mmsi: null, windowHours: 6, points: [] });
    await user.click(within(history).getByRole('button', { name: 'Feeder vessel' }));
    expect(getVesselTrack).toHaveBeenLastCalledWith('forwarder-token', '7', 'FEEDER', 6);
    expect(await within(history).findByText('Insufficient history to draw a track for this window.')).toBeInTheDocument();
  });

  it('shows insufficient history for a single observation', async () => {
    vi.mocked(getVesselTrack).mockResolvedValue({ ...track, points: track.points.slice(0, 1) });
    open(forwarder, '/freight-forwarder/shipments/7');
    expect(await screen.findByText('Insufficient history to draw a track for this window.')).toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Track points' })).not.toBeInTheDocument();
  });

  it('disables the feeder track when no feeder vessel is assigned', async () => {
    vi.mocked(getShipment).mockResolvedValue({ ...shipment, feederVessel: null, plannedFeederDepartureAt: null });
    open(forwarder, '/freight-forwarder/shipments/7');
    const history = await screen.findByRole('region', { name: 'Track history' });
    expect(within(history).getByRole('button', { name: 'Feeder vessel' })).toBeDisabled();
  });

  it('keeps the page when the track cannot be loaded and hides it when access is denied', async () => {
    vi.mocked(getVesselTrack).mockRejectedValueOnce(new ApiError('We could not reach DRIFT. Check your connection and try again.', 0));
    open(forwarder, '/freight-forwarder/shipments/7');
    expect(await screen.findByText('Track history could not be loaded. Try Refresh.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'HL-1001' })).toBeInTheDocument();

    vi.mocked(getVesselTrack).mockRejectedValueOnce(new ApiError('You do not have access to this shipment', 403));
    await userEvent.setup().click(screen.getByRole('button', { name: 'Refresh' }));
    expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Track history' })).not.toBeInTheDocument();
  });
});

describe('change history on the shipment detail', () => {
  const created = { id: 1, changeType: 'CREATED' as const, changedBy: { id: 4, fullName: 'Alice Tan' }, recordedAt: '2026-09-29T04:00:00Z', changes: [] };
  const renamed = {
    id: 2,
    changeType: 'UPDATED' as const,
    changedBy: { id: 8, fullName: 'Ivan Lim' },
    recordedAt: '2026-09-30T04:00:00Z',
    changes: [{ field: 'shipmentReference', previousValue: 'HL-0999', newValue: 'HL-1001' }],
  };

  it('shows the history below the route facts for an importer', async () => {
    vi.mocked(getShipmentHistory).mockResolvedValue({ shipmentId: 7, earlierChangesUnrecorded: false, entries: [renamed, created] });
    open(importer, '/importer/shipments/7');

    const section = await screen.findByRole('region', { name: 'Change history' });
    const facts = screen.getByRole('article', { name: 'Shipment HL-1001' });
    expect(facts.compareDocumentPosition(section) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(await within(section).findByText('HL-0999')).toBeInTheDocument();
    expect(getShipmentHistory).toHaveBeenCalledWith('importer-token', '7');
  });

  it('loads the history again on Refresh and after an edit is saved', async () => {
    vi.mocked(getShipmentHistory)
      .mockResolvedValueOnce({ shipmentId: 7, earlierChangesUnrecorded: false, entries: [created] })
      .mockResolvedValue({ shipmentId: 7, earlierChangesUnrecorded: false, entries: [renamed, created] });
    vi.mocked(updateShipment).mockResolvedValue(shipment);
    open(forwarder, '/freight-forwarder/shipments/7');
    const user = userEvent.setup();

    expect(await screen.findByText('No changes yet')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(await screen.findByText('HL-0999')).toBeInTheDocument();
    expect(getShipmentHistory).toHaveBeenCalledTimes(2);

    await user.click(screen.getByRole('button', { name: 'Edit shipment' }));
    await user.click(screen.getByRole('button', { name: 'Save shipment' }));
    expect(await screen.findByText('HL-0999')).toBeInTheDocument();
    expect(getShipmentHistory).toHaveBeenCalledTimes(3);
  });
});

const PREPARE_WORDING = "Prepare an intervention. The connection risk is high, and the prediction affects the importer's constraints.";
const NO_ACTION_WORDING = "No action is needed. The connection risk is low, and the prediction does not affect the importer's constraints.";
const IMPACT_NOT_RETURNED = 'The impact assessment has not been returned.';
const UNAVAILABLE_RECOMMENDATION = 'A recommendation is not available because the impact assessment has not been returned.';

const prepareRecommendation: ShipmentRecommendation = {
  available: true,
  unavailable: null,
  response: { code: 'PREPARE_INTERVENTION', label: 'Prepare an intervention', wording: PREPARE_WORDING },
  riskLevel: 'HIGH',
  riskExplanation: 'Less than 12 hours to transfer cargo between vessels. The planned connection window is being used.',
  impact: { outcome: 'AFFECTS_CONSTRAINTS', detail: null },
};

const noActionRecommendation: ShipmentRecommendation = {
  available: true,
  unavailable: null,
  response: { code: 'NO_ACTION', label: 'No action', wording: NO_ACTION_WORDING },
  riskLevel: 'LOW',
  riskExplanation: 'At least 24 hours to transfer cargo between vessels.',
  impact: { outcome: 'NO_PREDICTED_IMPACT', detail: null },
};

const unavailableRecommendation: ShipmentRecommendation = {
  available: false,
  unavailable: UNAVAILABLE_RECOMMENDATION,
  response: null,
  riskLevel: 'HIGH',
  riskExplanation: prepareRecommendation.riskExplanation,
  impact: { outcome: 'UNAVAILABLE', detail: IMPACT_NOT_RETURNED },
};

describe('recommendation on the shipment detail', () => {
  it('shows the returned response with the risk and the impact that selected it', async () => {
    vi.mocked(getShipment).mockResolvedValue({
      ...shipment,
      status: 'ARCHIVED',
      risk: { level: 'HIGH', explanation: prepareRecommendation.riskExplanation },
      recommendation: prepareRecommendation,
    });
    open(importer, '/importer/shipments/7');

    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    const risk = within(record).getByText('Risk', { exact: true, selector: 'dt' }).closest('div');
    const recommendation = within(record).getByText('Recommendation', { exact: true, selector: 'dt' }).closest('div');
    expect(risk?.nextElementSibling).toBe(recommendation);
    expect(factValue(record, 'Risk')).toHaveTextContent(`High ${prepareRecommendation.riskExplanation}`);
    expect(factValue(record, 'Recommendation')).toHaveTextContent('Prepare an intervention');
    expect(factValue(record, 'Recommendation')).toHaveTextContent(PREPARE_WORDING);
    expect(factValue(record, 'Connection risk')).toHaveTextContent(`High ${prepareRecommendation.riskExplanation}`);
    expect(factValue(record, 'Impact')).toHaveTextContent("Affects the importer's constraints");
    expect(screen.queryByText(NO_ACTION_WORDING)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Modify' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Dismiss' })).not.toBeInTheDocument();
    expect(createShipment).not.toHaveBeenCalled();
    expect(updateShipment).not.toHaveBeenCalled();
    expect(archiveShipment).not.toHaveBeenCalled();
    expect(restoreShipment).not.toHaveBeenCalled();
  });

  it('names why an unavailable recommendation was not selected', async () => {
    vi.mocked(getShipment).mockResolvedValue({
      ...shipment,
      risk: { level: 'HIGH', explanation: unavailableRecommendation.riskExplanation ?? '' },
      recommendation: unavailableRecommendation,
    });
    open(forwarder, '/freight-forwarder/shipments/7');

    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(factValue(record, 'Recommendation')).toHaveTextContent(UNAVAILABLE_RECOMMENDATION);
    expect(factValue(record, 'Impact')).toHaveTextContent(IMPACT_NOT_RETURNED);
    expect(factValue(record, 'Connection risk')).toHaveTextContent(`High ${unavailableRecommendation.riskExplanation}`);
    expect(factValue(record, 'Risk')).toHaveTextContent(`High ${unavailableRecommendation.riskExplanation}`);
    expect(screen.queryByText('No action')).not.toBeInTheDocument();
    expect(screen.queryByText(NO_ACTION_WORDING)).not.toBeInTheDocument();
    expect(screen.queryByText('Prepare an intervention')).not.toBeInTheDocument();
  });

  it('shows no action only when the returned response says no action is needed', async () => {
    vi.mocked(getShipment).mockResolvedValue({
      ...shipment,
      risk: { level: 'LOW', explanation: noActionRecommendation.riskExplanation },
      recommendation: noActionRecommendation,
    });
    open(forwarder, '/freight-forwarder/shipments/7');

    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(factValue(record, 'Recommendation')).toHaveTextContent('No action');
    expect(factValue(record, 'Response')).toHaveTextContent(NO_ACTION_WORDING);
    expect(factValue(record, 'Connection risk')).toHaveTextContent(`Low ${noActionRecommendation.riskExplanation}`);
    expect(factValue(record, 'Impact')).toHaveTextContent("Does not affect the importer's constraints");
    expect(factValue(record, 'Risk')).toHaveTextContent(`Low ${noActionRecommendation.riskExplanation}`);
  });

  it('hides the recommendation when the shipment response does not include one', async () => {
    vi.mocked(getShipment).mockResolvedValue({ ...shipment, recommendation: null });
    open(forwarder, '/freight-forwarder/shipments/7');

    const record = await screen.findByRole('article', { name: 'Shipment HL-1001' });
    expect(within(record).queryByText('Recommendation')).not.toBeInTheDocument();
    expect(factValue(record, 'Risk')).toHaveTextContent('Low At least 24 hours to transfer cargo between vessels.');
    expect(screen.queryByText(NO_ACTION_WORDING)).not.toBeInTheDocument();
  });
});
