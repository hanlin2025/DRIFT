package com.drift.backend.maritimeai;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface MaritimeAiAssessmentRepository extends JpaRepository<MaritimeAiAssessment, Long> {
	List<MaritimeAiAssessment> findByShipmentIdOrderByCreatedAtDesc(Long shipmentId);
}
