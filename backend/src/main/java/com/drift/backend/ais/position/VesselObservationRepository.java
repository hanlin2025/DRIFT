package com.drift.backend.ais.position;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VesselObservationRepository extends JpaRepository<VesselObservation, Long> {

	@Query(value = """
			WITH latest AS (
				SELECT DISTINCT ON (mmsi) *
				FROM vessel_observations
				ORDER BY mmsi, ingested_at DESC, id DESC
			),
			current_name AS (
				SELECT DISTINCT ON (mmsi)
					mmsi,
					btrim(regexp_replace(upper(vessel_name), '[[:space:]]+', ' ', 'g')) AS normalized_name
				FROM vessel_observations
				WHERE btrim(regexp_replace(upper(vessel_name), '[[:space:]]+', ' ', 'g')) <> ''
				ORDER BY mmsi, ingested_at DESC, id DESC
			)
			SELECT latest.*
			FROM latest
			JOIN current_name ON current_name.mmsi = latest.mmsi
			WHERE current_name.normalized_name = :normalizedName
			ORDER BY latest.ingested_at DESC, latest.id DESC
			LIMIT 1
			""", nativeQuery = true)
	Optional<VesselObservation> findLatestByNormalizedVesselName(@Param("normalizedName") String normalizedName);

	/** Latest observation id for each normalized vessel name, from one query, matching {@link #findLatestByNormalizedVesselName}. */
	@Query(value = """
			WITH latest AS (
				SELECT DISTINCT ON (mmsi) id, mmsi, ingested_at
				FROM vessel_observations
				ORDER BY mmsi, ingested_at DESC, id DESC
			),
			current_name AS (
				SELECT DISTINCT ON (mmsi)
					mmsi,
					btrim(regexp_replace(upper(vessel_name), '[[:space:]]+', ' ', 'g')) AS normalized_name
				FROM vessel_observations
				WHERE btrim(regexp_replace(upper(vessel_name), '[[:space:]]+', ' ', 'g')) <> ''
				ORDER BY mmsi, ingested_at DESC, id DESC
			)
			SELECT DISTINCT ON (current_name.normalized_name)
				current_name.normalized_name AS normalizedName, latest.id AS observationId
			FROM latest
			JOIN current_name ON current_name.mmsi = latest.mmsi
			WHERE current_name.normalized_name IN (:normalizedNames)
			ORDER BY current_name.normalized_name, latest.ingested_at DESC, latest.id DESC
			""", nativeQuery = true)
	List<LatestObservationByName> findLatestIdsByNormalizedVesselNames(
			@Param("normalizedNames") Collection<String> normalizedNames);

	interface LatestObservationByName {
		String getNormalizedName();

		Long getObservationId();
	}

	@Query(value = """
			SELECT *
			FROM vessel_observations
			WHERE mmsi = :mmsi
				AND ingested_at > :from
				AND ingested_at <= :to
				AND position_valid IS NOT FALSE
			ORDER BY ingested_at DESC, id DESC
			LIMIT :limit
			""", nativeQuery = true)
	List<VesselObservation> findNewestValidInWindow(@Param("mmsi") String mmsi, @Param("from") Instant from,
			@Param("to") Instant to, @Param("limit") int limit);

	Optional<VesselObservation> findFirstBySourceOrderByIngestedAtDescIdDesc(AisPositionSource source);
}
