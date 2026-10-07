import { useEffect, useRef } from 'react';
import 'maplibre-gl/dist/maplibre-gl.css';
import type { Shipment, ShipmentTracking, VesselPosition } from './api';
import { formatWhen } from './ShipmentForm';
import { along, arc, locate, type LngLat } from './places';

type Stop = {
  id: string;
  kicker: string;
  name: string;
  at: LngLat | null;
};

function shown(value: string) {
  const text = value.trim();
  return text || 'Not recorded';
}

function stopsFor(shipment: Shipment): Stop[] {
  const origin = locate(shipment.origin);
  const hub = locate(shipment.transshipmentPort);
  const destination = locate(shipment.destination);
  return [
    { id: 'departure', kicker: 'DEPARTURE', name: shown(shipment.origin), at: origin },
    { id: 'mother', kicker: 'MOTHER', name: shown(shipment.motherVessel), at: origin && hub ? along(origin, hub, 0.58) : null },
    { id: 'transshipment', kicker: 'TRANSSHIPMENT', name: shown(shipment.transshipmentPort), at: hub },
    { id: 'feeder', kicker: 'FEEDER', name: shown(shipment.feederVessel), at: hub && destination ? along(hub, destination, 0.42) : null },
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

type LiveFix = {
  id: 'mother' | 'feeder';
  kicker: string;
  name: string;
  position: VesselPosition;
};

function liveFixes(tracking: ShipmentTracking | null | undefined): LiveFix[] {
  if (!tracking) return [];
  const fixes: LiveFix[] = [];
  if (tracking.motherVessel) {
    fixes.push({
      id: 'mother',
      kicker: 'MOTHER LIVE',
      name: tracking.motherVessel.vesselName?.trim() || tracking.motherVesselName,
      position: tracking.motherVessel,
    });
  }
  if (tracking.feederVessel) {
    fixes.push({
      id: 'feeder',
      kicker: 'FEEDER LIVE',
      name: tracking.feederVessel.vesselName?.trim() || tracking.feederVesselName,
      position: tracking.feederVessel,
    });
  }
  return fixes;
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
  if (stop.name === 'Not recorded') name.className = 'is-missing';
  copy.append(kicker, name);
  root.append(dot, copy);
  return root;
}

type MapLibre = typeof import('maplibre-gl');

type LiveGlobe = {
  map: InstanceType<MapLibre['Map']>;
  maplibre: MapLibre;
  markers: InstanceType<MapLibre['Marker']>[];
  ports: LngLat[];
  framed: boolean;
};

function placeLive(handle: LiveGlobe, fixes: LiveFix[]) {
  for (const marker of handle.markers) marker.remove();
  handle.markers = fixes.map(fix => new handle.maplibre.Marker({ element: livePin(fix), anchor: 'center' })
    .setLngLat([fix.position.longitude, fix.position.latitude])
    .addTo(handle.map));
  if (handle.framed) return;
  const framed = [...handle.ports, ...fixes.map(fix => [fix.position.longitude, fix.position.latitude] as LngLat)];
  if (framed.length > 1) {
    const bounds = new handle.maplibre.LngLatBounds(framed[0], framed[0]);
    for (const point of framed) bounds.extend(point);
    handle.map.fitBounds(bounds, { padding: 72, maxZoom: 3.4, duration: 0 });
    handle.framed = true;
  } else if (framed.length === 1) {
    handle.map.jumpTo({ center: framed[0], zoom: 3.4 });
    handle.framed = true;
  }
}

function livePin(fix: LiveFix) {
  const root = document.createElement('div');
  root.className = `globe-pin live ${fix.id}`;
  root.title = `${fix.kicker} ${fix.name}. Last updated ${formatWhen(fix.position.ingestedAt)}`;
  const dot = document.createElement('span');
  dot.className = 'globe-dot';
  root.append(dot);
  return root;
}

export function RouteMap({ shipment, tracking }: { shipment: Shipment; tracking?: ShipmentTracking | null }) {
  const stage = useRef<HTMLDivElement>(null);
  const globeRef = useRef<LiveGlobe | null>(null);
  const fixesRef = useRef<LiveFix[]>([]);
  const mapReadyRef = useRef(false);
  const stops = stopsFor(shipment);
  const fixes = liveFixes(tracking);
  fixesRef.current = fixes;
  const unmapped = [stops[0], stops[2], stops[4]].filter(stop => stop.name !== 'Not recorded' && !stop.at).map(stop => stop.name);

  useEffect(() => {
    const node = stage.current;
    if (!node) return;
    let disposed = false;
    let created: { remove: () => void } | undefined;
    mapReadyRef.current = false;
    const plotted = stops.filter((stop): stop is Stop & { at: LngLat } => stop.at !== null);
    const line = track(stops);
    const ports = plotted.map(stop => stop.at);

    void import('maplibre-gl').then(maplibre => {
      if (disposed) return;
      const globe = new maplibre.Map({
        container: node,
        style: 'https://tiles.openfreemap.org/styles/liberty',
        center: ports[0] ?? [103.82, 1.26],
        zoom: 1.3,
        attributionControl: { compact: true },
        cooperativeGestures: true,
      });
      globe.addControl(new maplibre.NavigationControl({ showCompass: false }), 'top-right');
      created = globe;
      const handle: LiveGlobe = { map: globe, maplibre, markers: [], ports, framed: false };
      globeRef.current = handle;
      globe.on('load', () => {
        if (disposed) return;
        mapReadyRef.current = true;
        globe.setProjection({ type: 'globe' });
        if (line.length > 1) {
          globe.addSource('planned-route', {
            type: 'geojson',
            data: { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: line } },
          });
          globe.addLayer({
            id: 'planned-route',
            type: 'line',
            source: 'planned-route',
            paint: { 'line-color': '#214e40', 'line-width': 2.4, 'line-dasharray': [1.2, 1.2] },
          });
        }
        for (const stop of plotted) {
          new maplibre.Marker({ element: pin(stop), anchor: 'center' }).setLngLat(stop.at).addTo(globe);
        }
        placeLive(handle, fixesRef.current);
      });
    }).catch(() => undefined);

    return () => {
      disposed = true;
      mapReadyRef.current = false;
      globeRef.current = null;
      created?.remove();
    };
  }, [shipment]);

  useEffect(() => {
    const handle = globeRef.current;
    if (!handle || !mapReadyRef.current) return;
    placeLive(handle, fixes);
  }, [tracking]);

  return (
    <figure className="route-chart" aria-label="Planned route">
      <div ref={stage} className="route-chart-stage" />
      <ol className="sr-only">
        {stops.map(stop => <li key={stop.id}>{stop.kicker} {stop.name}</li>)}
      </ol>
      {fixes.length > 0 && (
        <ul className="route-live">
          {fixes.map(fix => (
            <li key={fix.id}>
              {fix.kicker} {fix.name} · {fix.position.latitude}, {fix.position.longitude}
              {fix.position.speedOverGroundKnots == null ? '' : ` · ${fix.position.speedOverGroundKnots} kn`}
              {' · '}Last updated {formatWhen(fix.position.ingestedAt)}
            </li>
          ))}
        </ul>
      )}
      {unmapped.length > 0 && <p className="route-unmapped">Not placed on the globe: {unmapped.join(', ')}</p>}
    </figure>
  );
}
