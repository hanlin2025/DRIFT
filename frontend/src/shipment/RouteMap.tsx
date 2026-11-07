import { useEffect, useRef, useState } from 'react';
import 'maplibre-gl/dist/maplibre-gl.css';
import type { Shipment, ShipmentTracking, VesselPosition, VesselTrack } from './api';
import { formatWhen, PENDING_ASSIGNMENT } from './ShipmentForm';
import { trackingNotice } from './positionNotice';
import { along, arc, locate, type LngLat } from './places';
import { trackPoints, trackSegments } from './trackSegments';

type Stop = {
  id: string;
  kicker: string;
  name: string;
  at: LngLat | null;
};

function shown(value: string | null, fallback = 'Not recorded') {
  const text = value?.trim();
  return text || fallback;
}

function stopsFor(shipment: Shipment): Stop[] {
  const origin = locate(shipment.origin);
  const hub = locate(shipment.transshipmentPort);
  const destination = locate(shipment.destination);
  return [
    { id: 'departure', kicker: 'DEPARTURE', name: shown(shipment.origin), at: origin },
    { id: 'mother', kicker: 'MOTHER', name: shown(shipment.motherVessel), at: origin && hub ? along(origin, hub, 0.58) : null },
    { id: 'transshipment', kicker: 'TRANSSHIPMENT', name: shown(shipment.transshipmentPort), at: hub },
    { id: 'feeder', kicker: 'FEEDER', name: shown(shipment.feederVessel, PENDING_ASSIGNMENT), at: hub && destination ? along(hub, destination, 0.42) : null },
    { id: 'destination', kicker: 'DESTINATION', name: shown(shipment.destination), at: destination },
  ];
}

function track(stops: Stop[]): LngLat[] {
  const ports = [stops[0], stops[2], stops[4]].map(stop => stop.at).filter((point): point is LngLat => point !== null);
  const line: LngLat[] = [];
  for (let index = 1; index < ports.length; index += 1) {
    const segment = arc(ports[index - 1], ports[index]);
    line.push(...(line.length ? segment.slice(1) : segment));
  }
  return line;
}

type RouteDisplayPosition = {
  role: 'MOTHER' | 'FEEDER';
  name: string;
  latitude: number;
  longitude: number;
  ingestedAt: string;
  speedOverGroundKnots: number | null;
  label: 'LIVE' | 'SEEDED' | 'SCENARIO' | 'RECORDED' | 'LAST KNOWN';
};

type LiveFix = {
  id: 'mother' | 'feeder';
  kicker: string;
  name: string;
  position: Pick<VesselPosition, 'latitude' | 'longitude' | 'ingestedAt' | 'speedOverGroundKnots'>;
  displayMode: 'live' | 'scenario' | 'recorded';
};

function liveFixes(tracking: ShipmentTracking | null | undefined): LiveFix[] {
  if (!tracking) return [];
  const fixes: LiveFix[] = [];
  if (tracking.motherVessel) {
    fixes.push({
      id: 'mother',
      kicker: positionKicker('MOTHER', tracking.motherVessel),
      name: tracking.motherVessel.vesselName?.trim() || tracking.motherVesselName,
      position: tracking.motherVessel,
      displayMode: 'live',
    });
  }
  if (tracking.feederVessel) {
    fixes.push({
      id: 'feeder',
      kicker: positionKicker('FEEDER', tracking.feederVessel),
      name: tracking.feederVessel.vesselName?.trim() || tracking.feederVesselName?.trim() || 'Feeder vessel',
      position: tracking.feederVessel,
      displayMode: 'live',
    });
  }
  return fixes;
}

function positionKicker(role: 'MOTHER' | 'FEEDER', position: VesselPosition) {
  return position.source === 'SEEDED' ? `${role} SEEDED` : `${role} LIVE`;
}

