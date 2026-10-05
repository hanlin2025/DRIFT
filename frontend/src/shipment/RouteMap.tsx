import { useEffect, useRef } from 'react';
import 'maplibre-gl/dist/maplibre-gl.css';
import type { Shipment } from './api';
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

export function RouteMap({ shipment }: { shipment: Shipment }) {
  const stage = useRef<HTMLDivElement>(null);
  const stops = stopsFor(shipment);
  const unmapped = [stops[0], stops[2], stops[4]].filter(stop => stop.name !== 'Not recorded' && !stop.at).map(stop => stop.name);

  useEffect(() => {
    const node = stage.current;
    if (!node) return;
    let disposed = false;
    let map: { remove: () => void } | undefined;
    const plotted = stops.filter((stop): stop is Stop & { at: LngLat } => stop.at !== null);
    const line = track(stops);

    void import('maplibre-gl').then(maplibre => {
      if (disposed) return;
      const globe = new maplibre.Map({
        container: node,
        style: 'https://tiles.openfreemap.org/styles/liberty',
        center: plotted[0]?.at ?? [103.82, 1.26],
        zoom: 1.3,
        attributionControl: { compact: true },
        cooperativeGestures: true,
      });
      map = globe;
      globe.on('load', () => {
        if (disposed) return;
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
        if (plotted.length > 1) {
          const bounds = new maplibre.LngLatBounds(plotted[0].at, plotted[0].at);
          for (const stop of plotted) bounds.extend(stop.at);
          globe.fitBounds(bounds, { padding: 72, maxZoom: 3.4, duration: 0 });
        }
      });
    }).catch(() => undefined);

    return () => {
      disposed = true;
      map?.remove();
    };
  }, [shipment]);

  return (
    <figure className="route-chart" aria-label="Planned route">
      <div ref={stage} className="route-chart-stage" />
      <ol className="sr-only">
        {stops.map(stop => <li key={stop.id}>{stop.kicker} {stop.name}</li>)}
      </ol>
      {unmapped.length > 0 && <p className="route-unmapped">Not placed on the globe: {unmapped.join(', ')}</p>}
    </figure>
  );
}
