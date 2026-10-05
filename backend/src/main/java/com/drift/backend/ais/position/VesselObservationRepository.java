package com.drift.backend.ais.position;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VesselObservationRepository extends JpaRepository<VesselObservation, Long> {

	Optional<VesselObservation> findFirstByMmsiOrderByIngestedAtDescIdDesc(String mmsi);

	@Query(value = """
			SELECT *
			FROM vessel_observations
			WHERE UPPER(BTRIM(REGEXP_REPLACE(vessel_name, '\\s+', ' ', 'g'))) = :vesselName
			ORDER BY ingested_at DESC, id DESC
			LIMIT 1
			""", nativeQuery = true)
	Optional<VesselObservation> findLatestByVesselName(@Param("vesselName") String vesselName);
}