function displayFixes(positions: RouteDisplayPosition[] | undefined, displayMode: LiveFix['displayMode']): LiveFix[] {
  return (positions ?? []).map(position => ({
    id: position.role.toLowerCase() as LiveFix['id'],
    kicker: `${position.role} ${position.label}`,
    name: position.name,
    position,
    displayMode: position.label === 'SCENARIO' ? 'scenario' : displayMode,
  }));
}

function pin(stop: Stop) {
  const root = document.createElement('div');
  root.className = `globe-pin ${stop.id}`;
  const dot = document.createElement('span');
  dot.className = 'globe-dot';
  const copy = document.createElement('span');
  copy.className = 'globe-copy';
  const kicker = document.createElement('span');
  kicker.className = 'overline';
  kicker.textContent = stop.kicker;
  const name = document.createElement('strong');
  name.textContent = stop.name;
  if (stop.name === 'Not recorded' || stop.name === PENDING_ASSIGNMENT) name.className = 'is-missing';
  copy.append(kicker, name);
  root.append(dot, copy);
  return root;
}

type MapLibre = typeof import('maplibre-gl');

type LiveGlobe = {
  map: InstanceType<MapLibre['Map']>;
  maplibre: MapLibre;
  markers: Map<LiveFix['id'], InstanceType<MapLibre['Marker']>>;
  stopMarkers: Map<string, InstanceType<MapLibre['Marker']>>;
  ports: LngLat[];
  routeKey: string;
  geometryKey: string;
};

function placeLive(handle: LiveGlobe, fixes: LiveFix[]) {
  const next = new Set(fixes.map(fix => fix.id));
  for (const [id, marker] of handle.markers) {
    if (!next.has(id)) {
      marker.remove();
      handle.markers.delete(id);
    }
  }
  for (const fix of fixes) {
    const marker = handle.markers.get(fix.id);
    if (marker) {
      marker.setLngLat([fix.position.longitude, fix.position.latitude]);
      updateLivePin(marker, fix);
    } else {
      handle.markers.set(fix.id, new handle.maplibre.Marker({ element: livePin(fix), anchor: 'center' })
        .setLngLat([fix.position.longitude, fix.position.latitude])
        .addTo(handle.map));
    }
  }
}

function updateLivePin(marker: InstanceType<MapLibre['Marker']>, fix: LiveFix) {
  const root = marker.getElement();
  root.className = `globe-pin ${fix.displayMode === 'live' ? 'live' : 'position-marker'} ${fix.displayMode} ${fix.id}`;
  root.title = markerTitle(fix);
}

function routeIdentity(stops: Stop[]) {
  return JSON.stringify([stops[0]?.at ?? null, stops[2]?.at ?? null, stops[4]?.at ?? null]);
}

function routePresentation(stops: Stop[]) {
  return JSON.stringify(stops.map(stop => [stop.id, stop.kicker, stop.name, stop.at]));
}

function updateStops(handle: LiveGlobe, stops: Stop[], reframe: boolean, routeIdentityKey?: string) {
  const plotted = stops.filter((stop): stop is Stop & { at: LngLat } => stop.at !== null);
  const next = new Set(plotted.map(stop => stop.id));
  for (const [id, marker] of handle.stopMarkers) {
    if (!next.has(id)) {
      marker.remove();
      handle.stopMarkers.delete(id);
    }
  }
  for (const stop of plotted) {
    const marker = handle.stopMarkers.get(stop.id);
    if (marker) {
      marker.setLngLat(stop.at);
      updateStopPin(marker, stop);
    } else {
      handle.stopMarkers.set(stop.id, new handle.maplibre.Marker({ element: pin(stop), anchor: 'center' })
        .setLngLat(stop.at)
        .addTo(handle.map));
    }
  }
  const line = track(stops);
  const planned = handle.map.getSource('planned-route') as { setData: (data: unknown) => void } | undefined;
  const data = line.length > 1
    ? { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: line } }
    : emptyTrack();
  if (planned) planned.setData(data);
  else if (line.length > 1) {
    handle.map.addSource('planned-route', { type: 'geojson', data });
    handle.map.addLayer({
      id: 'planned-route', type: 'line', source: 'planned-route',
      paint: { 'line-color': '#214e40', 'line-width': 2.4, 'line-dasharray': [1.2, 1.2] },
    });
  }
  const previousRoute = handle.routeKey;
  const nextGeometryKey = routeIdentity(stops);
  const nextRoute = routeIdentityKey ?? nextGeometryKey;
  const routeChanged = previousRoute !== nextRoute || handle.geometryKey !== nextGeometryKey;
  handle.routeKey = nextRoute;
  handle.geometryKey = nextGeometryKey;
  handle.ports = plotted.filter(stop => ['departure', 'transshipment', 'destination'].includes(stop.id)).map(stop => stop.at);
  if (reframe && routeChanged) fitRoute(handle);
}

