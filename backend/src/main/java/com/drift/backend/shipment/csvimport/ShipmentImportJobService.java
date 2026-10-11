package com.drift.backend.shipment.csvimport;

import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.company.Company;
import com.drift.backend.shipment.exception.ShipmentAccessForbiddenException;

@Service
public class ShipmentImportJobService {

	private final ShipmentImportJobRepository jobs;
	private final ShipmentImportJobErrorRepository errors;
	private final UserAccountRepository users;
	private final BulkImportAuthorizer bulkImportAuthorizer;
	private final ApplicationEventPublisher events;

	public ShipmentImportJobService(ShipmentImportJobRepository jobs, ShipmentImportJobErrorRepository errors,
			UserAccountRepository users, BulkImportAuthorizer bulkImportAuthorizer, ApplicationEventPublisher events) {
		this.jobs = jobs;
		this.errors = errors;
		this.users = users;
		this.bulkImportAuthorizer = bulkImportAuthorizer;
		this.events = events;
	}

	@Transactional
	public ShipmentImportJobResponse submit(AuthenticatedUser principal, String filename, long fileSizeBytes,
			String contentType, byte[] csv) {
		UserAccount requester = users.findById(principal.id()).orElseThrow(SessionEndedException::new);
		bulkImportAuthorizer.authorize(requester);
		ShipmentImportJob job = jobs.saveAndFlush(new ShipmentImportJob(requester.getCompany(), requester, filename,
				fileSizeBytes, contentType, csv));
		events.publishEvent(new ShipmentImportJobCreatedEvent(job.getId()));
		return ShipmentImportJobResponse.from(job);
	}

	@Transactional(readOnly = true)
	public ShipmentImportJobResponse status(AuthenticatedUser principal, Long jobId) {
		return ShipmentImportJobResponse.from(visibleJob(principal, jobId));
	}

	@Transactional(readOnly = true)
	public List<ShipmentImportJobErrorResponse> errors(AuthenticatedUser principal, Long jobId) {
		ShipmentImportJob job = visibleJob(principal, jobId);
		return errors.findByJobIdOrderByRowNumberAscIdAsc(job.getId()).stream()
				.map(ShipmentImportJobErrorResponse::from)
				.toList();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public ShipmentImportJobInput start(Long jobId) {
		ShipmentImportJob job = jobs.findById(jobId).orElseThrow(ShipmentImportJobNotFoundException::new);
		if (!job.start()) {
			return null;
		}
		byte[] csv = job.getSourceCsv();
		if (csv == null) {
			job.fail("CSV source is unavailable");
			return null;
		}
		return new ShipmentImportJobInput(job.getId(), job.getManagingCompany().getId(), csv);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordInitialErrors(Long jobId, int totalRows, List<ShipmentImportRowError> rowErrors) {
		ShipmentImportJob job = jobs.findById(jobId).orElseThrow(ShipmentImportJobNotFoundException::new);
		int failedRows = (int) rowErrors.stream().map(ShipmentImportRowError::rowNumber).distinct().count();
		job.initializeRows(totalRows, failedRows);
		errors.saveAll(rowErrors.stream().map(error -> new ShipmentImportJobError(job, error)).toList());
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public ShipmentImportJobContext rowContext(Long jobId) {
		ShipmentImportJob job = jobs.findById(jobId).orElseThrow(ShipmentImportJobNotFoundException::new);
		return new ShipmentImportJobContext(job.getManagingCompany().getId(), job.getRequestedBy().getId());
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordImportedRow(Long jobId) {
		jobs.findById(jobId).orElseThrow(ShipmentImportJobNotFoundException::new).recordImportedRow();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordFailedRow(Long jobId, ShipmentImportRowError error) {
		ShipmentImportJob job = jobs.findById(jobId).orElseThrow(ShipmentImportJobNotFoundException::new);
		errors.save(new ShipmentImportJobError(job, error));
		job.recordFailedRow();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void complete(Long jobId) {
		ShipmentImportJob job = jobs.findById(jobId).orElseThrow(ShipmentImportJobNotFoundException::new);
		job.complete();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void fail(Long jobId, String message) {
		jobs.findById(jobId).ifPresent(job -> job.fail(message));
	}

	private ShipmentImportJob visibleJob(AuthenticatedUser principal, Long jobId) {
		UserAccount requester = users.findById(principal.id()).orElseThrow(SessionEndedException::new);
		Company company = requester.getCompany();
		if (company == null || !company.isActive()) {
			throw new ShipmentAccessForbiddenException();
		}
		return jobs.findByIdAndManagingCompanyId(jobId, company.getId())
				.orElseThrow(ShipmentImportJobNotFoundException::new);
	}
}
