import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../signup/api';
import { getShipment, listShipments, type Shipment } from './api';
import { formatWhen, ShipmentFacts } from './ShipmentForm';

const UNREACHABLE = 'We could not reach DRIFT. Check your connection and try again.';

export function ShipmentList({ token, basePath, refreshKey, canRegister, onSessionEnded }: {
  token: string;
  basePath: string;
  refreshKey: number;
  canRegister: boolean;
  onSessionEnded: () => void;
}) {
  const [shipments, setShipments] = useState<Shipment[] | null>(null);
  const [problem, setProblem] = useState('');
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let active = true;
    setProblem('');
    listShipments(token).then(found => {
      if (active) setShipments(found);
    }).catch((reason: unknown) => {
      if (!active) return;
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionEnded();
        return;
      }
      setProblem(reason instanceof Error ? reason.message : UNREACHABLE);
    });
    return () => { active = false; };
  }, [token, refreshKey, attempt, onSessionEnded]);

  return (
    <section className="shipment-panel" aria-labelledby="company-shipments" aria-busy={shipments === null && !problem}>
      <h3 id="company-shipments">Company shipments</h3>
      <p className="intro">Review each shipment's route, vessels and planned mother-vessel arrival. Select one to see its details.</p>
      {problem
        ? <div className="error-notice" role="alert">{problem}<br /><button type="button" className="retry-button" onClick={() => setAttempt(value => value + 1)}>Try again</button></div>
        : shipments === null
          ? <div className="notice">Loading shipments...</div>
          : shipments.length === 0
            ? <div className="empty-shipments">
                <strong>No shipments yet</strong>
                <p>{canRegister ? 'Register your first shipment to start following its connection.' : 'Shipments registered for your company will appear here.'}</p>
                {canRegister && <a className="secondary-button" href="#register-shipment">Register a shipment</a>}
              </div>
            : <ul className="shipment-list">
                {shipments.map(shipment => (
                  <li key={shipment.id}>
                    <Link className="shipment-row" to={`${basePath}/shipments/${shipment.id}`}>
                      <span className="shipment-row-heading">
                        <strong>{shipment.shipmentReference}</strong>
                        <span className="shipment-arrival">Arrives <time dateTime={shipment.plannedMotherArrivalAt}>{formatWhen(shipment.plannedMotherArrivalAt)}</time></span>
                      </span>
                      <span className="shipment-route">{shipment.origin} to {shipment.destination}</span>
                      <span className="shipment-vessels">
                        <span><span className="overline">MOTHER</span> {shipment.motherVessel}</span>
                        <span><span className="overline">FEEDER</span> {shipment.feederVessel}</span>
                      </span>
                    </Link>
                  </li>
                ))}
              </ul>}
    </section>
  );
}

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
      setProblem({
        message: reason instanceof Error ? reason.message : UNREACHABLE,
        retry: !(reason instanceof ApiError && reason.status === 404),
      });
    });
    return () => { active = false; };
  }, [token, shipmentId, attempt, onSessionEnded]);

  return (
    <>
      <Link className="back-link" to={basePath}><span aria-hidden="true">&#8592;</span> All shipments</Link>
      {problem
        ? <div className="error-notice" role="alert">{problem.message}{problem.retry && <><br /><button type="button" className="retry-button" onClick={() => setAttempt(value => value + 1)}>Try again</button></>}</div>
        : shipment === null
          ? <div className="notice">Loading shipment...</div>
          : <>
              <p className="eyebrow">SHIPMENT</p>
              <h2>{shipment.shipmentReference}</h2>
              <p className="intro">{shipment.origin} to {shipment.destination}</p>
              <article className="shipment-record" aria-label={`Shipment ${shipment.shipmentReference}`}>
                <ShipmentFacts shipment={shipment} />
                <dl>
                  <div><dt>Registered</dt><dd><time dateTime={shipment.createdAt}>{formatWhen(shipment.createdAt)}</time></dd></div>
                </dl>
              </article>
            </>}
    </>
  );
}
