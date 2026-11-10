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
	private final PasswordEncoder passwordEncoder;

	public CompanySignupService(CompanySignupStore store, PasswordEncoder passwordEncoder) {
		this.store = store;
		this.passwordEncoder = passwordEncoder;
	}

	public CompanySignupResponse register(CompanySignupRequest request, String clientAddress) {
		OrganisationIdentity identity = OrganisationIdentity.parse(request.companyCode(), request.companyName());
		PasswordPolicy.check(request.password());
		// Hash before looking up the email so a registered email does not answer measurably faster.
		String passwordHash = passwordEncoder.encode(request.password());
		try {
			store.create(identity, request.fullName(), request.email(), passwordHash);
		} catch (SignupEmailTakenException ex) {
			// A concurrent signup took the email; everything from this request was rolled back.
		}
		return CompanySignupResponse.accepted();
	}
}
