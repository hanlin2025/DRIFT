package com.drift.backend.shipment;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.drift.backend.company.Company;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

/** Database predicates for shipment-list filtering. */
final class ShipmentSpecifications {

	private static final char LIKE_ESCAPE = '!';
	private static final ZoneId SINGAPORE_ZONE = ZoneId.of("Asia/Singapore");

	private ShipmentSpecifications() {
	}

	static Specification<Shipment> matching(Long companyId, ShipmentFilterCriteria criteria) {
		Specification<Shipment> specification = visibleToCompany(companyId);
		if (criteria.reference() != null) {
			specification = specification.and(referenceContains(criteria.reference()));
		}
		if (criteria.vessel() != null) {
			specification = specification.and(vesselContains(criteria.vessel()));
		}
		if (criteria.customer() != null) {
			specification = specification.and(customerContains(criteria.customer()));
		}
		if (criteria.arrivalDate() != null) {
			specification = specification.and(arrivalOnSingaporeDate(criteria.arrivalDate()));
		}
		return specification;
	}

	static Specification<Shipment> visibleToCompany(Long companyId) {
		return (root, query, builder) -> {
			Join<Shipment, Company> importer = root.join("importerCompany", JoinType.LEFT);
			return builder.or(
					builder.equal(root.get("company").get("id"), companyId),
					builder.equal(importer.get("id"), companyId));
		};
	}

	static Specification<Shipment> referenceContains(String reference) {
		return (root, query, builder) -> contains(builder, root.get("shipmentReference"), reference);
	}

	static Specification<Shipment> vesselContains(String vessel) {
		return (root, query, builder) -> builder.or(
				contains(builder, root.get("motherVessel"), vessel),
				contains(builder, root.get("feederVessel"), vessel));
	}

	static Specification<Shipment> customerContains(String customer) {
		return (root, query, builder) -> {
			Join<Shipment, Company> importer = root.join("importerCompany", JoinType.INNER);
			return builder.or(
					contains(builder, importer.get("code"), customer),
					contains(builder, importer.get("name"), customer));
		};
	}

	static Specification<Shipment> arrivalOnSingaporeDate(LocalDate date) {
		OffsetDateTime start = date.atStartOfDay(SINGAPORE_ZONE).toOffsetDateTime();
		OffsetDateTime end = date.plusDays(1).atStartOfDay(SINGAPORE_ZONE).toOffsetDateTime();
		return (root, query, builder) -> builder.and(
				builder.greaterThanOrEqualTo(root.get("plannedMotherArrivalAt"), start),
				builder.lessThan(root.get("plannedMotherArrivalAt"), end));
	}

	private static Predicate contains(CriteriaBuilder builder, jakarta.persistence.criteria.Expression<String> field,
			String value) {
		return builder.like(builder.lower(field), containsPattern(value), LIKE_ESCAPE);
	}

	private static String containsPattern(String value) {
		String escaped = value.toLowerCase(Locale.ROOT)
				.replace("!", "!!")
				.replace("%", "!%")
				.replace("_", "!_");
		return "%" + escaped + "%";
	}
}
