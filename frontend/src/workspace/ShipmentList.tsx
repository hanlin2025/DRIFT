import { Link } from 'react-router-dom';
import { type Shipment } from '../shipment/api';

const columns = ['Reference', 'Origin', 'Destination', 'Transshipment port', 'Mother vessel', 'Feeder vessel'] as const;

export function ShipmentList({ shipments, basePath }: { shipments: Shipment[]; basePath?: string }) {
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
              <td>{basePath
                ? <Link className="shipment-row-link" to={`${basePath}/shipments/${shipment.id}`}>{shipment.shipmentReference}</Link>
                : shipment.shipmentReference}</td>
              <td>{shipment.origin}</td>
              <td>{shipment.destination}</td>
              <td>{shipment.transshipmentPort}</td>
              <td>{shipment.motherVessel}</td>
              <td>{shipment.feederVessel}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
