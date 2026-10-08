package com.drift.backend.shipment;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;
import com.drift.backend.shipment.ShipmentCsv.Row;
import com.drift.backend.shipment.ShipmentCsv.RowFailure;
import com.drift.backend.shipment.exception.DuplicateShipmentReferenceException;
import com.drift.backend.shipment.exception.InvalidImporterOrganisationException;
import com.drift.backend.shipment.exception.InvalidItineraryException;
import com.drift.backend.shipment.exception.InvalidShipmentFileException;
import com.drift.backend.shipment.exception.ShipmentImportForbiddenException;
import com.drift.backend.shipment.exception.ShipmentImportNotFoundException;

@Service
public class ShipmentImportService {

	private static final String REFERENCE = "Tracking/BL No.";
	private static final String ORIGIN = "Origin Port";
	private static final String DESTINATION = "Destination Port";
	private static final String TRANSSHIPMENT = "Transshipment Port";
	private static final String MOTHER = "Mother Vessel";
	private static final String ARRIVAL = "Planned Mother Arrival";
	private static final String FEEDER = "Feeder Vessel";
	private static final String DEPARTURE = "Planned Feeder Departure";
	private static final String IMPORTER = "Importer Organisation";
	private static final String OFFSET_EXAMPLE = "2026-10-15T08:00:00+08:00";

	private final ShipmentService shipments;
	private final ShipmentImportRepository imports;
	private final UserAccountRepository users;
	private final CompanyRepository companies;
	private final ShipmentImportService self;

	public ShipmentImportService(ShipmentService shipments, ShipmentImportRepository imports,
			UserAccountRepository users, CompanyRepository companies, @Lazy ShipmentImportService self) {
		this.shipments = shipments;
		this.imports = imports;
		this.users = users;
		this.companies = companies;
		this.self = self;
	}

	public ShipmentImportResponse importCsv(AuthenticatedUser principal, byte[] bytes, String filename, String contentType) {
		if (bytes == null || bytes.length == 0 || bytes.length > ShipmentCsv.MAX_BYTES || !looksLikeCsv(filename, contentType)) {
			throw new InvalidShipmentFileException();
		}
		UserAccount account = self.forwarder(principal);
		Company company = account.getCompany();
		String text = new String(bytes, StandardCharsets.UTF_8);
		if (text.indexOf('\0') >= 0) {
			throw new InvalidShipmentFileException();
		}
		List<Row> rows = ShipmentCsv.parse(text);
		int imported = 0;
		List<RowFailure> failures = new ArrayList<>();
		Set<String> accepted = new HashSet<>();
		for (Row row : rows) {
			String referenceKey = row.get(REFERENCE).toLowerCase(Locale.ROOT);
			Prepared prepared = prepare(row, company);
			if (prepared.problem() != null) {
				failures.add(new RowFailure(row.number(), prepared.problem()));
				continue;
			}
			if (accepted.contains(referenceKey)) {
				failures.add(new RowFailure(row.number(), ShipmentCsv.DUPLICATE_REFERENCE));
				continue;
			}
			try {
				shipments.create(principal, prepared.request());
				accepted.add(referenceKey);
				imported++;
			} catch (DuplicateShipmentReferenceException ex) {
				failures.add(new RowFailure(row.number(), ShipmentCsv.DUPLICATE_REFERENCE));
			} catch (InvalidImporterOrganisationException | InvalidItineraryException ex) {
				failures.add(new RowFailure(row.number(), ex.getMessage()));
			} catch (RuntimeException ex) {
				failures.add(new RowFailure(row.number(), "This row could not be imported."));
			}
		}
		return self.record(account, imported, failures);
	}

	@Transactional(readOnly = true)
	public byte[] template(AuthenticatedUser principal) {
		forwarder(principal);
		return ShipmentCsv.template().getBytes(StandardCharsets.UTF_8);
	}

	@Transactional(readOnly = true)
	public byte[] errorReport(AuthenticatedUser principal, Long importId) {
		UserAccount account = forwarder(principal);
		ShipmentImport shipmentImport = imports.findByIdAndCompanyId(importId, account.getCompany().getId())
				.orElseThrow(ShipmentImportNotFoundException::new);
		List<RowFailure> failures = shipmentImport.getErrors().stream()
				.map(error -> new RowFailure(error.getRowNumber(), error.getReason()))
				.toList();
		return ShipmentCsv.errorReport(failures).getBytes(StandardCharsets.UTF_8);
	}

	@Transactional
	public ShipmentImportResponse record(UserAccount account, int imported, List<RowFailure> failures) {
		ShipmentImport shipmentImport = new ShipmentImport(account.getCompany(), account, imported, failures.size());
		for (RowFailure failure : failures) {
			String reason = failure.reason().length() > 500 ? failure.reason().substring(0, 500) : failure.reason();
			shipmentImport.addError(failure.rowNumber(), reason);
		}
		return ShipmentImportResponse.from(imports.saveAndFlush(shipmentImport));
	}