function updateStopPin(marker: InstanceType<MapLibre['Marker']>, stop: Stop) {
  const root = marker.getElement();
  root.className = `globe-pin ${stop.id}`;
  const kicker = root.querySelector('.overline');
  const name = root.querySelector('strong');
  if (kicker) kicker.textContent = stop.kicker;
  if (name) {
    name.textContent = stop.name;
    name.className = stop.name === 'Not recorded' || stop.name === PENDING_ASSIGNMENT ? 'is-missing' : '';
  }
}

function fitRoute(handle: LiveGlobe) {
  if (handle.ports.length > 1) {
    const bounds = new handle.maplibre.LngLatBounds(handle.ports[0], handle.ports[0]);
    for (const point of handle.ports) bounds.extend(point);
    handle.map.fitBounds(bounds, { padding: 72, maxZoom: 3.4, duration: 0 });
  } else if (handle.ports.length === 1) {
    handle.map.jumpTo({ center: handle.ports[0], zoom: 3.4 });
  }
}

const TRACK_LINE = '#2a6f97';
const TRACK_SLOW = '#c2410c';
const NO_SELECTION = -1;

function emptyTrack() {
  return { type: 'FeatureCollection' as const, features: [] };
}

function addTrackLayers(globe: LiveGlobe['map']) {
  globe.addSource('vessel-track', { type: 'geojson', data: emptyTrack() });
  globe.addSource('vessel-track-points', { type: 'geojson', data: emptyTrack() });
  globe.addLayer({
    id: 'vessel-track',
    type: 'line',
    source: 'vessel-track',
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: {
      'line-color': ['case', ['get', 'slow'], TRACK_SLOW, TRACK_LINE],
      'line-width': ['case', ['get', 'slow'], 5, 3],
    },
  });
  globe.addLayer({
    id: 'vessel-track-points',
    type: 'circle',
    source: 'vessel-track-points',
    paint: {
      'circle-radius': 4,
      'circle-color': ['case', ['get', 'slow'], TRACK_SLOW, TRACK_LINE],
      'circle-stroke-color': '#ffffff',
      'circle-stroke-width': 1.5,
    },
  });
  globe.addLayer({
    id: 'vessel-track-selected',
    type: 'circle',
    source: 'vessel-track-points',
    filter: ['==', ['get', 'id'], NO_SELECTION],
    paint: { 'circle-radius': 8, 'circle-color': 'rgba(0,0,0,0)', 'circle-stroke-color': '#10231c', 'circle-stroke-width': 2.5 },
  });
}

function placeTrack(handle: LiveGlobe, history: VesselTrack | null | undefined) {
  const points = history?.points ?? [];
  const threshold = history?.slowdownThresholdKnots ?? 0;
  type GeoJsonSource = { setData: (data: unknown) => void };
  (handle.map.getSource('vessel-track') as GeoJsonSource | undefined)?.setData(trackSegments(points, threshold));
  (handle.map.getSource('vessel-track-points') as GeoJsonSource | undefined)?.setData(trackPoints(points, threshold));
}

