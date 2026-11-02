package com.drift.backend.maritimechange;

import java.util.List;

interface VisibleShipments {

	List<ShipmentMatchFacts> forCompany(long companyId);
}
