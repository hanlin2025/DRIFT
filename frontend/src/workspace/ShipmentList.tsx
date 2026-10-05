import { type Shipment } from '../shipment/api';

const columns = ['Reference', 'Origin', 'Destination', 'Transshipment port', 'Mother vessel', 'Feeder vessel'] as const;

export function ShipmentList({ shipments, selectedId, onSelect }: {
  shipments: Shipment[];
  selectedId?: number;
  onSelect?: (shipment: Shipment) => void;
}) {
  return (
    <div className="shipment-list">
      <table>
        <caption className="sr-only">Shipments</caption>
        <thead>
          <tr>{columns.map(column => <th key={column} scope="col">{column}</th>)}</tr>
        </thead>
        <tbody>
          {shipments.map(shipment => (
            <tr key={shipment.id} className={shipment.id === selectedId ? 'is-selected' : undefined}>
              <td>{onSelect
                ? <a className="shipment-row-link" href={`#shipment-${shipment.id}`} aria-current={shipment.id === selectedId ? 'true' : undefined} onClick={event => { event.preventDefault(); onSelect(shipment); }}>{shipment.shipmentReference}</a>
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
