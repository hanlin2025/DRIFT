package com.drift.backend.shipment.csvimport;

record ShipmentImportJobInput(Long jobId, Long managingCompanyId, byte[] csv) {
}
