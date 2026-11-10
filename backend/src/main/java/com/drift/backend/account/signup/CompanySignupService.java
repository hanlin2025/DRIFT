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
	private final SignupRateLimiter rateLimiter;
	private final PasswordEncoder passwordEncoder;

	public CompanySignupService(CompanySignupStore store, SignupRateLimiter rateLimiter, PasswordEncoder passwordEncoder) {
		this.store = store;
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
			if (store.create(identity, request.fullName(), request.email(), passwordHash) == CompanySignupStore.Outcome.PENDING) {
				replacePendingSignup(identity, request.fullName(), request.email(), passwordHash, clientAddress);
			}
		} catch (SignupEmailTakenException ex) {
			// A concurrent signup took the email; everything from this request was rolled back.
		}
		return CompanySignupResponse.accepted();
	}

	/**
	 * The newest submission for a pending email wins: it replaces the earlier one and only its emailed link can
	 * activate the account. Limited like a resend; over the limit nothing changes and the answer is still 202,
	 * because a 429 here would reveal that the email is registered.
	 */
	private void replacePendingSignup(OrganisationIdentity identity, String fullName, String email, String passwordHash,
			String clientAddress) {
		try {
			rateLimiter.allowResend(email, clientAddress);
		} catch (SignupRateLimitException ex) {
			return;
		}
		store.replacePending(identity, fullName, email, passwordHash);
	}
}
