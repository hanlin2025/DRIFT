import { Link } from 'react-router-dom';
import { type Shipment } from '../shipment/api';
import { formatWhen, PENDING_ASSIGNMENT } from '../shipment/ShipmentForm';

const columns = ['Reference', 'Origin', 'Destination', 'Transshipment port', 'Mother vessel', 'Feeder vessel', 'Planned arrival'] as const;

export function ShipmentList({ shipments, basePath }: { shipments: Shipment[]; basePath: string }) {
  return (
    <div className="shipment-list">
      <table>
        <caption className="sr-only">Shipments</caption>
        <thead>
          <tr>{columns.map(column => <th key={column} scope="col">{column}</th>)}</tr>
        </thead>
        <tbody>
          {shipments.map(shipment => (
            <tr key={shipment.id}>
              <td><Link className="shipment-row-link" to={`${basePath}/shipments/${shipment.id}`}>{shipment.shipmentReference}</Link></td>
              <td>{shipment.origin}</td>
              <td>{shipment.destination}</td>
              <td>{shipment.transshipmentPort}</td>
              <td>{shipment.motherVessel}</td>
              <td className={shipment.feederVessel ? undefined : 'is-missing'}>{shipment.feederVessel || PENDING_ASSIGNMENT}</td>
              <td><time dateTime={shipment.plannedMotherArrivalAt}>{formatWhen(shipment.plannedMotherArrivalAt)}</time></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
