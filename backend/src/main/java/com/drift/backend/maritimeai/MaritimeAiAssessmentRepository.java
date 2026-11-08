package com.drift.backend.maritimeai;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface MaritimeAiAssessmentRepository extends JpaRepository<MaritimeAiAssessment, Long> {
	List<MaritimeAiAssessment> findByShipment_IdOrderByCreatedAtDesc(Long shipmentId);
	Optional<MaritimeAiAssessment> findByAssessmentId(String assessmentId);
	Optional<MaritimeAiAssessment> findByAssessmentIdAndShipment_Id(String assessmentId, Long shipmentId);
}
