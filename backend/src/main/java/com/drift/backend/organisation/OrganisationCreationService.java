package com.drift.backend.organisation;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.admin.exception.AssignmentForbiddenException;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;
import com.drift.backend.organisation.exception.DuplicateOrganisationException;
import com.drift.backend.organisation.exception.InvalidOrganisationException;
import com.drift.backend.organisation.exception.OrganisationCreationForbiddenException;

@Service
public class OrganisationCreationService {

	private final CompanyRepository companies;
	private final UserAccountRepository users;

	public OrganisationCreationService(CompanyRepository companies, UserAccountRepository users) {
		this.companies = companies;
		this.users = users;
	}

	@Transactional
	public OrganisationResponse create(AuthenticatedUser principal, CreateOrganisationRequest request) {
		requireActiveAdmin(principal);
		if (request == null) {
			throw new InvalidOrganisationException(InvalidOrganisationException.CODE);
		}
		OrganisationIdentity identity = OrganisationIdentity.parse(request.code(), request.name());
		if (companies.findByNormalisedCode(identity.code()).isPresent()) {
			throw new DuplicateOrganisationException();
		}
		try {
			Company saved = companies.saveAndFlush(Company.createActive(identity.code(), identity.name()));
			return new OrganisationResponse(saved.getId(), saved.getCode(), saved.getName());
		} catch (DataIntegrityViolationException ex) {
			throw new DuplicateOrganisationException();
		}
	}

	private void requireActiveAdmin(AuthenticatedUser principal) {
		UserAccount account = users.findById(principal.id()).orElseThrow(SessionEndedException::new);
		if (account.getRole() != Role.ADMIN) {
			throw new AssignmentForbiddenException();
		}
		Company company = account.getCompany();
		if (company == null || !company.isActive()) {
			throw new OrganisationCreationForbiddenException();
		}
	}
}
