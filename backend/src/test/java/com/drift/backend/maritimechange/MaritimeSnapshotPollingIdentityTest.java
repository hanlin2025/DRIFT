package com.drift.backend.maritimechange;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class MaritimeSnapshotPollingIdentityTest {
	@Test
	void mpaArrivalIdentityKeepsSameDayRevisionsTogetherAndSeparatesDistinctCallDays() throws Exception {
		var record = new ObjectMapper().readTree("""
				{"vesselParticulars":{"callSign":"9VTEST","vesselName":"TEST VESSEL"},
				 "locationFrom":"SEA","locationTo":"PEBGA"}
				""");

		String first = MaritimeSnapshotPollingService.arrivalIdentity(record, Instant.parse("2026-11-12T01:00:00Z"));
		String revisedSameDay = MaritimeSnapshotPollingService.arrivalIdentity(record, Instant.parse("2026-11-12T09:00:00Z"));
		String separateCall = MaritimeSnapshotPollingService.arrivalIdentity(record, Instant.parse("2026-11-13T01:00:00Z"));

		assertThat(revisedSameDay).isEqualTo(first);
		assertThat(separateCall).isNotEqualTo(first);
	}
}
