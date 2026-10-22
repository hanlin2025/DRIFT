import { Link } from 'react-router-dom';
import { canArchiveShipment, type ShipmentArchive } from '../shipment/archiveAccess';
import { type Shipment } from '../shipment/api';
import { RemoveShipment } from '../shipment/RemoveShipment';
import { PriorityScore, RiskBadge } from '../shipment/RiskBadge';
import { formatWhen, PENDING_ASSIGNMENT } from '../shipment/ShipmentForm';

const columns = ['Reference', 'Risk', 'Importer', 'Origin', 'Destination', 'Transshipment port', 'Mother vessel', 'Feeder vessel', 'Planned arrival'] as const;

const NOT_LINKED = 'Not linked';

export function ShipmentList({ shipments, basePath, archive }: {
  shipments: Shipment[];
  basePath: string;
  archive?: ShipmentArchive;
}) {
  const showActions = Boolean(archive && shipments.some(shipment => canArchiveShipment(archive.viewer, shipment.companyId)));
  return (
    <div className="shipment-list">
      <table>
        <caption className="sr-only">Shipments</caption>
        <thead>
          <tr>
            {columns.map(column => <th key={column} scope="col">{column}</th>)}
            {showActions && <th scope="col"><span className="sr-only">Actions</span></th>}
          </tr>
        </thead>
        <tbody>
          {shipments.map(shipment => {
            const removable = Boolean(archive && canArchiveShipment(archive.viewer, shipment.companyId));
            return (
              <tr key={shipment.id}>
                <td><Link className="shipment-row-link" to={`${basePath}/shipments/${shipment.id}`}>{shipment.shipmentReference}</Link></td>
                <td className="shipment-risk" title={shipment.risk?.explanation}><RiskBadge risk={shipment.risk} /><PriorityScore priority={shipment.priority} reference={shipment.shipmentReference} /></td>
                <td className={shipment.importer ? undefined : 'is-missing'}>{shipment.importer ? shipment.importer.name : NOT_LINKED}</td>
                <td>{shipment.origin}</td>
                <td>{shipment.destination}</td>
                <td>{shipment.transshipmentPort}</td>
                <td>{shipment.motherVessel}</td>
                <td className={shipment.feederVessel ? undefined : 'is-missing'}>{shipment.feederVessel || PENDING_ASSIGNMENT}</td>
                <td><time dateTime={shipment.plannedMotherArrivalAt}>{formatWhen(shipment.plannedMotherArrivalAt)}</time></td>
                {showActions && <td className="shipment-actions">{removable && archive
                  ? <RemoveShipment token={archive.token} shipmentId={shipment.id} reference={shipment.shipmentReference} onSessionEnded={archive.onSessionEnded} onRemoved={archive.onRemoved} onFailed={archive.onFailed} />
                  : null}</td>}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
