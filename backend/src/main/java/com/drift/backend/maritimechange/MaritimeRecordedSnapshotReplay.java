package com.drift.backend.maritimechange;

import java.io.InputStream;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Offline replay of compact, authentic recorded observations through the live normalizer and detector. */
@Service
class MaritimeRecordedSnapshotReplay {
	private final ObjectMapper json;
	private final MaritimeSnapshotNormalizer normalizer;
	private final MaritimeSnapshotChangeDetector detector;

	MaritimeRecordedSnapshotReplay(ObjectMapper json, MaritimeSnapshotNormalizer normalizer,
			MaritimeSnapshotChangeDetector detector) {
		this.json = json;
		this.normalizer = normalizer;
		this.detector = detector;
	}

	@Transactional
	MaritimeSnapshotChangeBatch replay() {
		List<MaritimeSourceObservation> observations = readFixture();
		return detector.accept(observations.stream().map(normalizer::normalize).toList());
	}

	private List<MaritimeSourceObservation> readFixture() {
		try (InputStream input = MaritimeRecordedSnapshotReplay.class
				.getResourceAsStream("/maritime-context/cdg358-recorded-replay.json")) {
			if (input == null) throw new IllegalStateException("CDG-358 replay fixture is missing");
			return json.readValue(input, new TypeReference<List<MaritimeSourceObservation>>() { });
		} catch (Exception ex) {
			throw new IllegalStateException("CDG-358 replay fixture is invalid", ex);
		}
	}
}
