package com.drift.backend.account.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
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

@Service
public class AdminInvitationService {

	static final Set<Role> INVITABLE = EnumSet.of(Role.IMPORTER, Role.FREIGHT_FORWARDER, Role.LOGISTICS_MANAGER);
	private static final String PENDING_INDEX = "invitations_one_pending_email";
	private static final String REVOCATION_CONSTRAINT = "invitation_revocations_one_per_invitation";
	private static final Logger log = LoggerFactory.getLogger(AdminInvitationService.class);

	private final UserAccountRepository users;
	private final InvitationRepository invitations;
	private final InvitationRevocationRepository revocations;
	private final InvitationMailer mailer;
	private final Clock clock;
	private final String linkBaseUrl;
	private final Duration ttl;

	@Autowired
	public AdminInvitationService(UserAccountRepository users, InvitationRepository invitations,
			InvitationRevocationRepository revocations, InvitationMailer mailer, ObjectProvider<Clock> clocks,
			@Value("${app.invitation.link-base-url}") String linkBaseUrl, @Value("${app.invitation.ttl}") Duration ttl) {
		this(users, invitations, revocations, mailer, clocks.getIfAvailable(Clock::systemUTC), linkBaseUrl, ttl);
	}

	AdminInvitationService(UserAccountRepository users, InvitationRepository invitations,
			InvitationRevocationRepository revocations, InvitationMailer mailer, Clock clock, String linkBaseUrl, Duration ttl) {
		this.users = users;
		this.invitations = invitations;
		this.revocations = revocations;
		this.mailer = mailer;
		this.clock = clock;
		this.linkBaseUrl = linkBaseUrl;
		this.ttl = ttl;
	}

	@Transactional(readOnly = true)
	public List<ManagedInvitationResponse> pending(com.drift.backend.account.authentication.AuthenticatedUser principal) {
		UserAccount admin = managingAdmin(principal);
		Instant now = clock.instant();
		return invitations.findPendingByCompany(admin.getCompany().getId()).stream()
				.map(invitation -> ManagedInvitationResponse.from(invitation, now))
				.toList();
	}

	@Transactional
	public WithdrawnInvitationResponse withdraw(com.drift.backend.account.authentication.AuthenticatedUser principal,
			Long invitationId) {
		UserAccount admin = managingAdmin(principal);
		Invitation invitation = invitations.findForWithdrawal(invitationId).orElseThrow(InvitationAccessDeniedException::new);
		if (invitation.getCompany() == null || !admin.getCompany().getId().equals(invitation.getCompany().getId())) {
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
		Company organisation = admin.getCompany();
		if (organisation == null || !organisation.isActive()) {
			throw new NoEligibleOrganisationException();
		}
		if (!organisation.getId().equals(request.organisationId())) {
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
