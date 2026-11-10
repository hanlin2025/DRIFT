package com.drift.backend.account.signup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The public signup route must stay unregistered unless it is switched on. */
class CompanySignupGateTest {

	private final ApplicationContextRunner context = new ApplicationContextRunner()
			.withBean(CompanySignupService.class, () -> mock(CompanySignupService.class))
			.withUserConfiguration(CompanySignupController.class);

	@Test
	void routeIsNotRegisteredByDefault() {
		context.run(app -> assertThat(app).doesNotHaveBean(CompanySignupController.class));
	}

	@Test
	void routeIsNotRegisteredWhenSwitchedOff() {
		context.withPropertyValues("app.signup.enabled=false")
				.run(app -> assertThat(app).doesNotHaveBean(CompanySignupController.class));
	}

	@Test
	void routeIsRegisteredOnlyWhenSwitchedOn() {
		context.withPropertyValues("app.signup.enabled=true")
				.run(app -> assertThat(app).hasSingleBean(CompanySignupController.class));
	}
}
