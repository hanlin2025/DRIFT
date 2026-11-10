package com.drift.backend.organisation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.drift.backend.organisation.exception.InvalidOrganisationException;

class OrganisationIdentityTest {

	@Test
	void storesTheCodeInUppercaseAndKeepsTheDisplayName() {
		OrganisationIdentity identity = OrganisationIdentity.parse("  pacific_gate ", "  Pacific Gate Imports  ");

		assertThat(identity.code()).isEqualTo("PACIFIC_GATE");
		assertThat(identity.name()).isEqualTo("Pacific Gate Imports");
	}

	@Test
	void acceptsANameThatMatchesTheSeededCompanies() {
		OrganisationIdentity identity = OrganisationIdentity.parse("STRAITS_FRESH_DEMO", "Straits Fresh Imports (Demo)");

		assertThat(identity.code()).isEqualTo("STRAITS_FRESH_DEMO");
		assertThat(identity.name()).isEqualTo("Straits Fresh Imports (Demo)");
	}

	@Test
	void acceptsANameOfTheStoredMaximum() {
		String name = "A".repeat(OrganisationIdentity.NAME_MAX);

		assertThat(OrganisationIdentity.parse("GATE", name).name()).isEqualTo(name);
	}

	@Test
	void rejectsAMissingCodeADigitFirstCodeAndACodeThatIsTooLong() {
		assertThatThrownBy(() -> OrganisationIdentity.parse("   ", "Pacific Gate Imports"))
				.isInstanceOf(InvalidOrganisationException.class)
				.hasMessage(InvalidOrganisationException.CODE);
		assertThatThrownBy(() -> OrganisationIdentity.parse("1GATE", "Pacific Gate Imports"))
				.isInstanceOf(InvalidOrganisationException.class)
				.hasMessage(InvalidOrganisationException.CODE);
		assertThatThrownBy(() -> OrganisationIdentity.parse("GATE-1", "Pacific Gate Imports"))
				.isInstanceOf(InvalidOrganisationException.class)
				.hasMessage(InvalidOrganisationException.CODE);
		assertThatThrownBy(() -> OrganisationIdentity.parse("A".repeat(51), "Pacific Gate Imports"))
				.isInstanceOf(InvalidOrganisationException.class)
				.hasMessage(InvalidOrganisationException.CODE);
		assertThatThrownBy(() -> OrganisationIdentity.parse(null, "Pacific Gate Imports"))
				.isInstanceOf(InvalidOrganisationException.class)
				.hasMessage(InvalidOrganisationException.CODE);
	}

	@Test
	void rejectsAMissingNameANameThatIsTooLongAndANameWithAControlCharacter() {
		assertThatThrownBy(() -> OrganisationIdentity.parse("PACIFIC_GATE", "   "))
				.isInstanceOf(InvalidOrganisationException.class)
				.hasMessage(InvalidOrganisationException.NAME);
		assertThatThrownBy(() -> OrganisationIdentity.parse("PACIFIC_GATE", "A".repeat(201)))
				.isInstanceOf(InvalidOrganisationException.class)
				.hasMessage(InvalidOrganisationException.NAME);
		assertThatThrownBy(() -> OrganisationIdentity.parse("PACIFIC_GATE", "Pacific\nGate"))
				.isInstanceOf(InvalidOrganisationException.class)
				.hasMessage(InvalidOrganisationException.NAME);
		assertThatThrownBy(() -> OrganisationIdentity.parse("PACIFIC_GATE", null))
				.isInstanceOf(InvalidOrganisationException.class)
				.hasMessage(InvalidOrganisationException.NAME);
	}
}
