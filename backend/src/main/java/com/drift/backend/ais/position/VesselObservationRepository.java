package com.drift.backend.ais.position;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VesselObservationRepository extends JpaRepository<VesselObservation, Long> {

	@Query(value = """
			SELECT *
			FROM vessel_observations
			WHERE vessel_name IS NOT NULL
				AND regexp_replace(upper(btrim(vessel_name)), '[[:space:]]+', ' ', 'g') = :normalizedName
			ORDER BY ingested_at DESC, id DESC
			LIMIT 1
			""", nativeQuery = true)
	Optional<VesselObservation> findLatestByNormalizedVesselName(@Param("normalizedName") String normalizedName);
}
