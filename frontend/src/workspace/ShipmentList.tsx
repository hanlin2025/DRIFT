import { Link } from 'react-router-dom';
import { canArchiveShipment, type ShipmentArchive } from '../shipment/archiveAccess';
import { type Shipment } from '../shipment/api';
import { ShipmentStatusAction } from '../shipment/ShipmentStatusAction';
import { formatWhen, PENDING_ASSIGNMENT } from '../shipment/ShipmentForm';

const columns = ['Reference', 'Origin', 'Destination', 'Transshipment port', 'Mother vessel', 'Feeder vessel', 'Planned arrival'] as const;

export function ShipmentList({ shipments, basePath, archive, archived = false }: {
  shipments: Shipment[];
  basePath: string;
  archive?: ShipmentArchive;
  archived?: boolean;
}) {
  const showActions = Boolean(archive && shipments.some(shipment => canArchiveShipment(archive.viewer, shipment.companyId)));
  return (
    <div className="shipment-list">
      <table>
        <caption className="sr-only">Shipments</caption>
        <thead>
          <tr>
            {columns.map(column => <th key={column} scope="col">{column}</th>)}
            {archived && <th scope="col">Archived by</th>}
            {showActions && <th scope="col"><span className="sr-only">Actions</span></th>}
          </tr>
        </thead>
        <tbody>
          {shipments.map(shipment => {
            const canChangeStatus = Boolean(archive && canArchiveShipment(archive.viewer, shipment.companyId));
            return (
              <tr key={shipment.id}>
                <td><Link className="shipment-row-link" to={`${basePath}/shipments/${shipment.id}`}>{shipment.shipmentReference}</Link></td>
                <td>{shipment.origin}</td>
                <td>{shipment.destination}</td>
                <td>{shipment.transshipmentPort}</td>
                <td>{shipment.motherVessel}</td>
                <td className={shipment.feederVessel ? undefined : 'is-missing'}>{shipment.feederVessel || PENDING_ASSIGNMENT}</td>
                <td><time dateTime={shipment.plannedMotherArrivalAt}>{formatWhen(shipment.plannedMotherArrivalAt)}</time></td>
                {archived && <ArchivedBy shipment={shipment} />}
                {showActions && <td className="shipment-actions">{canChangeStatus && archive
                  ? <ShipmentStatusAction shipment={shipment} archive={archive} />
                  : null}</td>}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function ArchivedBy({ shipment }: { shipment: Shipment }) {
  if (!shipment.updatedByName || !shipment.updatedAt) {
    return <td className="is-missing">Unknown</td>;
  }
  return <td>{shipment.updatedByName} on <time dateTime={shipment.updatedAt}>{formatWhen(shipment.updatedAt)}</time></td>;
}
