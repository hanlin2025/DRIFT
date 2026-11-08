package com.drift.backend.importerauthorisation;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;
import com.drift.backend.company.ForwarderImporterRelationship;
import com.drift.backend.company.ForwarderImporterRelationshipRepository;
import com.drift.backend.organisation.OrganisationResponse;
import com.drift.backend.shipment.exception.InvalidImporterOrganisationException;

@Service
public class ImporterAuthorisationService {

	private final UserAccountRepository users;
	private final CompanyRepository companies;
	private final ForwarderImporterRelationshipRepository relationships;
	private final TransactionTemplate transactions;

	public ImporterAuthorisationService(UserAccountRepository users, CompanyRepository companies,
			ForwarderImporterRelationshipRepository relationships, PlatformTransactionManager transactions) {
		this.users = users;
		this.companies = companies;
		this.relationships = relationships;
		this.transactions = new TransactionTemplate(transactions);
	}

	public ImporterAuthorisationResponse grant(AuthenticatedUser principal, Long importerCompanyId) {
		try {
			return grantInTransaction(principal, importerCompanyId);
		} catch (RuntimeException exception) {
			if (!isUniqueConflict(exception)) {
				throw exception;
			}
			return grantInTransaction(principal, importerCompanyId);
		}
	}

	@Transactional(readOnly = true)
	public ImporterAuthorisationListResponse list(AuthenticatedUser principal) {
		UserAccount account = viewer(principal);
		return new ImporterAuthorisationListResponse(relationships.findActiveByForwarderCompanyId(account.getCompany().getId())
				.stream()
				.map(this::response)
				.toList());
	}

	@Transactional
	public ImporterAuthorisationResponse end(AuthenticatedUser principal, Long authorisationId) {
		UserAccount account = administrator(principal);
		ForwarderImporterRelationship row = relationships.findByIdForUpdate(authorisationId)
				.filter(found -> found.getForwarderCompany().getId().equals(account.getCompany().getId()))
				.orElseThrow(ImporterAuthorisationNotFoundException::new);
		if (row.isActive()) {
			row.deactivate(account);
		}
		return response(row);
	}

	private ImporterAuthorisationResponse grantInTransaction(AuthenticatedUser principal, Long importerCompanyId) {
		return transactions.execute(status -> {
			UserAccount account = administrator(principal);
			Company importer = otherActiveCompany(account.getCompany(), importerCompanyId);
			return relationships.findByPairForUpdate(account.getCompany().getId(), importer.getId())
					.map(existing -> {
						if (!existing.isActive()) {
							existing.reactivate(account);
						}
						return response(existing);
					})
					.orElseGet(() -> response(relationships.saveAndFlush(
							new ForwarderImporterRelationship(account.getCompany(), importer, account))));
		});
	}

	private UserAccount viewer(AuthenticatedUser principal) {
		UserAccount account = account(principal);
		if (account.getRole() != Role.ADMIN && account.getRole() != Role.FREIGHT_FORWARDER) {
			throw new ImporterAuthorisationForbiddenException(ImporterAuthorisationForbiddenException.VIEW);
		}
		return account;
	}

	private UserAccount administrator(AuthenticatedUser principal) {
		UserAccount account = account(principal);
		if (account.getRole() != Role.ADMIN) {
			throw new ImporterAuthorisationForbiddenException(ImporterAuthorisationForbiddenException.CHANGE);
		}
		return account;
	}

	private UserAccount account(AuthenticatedUser principal) {
		UserAccount account = users.findById(principal.id()).orElseThrow(SessionEndedException::new);
		Company company = account.getCompany();
		if (company == null || !company.isActive()) {
			throw new ImporterAuthorisationForbiddenException(ImporterAuthorisationForbiddenException.ACTIVE_COMPANY);
		}
		return account;
	}

	private Company otherActiveCompany(Company forwarder, Long importerCompanyId) {
		if (importerCompanyId == null) {
			throw new InvalidImporterOrganisationException();
		}
		return companies.findById(importerCompanyId)
				.filter(Company::isActive)
				.filter(company -> !company.getId().equals(forwarder.getId()))
				.orElseThrow(InvalidImporterOrganisationException::new);
	}

	private ImporterAuthorisationResponse response(ForwarderImporterRelationship row) {
		Company importer = row.getImporterCompany();
		return new ImporterAuthorisationResponse(row.getId(),
				new OrganisationResponse(importer.getId(), importer.getCode(), importer.getName()), row.isActive());
	}

	private static boolean isUniqueConflict(Throwable exception) {
		Throwable current = exception;
		while (current != null) {
			if (current instanceof DataIntegrityViolationException || current instanceof ConstraintViolationException) {
				return true;
			}
			if (current instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}
}
