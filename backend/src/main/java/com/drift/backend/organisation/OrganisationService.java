package com.drift.backend.organisation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;
import com.drift.backend.organisation.exception.InvalidOrganisationQueryException;
import com.drift.backend.organisation.exception.OrganisationAccessForbiddenException;

@Service
public class OrganisationService {

	static final int DEFAULT_PAGE_SIZE = 20;
	static final int MAX_PAGE_SIZE = 100;

	private final CompanyRepository companies;
	private final UserAccountRepository users;

	public OrganisationService(CompanyRepository companies, UserAccountRepository users) {
		this.companies = companies;
		this.users = users;
	}

	@Transactional(readOnly = true)
	public OrganisationPageResponse search(AuthenticatedUser principal, String type, String name, Integer page,
			Integer size) {
		Company company = activeCompany(principal);
		if (type == null || !type.strip().equalsIgnoreCase("importer")) {
			throw new InvalidOrganisationQueryException(InvalidOrganisationQueryException.TYPE);
		}
		int pageIndex = page == null ? 0 : page;
		int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
		if (pageIndex < 0) {
			throw new InvalidOrganisationQueryException(InvalidOrganisationQueryException.PAGE);
		}
		if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
			throw new InvalidOrganisationQueryException(InvalidOrganisationQueryException.SIZE);
		}
		String term = name == null ? "" : name.strip();
		Page<Company> found = companies.findActiveOtherCompanies(company.getId(), term, PageRequest.of(pageIndex,
				pageSize, Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"))));
		return new OrganisationPageResponse(found.getContent().stream()
				.map(match -> new OrganisationResponse(match.getId(), match.getCode(), match.getName()))
				.toList(), pageIndex, pageSize, found.getTotalElements());
	}

	private Company activeCompany(AuthenticatedUser principal) {
		UserAccount account = users.findById(principal.id()).orElseThrow(SessionEndedException::new);
		Company company = account.getCompany();
		if (company == null || !company.isActive()) {
			throw new OrganisationAccessForbiddenException();
		}
		return company;
	}
}
