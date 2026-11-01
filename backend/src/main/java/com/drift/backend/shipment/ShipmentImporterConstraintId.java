package com.drift.backend.shipment;

import java.io.Serializable;
import java.util.Objects;

public final class ShipmentImporterConstraintId implements Serializable {

	private Long shipmentId;
	private Long importerCompanyId;

	public ShipmentImporterConstraintId() {
	}

	public ShipmentImporterConstraintId(Long shipmentId, Long importerCompanyId) {
		this.shipmentId = shipmentId;
		this.importerCompanyId = importerCompanyId;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof ShipmentImporterConstraintId that)) {
			return false;
		}
		return Objects.equals(shipmentId, that.shipmentId) && Objects.equals(importerCompanyId, that.importerCompanyId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(shipmentId, importerCompanyId);
	}
}
