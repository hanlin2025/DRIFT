package com.drift.backend.account.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.exception.DuplicateAccountException;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.account.registration.invitation.Invitation;
import com.drift.backend.account.registration.invitation.InvitationRepository;
import com.drift.backend.account.registration.invitation.InvitationStatus;
import com.drift.backend.account.registration.invitation.InvitationToken;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;

@Service
public class AdminInvitationService {

	static final Set<Role> INVITABLE = EnumSet.of(Role.IMPORTER, Role.FREIGHT_FORWARDER, Role.LOGISTICS_MANAGER);
	private static final String PENDING_INDEX = "invitations_one_pending_email";
	private static final String REVOCATION_CONSTRAINT = "invitation_revocations_one_per_invitation";
	private static final Logger log = LoggerFactory.getLogger(AdminInvitationService.class);

	private final UserAccountRepository users;
	private final CompanyRepository companies;
	private final InvitationRepository invitations;
	private final InvitationRevocationRepository revocations;
	private final InvitationMailer mailer;
	private final Clock clock;
	private final String linkBaseUrl;
	private final Duration ttl;

	@Autowired
	public AdminInvitationService(UserAccountRepository users, CompanyRepository companies,
			InvitationRepository invitations, InvitationRevocationRepository revocations, InvitationMailer mailer,
			ObjectProvider<Clock> clocks, @Value("${app.invitation.link-base-url}") String linkBaseUrl,
			@Value("${app.invitation.ttl}") Duration ttl) {
		this(users, companies, invitations, revocations, mailer, clocks.getIfAvailable(Clock::systemUTC), linkBaseUrl,
				ttl);
	}

	AdminInvitationService(UserAccountRepository users, CompanyRepository companies, InvitationRepository invitations,
			InvitationRevocationRepository revocations, InvitationMailer mailer, Clock clock, String linkBaseUrl,
			Duration ttl) {
		this.users = users;
		this.companies = companies;
		this.invitations = invitations;
		this.revocations = revocations;
		this.mailer = mailer;
		this.clock = clock;
		this.linkBaseUrl = linkBaseUrl;
		this.ttl = ttl;
	}

	@Transactional(readOnly = true)
	public List<AdminOrganisationResponse> invitationOrganisations(
			com.drift.backend.account.authentication.AuthenticatedUser principal) {
		UserAccount admin = managingAdmin(principal);
		return permittedCompanies(admin).stream()
				.sorted(Comparator.comparing(Company::getName).thenComparing(Company::getId))
				.map(AdminOrganisationResponse::from)
				.toList();
	}

	@Transactional(readOnly = true)
	public List<ManagedInvitationResponse> pending(com.drift.backend.account.authentication.AuthenticatedUser principal) {
		UserAccount admin = managingAdmin(principal);
		Instant now = clock.instant();
		return invitations.findPendingByCompanies(permittedCompanyIds(admin)).stream()
				.map(invitation -> ManagedInvitationResponse.from(invitation, now))
				.toList();
	}

	@Transactional
	public WithdrawnInvitationResponse withdraw(com.drift.backend.account.authentication.AuthenticatedUser principal,
			Long invitationId) {
		UserAccount admin = managingAdmin(principal);
		Invitation invitation = invitations.findForWithdrawal(invitationId).orElseThrow(InvitationAccessDeniedException::new);
		if (invitation.getCompany() == null || !mayInviteInto(admin, invitation.getCompany())) {
			throw new InvitationAccessDeniedException();
		}
		if (invitation.getStatus() == InvitationStatus.REVOKED) {
			throw new InvitationAlreadyWithdrawnException();
		}
		if (invitation.getStatus() == InvitationStatus.CONSUMED) {
			throw new InvitationAlreadyAcceptedException();
		}
		Instant now = clock.instant();
		if (!invitation.getExpiresAt().isAfter(now)) {
			throw new InvitationExpiredException();
		}
		invitation.revoke();
		invitations.saveAndFlush(invitation);
		try {
			revocations.saveAndFlush(new InvitationRevocation(invitation, admin, admin.getCompany(), now));
		} catch (DataIntegrityViolationException ex) {
			if (mentions(ex, REVOCATION_CONSTRAINT)) {
				throw new InvitationAlreadyWithdrawnException();
			}
			throw ex;
		}
		return WithdrawnInvitationResponse.from(invitation, now);
	}

