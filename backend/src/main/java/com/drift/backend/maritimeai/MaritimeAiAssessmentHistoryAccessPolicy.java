package com.drift.backend.maritimeai;

import java.util.Objects;

import org.springframework.stereotype.Component;

import com.drift.backend.shipment.ShipmentViewAuthorization;

/** Authorizes every part of a historical assessment as one indivisible record. */
@Component
class MaritimeAiAssessmentHistoryAccessPolicy {
	boolean canRead(MaritimeAiAssessment assessment, ShipmentViewAuthorization shipment,
			MaritimeAiAssessmentHistoryScope historicalScope) {
		if (!Objects.equals(assessment.getOwningCompanyId(), shipment.managingCompanyId())) {
			return false;
		}
		if (Objects.equals(shipment.viewerCompanyId(), shipment.managingCompanyId())) {
			return true;
		}
		return Objects.equals(shipment.viewerCompanyId(), shipment.importerCompanyId())
				&& historicalScope != null && historicalScope.identifiesImporter(shipment.viewerCompanyId());
	}
}
