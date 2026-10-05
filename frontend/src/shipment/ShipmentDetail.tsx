import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../signup/api';
import { getShipment, type Shipment, type VesselPosition } from './api';
import { RouteMap } from './RouteMap';
import { formatWhen } from './ShipmentForm';

const UNREACHABLE = 'We could not reach DRIFT. Check your connection and try again.';

export function ShipmentDetail({ token, shipmentId, basePath, onSessionEnded }: {
  token: string;
  shipmentId: string;
  basePath: string;
  onSessionEnded: () => void;
}) {
  const [shipment, setShipment] = useState<Shipment | null>(null);
  const [problem, setProblem] = useState<{ message: string; retry: boolean } | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let active = true;
    setShipment(null);
    setProblem(null);
    getShipment(token, shipmentId).then(found => {
      if (active) setShipment(found);
    }).catch((reason: unknown) => {
      if (!active) return;
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      const missing = reason instanceof ApiError && reason.status === 404;
      setProblem({
        message: missing ? 'This shipment is not available.' : reason instanceof Error ? reason.message : UNREACHABLE,
        retry: !missing,
      });
    });
    return () => { active = false; };
  }, [token, shipmentId, attempt, onSessionEnded]);

  return (
    <>
      <Link className="back-link" to={basePath}><span aria-hidden="true">&#8592;</span> Back</Link>
      {problem
        ? <div className="error-notice" role="alert">{problem.message}{problem.retry && <><br /><button type="button" className="retry-button" onClick={() => setAttempt(value => value + 1)}>Try again</button></>}</div>
        : shipment === null
          ? <div className="notice">Loading shipment...</div>
          : <ShipmentRecord shipment={shipment} />}
    </>
  );
}

function ShipmentRecord({ shipment }: { shipment: Shipment }) {
  return (
    <>
      <p className="eyebrow">SHIPMENT</p>
      <h2>{shipment.shipmentReference}</h2>
      <p className="intro">{shipment.origin} to {shipment.destination}</p>
      <article className="shipment-record" aria-label={`Shipment ${shipment.shipmentReference}`}>
        <dl>
          <div><dt>Origin</dt><dd>{shipment.origin}</dd></div>
          <div><dt>Transshipment port</dt><dd>{shipment.transshipmentPort}</dd></div>
          <div><dt>Destination</dt><dd>{shipment.destination}</dd></div>
          <div><dt>Mother vessel</dt><dd>{shipment.motherVessel}</dd></div>
          <div><dt>Mother vessel position</dt><dd>{formatLive(shipment.motherVesselPosition)}</dd></div>
          <div><dt>Planned arrival</dt><dd><time dateTime={shipment.plannedMotherArrivalAt}>{formatWhen(shipment.plannedMotherArrivalAt)}</time></dd></div>
          <div><dt>Feeder vessel</dt><dd>{shipment.feederVessel}</dd></div>
          <div><dt>Feeder vessel position</dt><dd>{formatLive(shipment.feederVesselPosition)}</dd></div>
          <div><dt>Planned departure</dt><dd><time dateTime={shipment.plannedFeederDepartureAt}>{formatWhen(shipment.plannedFeederDepartureAt)}</time></dd></div>
          <div>
            <dt>Connection window</dt>
            <dd>{shipment.connectionWindow ? shipment.connectionWindow.duration : 'No planned connection window'}</dd>
          </div>
          <div><dt>Registered</dt><dd><time dateTime={shipment.createdAt}>{formatWhen(shipment.createdAt)}</time></dd></div>
        </dl>
      </article>
      <RouteMap shipment={shipment} />
    </>
  );
}

function formatLive(position: VesselPosition | null) {
  if (!position) return 'No live AIS position';
  const name = position.vesselName?.trim() || position.mmsi;
  const speed = position.speedOverGroundKnots == null ? '' : ` · ${position.speedOverGroundKnots} kn`;
  return `${name} · ${position.latitude}, ${position.longitude}${speed}`;
}