	@Transactional
	public InvitationIssuedResponse invite(com.drift.backend.account.authentication.AuthenticatedUser principal,
			InviteColleagueRequest request) {
		UserAccount admin = users.findById(principal.id()).orElseThrow(SessionEndedException::new);
		if (admin.getRole() != Role.ADMIN) {
			throw new com.drift.backend.account.admin.exception.AssignmentForbiddenException();
		}
		Company own = admin.getCompany();
		if (own == null || !own.isActive()) {
			throw new NoEligibleOrganisationException();
		}
		Company organisation = companies.findById(request.organisationId())
				.orElseThrow(() -> new InvalidInvitationException("organisationId", "Choose your active organisation."));
		if (!mayInviteInto(admin, organisation)) {
			throw new InvalidInvitationException("organisationId", "Choose your active organisation.");
		}
		Role role = role(request.role());
		if (users.existsByEmailIgnoreCase(request.email())) {
			throw new DuplicateAccountException();
		}
		invitations.findPendingForUpdate(request.email()).ifPresent(pending -> {
			pending.revoke();
			invitations.saveAndFlush(pending);
		});
		String code = InvitationToken.generate();
		Instant now = clock.instant();
		Invitation invitation = Invitation.issue(request.email(), organisation, role, InvitationToken.hash(code), now.plus(ttl), now);
		try {
			invitations.saveAndFlush(invitation);
		} catch (DataIntegrityViolationException ex) {
			if (mentions(ex, PENDING_INDEX)) {
				throw new InvitationConflictException();
			}
			throw ex;
		}
		try {
			mailer.sendInvitation(request.email(), InvitationMailConfig.link(linkBaseUrl, code), code, organisation.getName(),
					AdminAssignmentService.label(role), ttl);
		} catch (RuntimeException ex) {
			log.error("Invitation email could not be sent ({})", ex.getClass().getSimpleName());
			throw new InvitationDeliveryException();
		}
		return InvitationIssuedResponse.from(invitation);
	}

	private UserAccount managingAdmin(com.drift.backend.account.authentication.AuthenticatedUser principal) {
		UserAccount admin = users.findById(principal.id()).orElseThrow(SessionEndedException::new);
		if (admin.getRole() != Role.ADMIN) {
			throw new com.drift.backend.account.admin.exception.AssignmentForbiddenException();
		}
		Company organisation = admin.getCompany();
		if (organisation == null || !organisation.isActive()) {
			throw new InvitationManagementForbiddenException();
		}
		return admin;
	}

	private Set<Long> permittedCompanyIds(UserAccount admin) {
		Map<Long, Company> permitted = new LinkedHashMap<>();
		for (Company company : permittedCompanies(admin)) {
			permitted.put(company.getId(), company);
		}
		return permitted.keySet();
	}

	private List<Company> permittedCompanies(UserAccount admin) {
		Map<Long, Company> permitted = new LinkedHashMap<>();
		Company own = admin.getCompany();
		permitted.put(own.getId(), own);
		for (Company empty : companies.findActiveEmptyCreatedBy(admin.getId())) {
			permitted.putIfAbsent(empty.getId(), empty);
		}
		return List.copyOf(permitted.values());
	}

	private boolean mayInviteInto(UserAccount admin, Company organisation) {
		if (organisation == null || !organisation.isActive()) {
			return false;
		}
		if (admin.getCompany().getId().equals(organisation.getId())) {
			return true;
		}
		UserAccount creator = organisation.getCreatedBy();
		return creator != null && admin.getId().equals(creator.getId())
				&& !users.existsByCompany_Id(organisation.getId());
	}

	private static Role role(String code) {
		Role parsed;
		try {
			parsed = Role.valueOf(code);
		} catch (IllegalArgumentException ex) {
			throw new InvalidInvitationException("role", "Choose a role this administrator can invite.");
		}
		if (!INVITABLE.contains(parsed)) {
			throw new InvalidInvitationException("role", "Choose a role this administrator can invite.");
		}
		return parsed;
	}

	private static boolean mentions(Throwable failure, String text) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current instanceof ConstraintViolationException violation && text.equals(violation.getConstraintName())) {
				return true;
			}
			if (current.getMessage() != null && current.getMessage().contains(text)) {
				return true;
			}
		}
		return false;
	}
}
