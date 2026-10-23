import { canArchiveShipment, type ShipmentArchive } from './archiveAccess';
import { type Shipment } from './api';
import { RemoveShipment } from './RemoveShipment';
import { RestoreShipment } from './RestoreShipment';

export function ShipmentStatusAction({ shipment, archive }: {
  shipment: Shipment;
  archive: ShipmentArchive;
}) {
  if (!canArchiveShipment(archive.viewer, shipment.companyId)) return null;
  const shared = {
    token: archive.token,
    shipmentId: shipment.id,
    reference: shipment.shipmentReference,
    onSessionEnded: archive.onSessionEnded,
    onFailed: archive.onFailed,
  };
  switch (shipment.status) {
    case 'ARCHIVED':
      return <RestoreShipment {...shared} onRestored={archive.onRemoved} />;
    case 'ACTIVE':
    case null:
      return <RemoveShipment {...shared} onRemoved={archive.onRemoved} />;
    default: {
      const unexpected: never = shipment.status;
      return unexpected;
    }
  }
}