function markSelected(handle: LiveGlobe, selectedId: number | null | undefined) {
  handle.map.setFilter('vessel-track-selected', ['==', ['get', 'id'], selectedId ?? NO_SELECTION]);
}

function livePin(fix: LiveFix) {
  const root = document.createElement('div');
  root.className = `globe-pin ${fix.displayMode === 'live' ? 'live' : 'position-marker'} ${fix.displayMode} ${fix.id}`;
  root.title = markerTitle(fix);
  const dot = document.createElement('span');
  dot.className = 'globe-dot';
  root.append(dot);
  return root;
}

function markerTitle(fix: LiveFix) {
  const timeLabel = fix.displayMode === 'recorded' ? 'Receipt time' : fix.displayMode === 'scenario' ? 'Scenario time' : 'Last updated';
  return `${fix.kicker} ${fix.name}. ${timeLabel} ${formatWhen(fix.position.ingestedAt)}`;
}

export function RouteMap({ shipment, tracking, history, selectedId, onSelectPoint, displayPositions, displayMode = 'live', contextKey, routeIdentityKey }: {
  shipment: Shipment;
  tracking?: ShipmentTracking | null;
  history?: VesselTrack | null;
  selectedId?: number | null;
  onSelectPoint?: (id: number) => void;
  displayPositions?: RouteDisplayPosition[];
  displayMode?: 'live' | 'scenario' | 'recorded';
  contextKey?: string;
  routeIdentityKey?: string;
}) {
  const stage = useRef<HTMLDivElement>(null);
  const historyRef = useRef(history);
  const selectedRef = useRef(selectedId);
  const onSelectRef = useRef(onSelectPoint);
  const stopsRef = useRef<Stop[]>([]);
  const routeIdentityRef = useRef(routeIdentityKey);
  const contextRef = useRef(contextKey ?? String(shipment.id));
  historyRef.current = history;
  selectedRef.current = selectedId;
  onSelectRef.current = onSelectPoint;
  const globeRef = useRef<LiveGlobe | null>(null);
  const fixesRef = useRef<LiveFix[]>([]);
  const mapReadyRef = useRef(false);
  const stops = stopsFor(shipment);
  stopsRef.current = stops;
  routeIdentityRef.current = routeIdentityKey;
  const fixes = displayPositions
    ? displayFixes(displayPositions, displayMode)
    : displayMode === 'recorded' ? [] : liveFixes(tracking);
  const notice = displayMode === 'recorded' ? null : trackingNotice(tracking);
  fixesRef.current = fixes;
  const [mapState, setMapState] = useState<'loading' | 'ready' | 'failed'>('loading');
  const unmapped = [stops[0], stops[2], stops[4]].filter(stop => stop.name !== 'Not recorded' && !stop.at).map(stop => stop.name);

  useEffect(() => {
    const node = stage.current;
    if (!node) return;
    let disposed = false;
    let created: { remove: () => void } | undefined;
    mapReadyRef.current = false;
    void import('maplibre-gl').then(maplibre => {
      if (disposed) return;
      const globe = new maplibre.Map({
        container: node,
        style: 'https://tiles.openfreemap.org/styles/liberty',
        center: stopsRef.current.find(stop => stop.at)?.at ?? [103.82, 1.26],
        zoom: 1.3,
        attributionControl: { compact: true },
        cooperativeGestures: true,
      });
      globe.addControl(new maplibre.NavigationControl({ showCompass: false }), 'top-right');
      created = globe;
      const handle: LiveGlobe = { map: globe, maplibre, markers: new Map(), stopMarkers: new Map(), ports: [], routeKey: '', geometryKey: '' };
      globeRef.current = handle;
      globe.on('error', () => {
        if (!disposed) setMapState('failed');
      });
      globe.on('load', () => {
        if (disposed) return;
        mapReadyRef.current = true;
        setMapState('ready');
        globe.setProjection({ type: 'globe' });
        updateStops(handle, stopsRef.current, true, routeIdentityRef.current);
        addTrackLayers(globe);
        globe.on('click', 'vessel-track-points', event => {
          const id = event.features?.[0]?.properties?.id;
          if (typeof id === 'number') onSelectRef.current?.(id);
        });
        globe.on('mouseenter', 'vessel-track-points', () => { globe.getCanvas().style.cursor = 'pointer'; });
        globe.on('mouseleave', 'vessel-track-points', () => { globe.getCanvas().style.cursor = ''; });
        placeTrack(handle, historyRef.current);
        markSelected(handle, selectedRef.current);
        placeLive(handle, fixesRef.current);
      });
    }).catch(() => {
      if (!disposed) setMapState('failed');
    });

    return () => {
      disposed = true;
      mapReadyRef.current = false;
      globeRef.current = null;
      created?.remove();
    };
  }, []);

  const routeKey = routeIdentityKey ?? routeIdentity(stops);
  const stopPresentation = routePresentation(stops);

  useEffect(() => {
    const handle = globeRef.current;
    if (!handle || !mapReadyRef.current) return;
    updateStops(handle, stops, true, routeIdentityRef.current);
  }, [routeKey, stopPresentation]);

  useEffect(() => {
    const nextContext = contextKey ?? String(shipment.id);
    if (contextRef.current === nextContext) return;
    contextRef.current = nextContext;
    const handle = globeRef.current;
    if (!handle) return;
    for (const marker of handle.markers.values()) marker.remove();
    handle.markers.clear();
    // A controlled-scenario revision can arrive before MapLibre has finished
    // loading its style (or after an offline style failure). The text and
    // scenario facts remain usable, but style-backed sources/layers cannot be
    // touched until the map is ready.
    if (!mapReadyRef.current || handle.map.isStyleLoaded() !== true) return;
    placeTrack(handle, null);
    markSelected(handle, null);
  }, [contextKey, shipment.id]);

  useEffect(() => {
    const handle = globeRef.current;
    if (!handle || !mapReadyRef.current) return;
    placeLive(handle, fixes);
  }, [tracking, displayMode, displayPositions]);

  useEffect(() => {
    const handle = globeRef.current;
    if (!handle || !mapReadyRef.current) return;
    placeTrack(handle, history);
  }, [history]);

  useEffect(() => {
    const handle = globeRef.current;
    if (!handle || !mapReadyRef.current) return;
    markSelected(handle, selectedId);
  }, [selectedId]);

  return (
    <figure className="route-chart" aria-label="Planned route">
      <div ref={stage} className="route-chart-stage" />
      <ol className="sr-only">
        {stops.map(stop => <li key={stop.id}>{stop.kicker} {stop.name}</li>)}
      </ol>
      {mapState === 'loading' && <p className="route-map-status" role="status">Loading map…</p>}
      {mapState === 'failed' && (
        <p className="route-map-fallback" role="status">
          The basemap is unavailable. AIS track availability is separate; coordinates, timestamps and shipment facts remain available below.
        </p>
      )}
      {fixes.length > 0 && (
        <ul className="route-live">
          {fixes.map(fix => (
            <li key={fix.id}>
              {fix.kicker} {fix.name} · {fix.position.latitude}, {fix.position.longitude}
              {fix.position.speedOverGroundKnots == null ? '' : ` · ${fix.position.speedOverGroundKnots} kn`}
              {' · '}{displayMode === 'recorded' ? 'Receipt time' : displayMode === 'scenario' ? 'Scenario time' : 'Last updated'} {formatWhen(fix.position.ingestedAt)}
            </li>
          ))}
        </ul>
      )}
      {notice && <p className="route-warning" role="status">{notice}</p>}
      {unmapped.length > 0 && <p className="route-unmapped">Not placed on the globe: {unmapped.join(', ')}</p>}
    </figure>
  );
}