	private Prepared prepare(Row row, Company owner) {
		Field reference = text(row, REFERENCE, "Shipment reference", 100);
		if (reference.problem() != null) {
			return Prepared.problem(reference.problem());
		}
		Field origin = text(row, ORIGIN, "Origin", 200);
		if (origin.problem() != null) {
			return Prepared.problem(origin.problem());
		}
		Field destination = text(row, DESTINATION, "Destination", 200);
		if (destination.problem() != null) {
			return Prepared.problem(destination.problem());
		}
		Field transshipment = text(row, TRANSSHIPMENT, "Transshipment port", 200);
		if (transshipment.problem() != null) {
			return Prepared.problem(transshipment.problem());
		}
		Field mother = text(row, MOTHER, "Mother vessel", 200);
		if (mother.problem() != null) {
			return Prepared.problem(mother.problem());
		}
		Field feeder = text(row, FEEDER, "Feeder vessel", 200);
		if (feeder.problem() != null) {
			return Prepared.problem(feeder.problem());
		}
		OffsetDateTime arrival = when(row.get(ARRIVAL));
		if (row.get(ARRIVAL).isBlank()) {
			return Prepared.problem("Planned mother-vessel arrival is required");
		}
		if (arrival == null) {
			return Prepared.problem("Planned mother-vessel arrival must be an offset date and time, such as " + OFFSET_EXAMPLE);
		}
		OffsetDateTime departure = when(row.get(DEPARTURE));
		if (row.get(DEPARTURE).isBlank()) {
			return Prepared.problem("Planned feeder-vessel departure is required");
		}
		if (departure == null) {
			return Prepared.problem("Planned feeder-vessel departure must be an offset date and time, such as " + OFFSET_EXAMPLE);
		}
		if (!departure.isAfter(arrival)) {
			return Prepared.problem(InvalidItineraryException.MESSAGE);
		}
		String importerName = row.get(IMPORTER);
		Long importerCompanyId = null;
		if (!importerName.isBlank()) {
			importerCompanyId = importerId(owner, importerName);
			if (importerCompanyId == null) {
				return Prepared.problem(InvalidImporterOrganisationException.MESSAGE);
			}
		}
		return Prepared.ready(new CreateShipmentRequest(reference.value(), origin.value(), destination.value(),
				transshipment.value(), mother.value(), arrival, feeder.value(), departure, importerCompanyId));
	}

	private static Field text(Row row, String header, String label, int limit) {
		String value = row.get(header);
		if (value.isBlank()) {
			return new Field(null, label + " is required");
		}
		if (value.length() > limit) {
			return new Field(null, label + " must be at most " + limit + " characters");
		}
		return new Field(value, null);
	}

	private static OffsetDateTime when(String value) {
		if (value.isBlank()) {
			return null;
		}
		try {
			return OffsetDateTime.parse(value);
		} catch (DateTimeParseException ex) {
			return null;
		}
	}

	private Long importerId(Company owner, String name) {
		String expected = name.toLowerCase(Locale.ROOT);
		List<Company> matches = companies.findByActiveTrueAndIdNotOrderByNameAsc(owner.getId()).stream()
				.filter(company -> company.getName().toLowerCase(Locale.ROOT).equals(expected))
				.toList();
		if (matches.size() != 1) {
			return null;
		}
		return matches.get(0).getId();
	}

	@Transactional(readOnly = true)
	public UserAccount forwarder(AuthenticatedUser principal) {
		UserAccount account = users.findById(principal.id()).orElseThrow(SessionEndedException::new);
		if (account.getRole() != Role.FREIGHT_FORWARDER) {
			throw new ShipmentImportForbiddenException();
		}
		Company company = account.getCompany();
		if (company == null || !company.isActive()) {
			throw new ShipmentImportForbiddenException();
		}
		company.getId();
		return account;
	}

	private static boolean looksLikeCsv(String filename, String contentType) {
		String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
		if (type.contains("json") || type.contains("html") || type.contains("xml") || type.startsWith("image/")
				|| type.contains("pdf")) {
			return false;
		}
		boolean namedCsv = filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".csv");
		boolean typedCsv = type.isBlank() || type.contains("csv") || type.contains("excel") || type.contains("text/plain")
				|| type.contains("octet-stream");
		return namedCsv || typedCsv;
	}

	private record Field(String value, String problem) {
	}

	private record Prepared(CreateShipmentRequest request, String problem) {
		static Prepared ready(CreateShipmentRequest request) {
			return new Prepared(request, null);
		}

		static Prepared problem(String problem) {
			return new Prepared(null, problem);
		}
	}
}
