package com.drift.backend.maritimechange;

import java.util.List;

interface VisibleShipments {

	List<ShipmentMatchFacts> forCompany(long companyId);

	/** Active mother-vessel candidates for internal source-change processing, which has no viewer company. */
	default List<ShipmentMatchFacts> activeForMotherImo(String imo) { return List.of(); }
}
