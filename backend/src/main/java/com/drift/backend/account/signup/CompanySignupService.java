package com.drift.backend.account.signup;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.drift.backend.account.registration.PasswordPolicy;
import com.drift.backend.organisation.OrganisationIdentity;

/**
 * Public self-service signup: a new company and its first administrator. The response never says whether
 * the email was already registered.
 */
@Service
public class CompanySignupService {

	private final CompanySignupStore store;
	private final EmailVerificationStore verifications;
	private final SignupRateLimiter rateLimiter;
	private final PasswordEncoder passwordEncoder;

	public CompanySignupService(CompanySignupStore store, EmailVerificationStore verifications,
			SignupRateLimiter rateLimiter, PasswordEncoder passwordEncoder) {
		this.store = store;
		this.verifications = verifications;
		this.rateLimiter = rateLimiter;
		this.passwordEncoder = passwordEncoder;
	}

	public CompanySignupResponse register(CompanySignupRequest request, String clientAddress) {
		OrganisationIdentity identity = OrganisationIdentity.parse(request.companyCode(), request.companyName());
		PasswordPolicy.check(request.password());
		rateLimiter.allowSignup(request.email(), clientAddress);
		// Hash before looking up the email so a registered email does not answer measurably faster.
		String passwordHash = passwordEncoder.encode(request.password());
		try {
			if (store.create(identity, request.fullName(), request.email(), passwordHash).isEmpty()) {
				resendIfStillPending(request.email(), identity.code(), clientAddress);
			}
		} catch (SignupEmailTakenException ex) {
			// A concurrent signup took the email; everything from this request was rolled back.
		}
		return CompanySignupResponse.accepted();
	}

	/** Repeating a signup that is still awaiting verification sends a fresh link, within the resend limit. */
	private void resendIfStillPending(String email, String companyCode, String clientAddress) {
		if (!store.isPendingAdministratorOf(email, companyCode)) {
			return;
		}
		try {
			rateLimiter.allowResend(email, clientAddress);
		} catch (SignupRateLimitException ex) {
			// Answering 429 here would reveal that the email is registered.
			return;
		}
		verifications.reissue(email);
	}
}
