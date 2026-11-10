package com.drift.backend.maritimechange;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Set app.maritime-change.replay.run-on-start=true to run the deterministic local replay once. */
@Component
@ConditionalOnProperty(name = "app.maritime-change.replay.run-on-start", havingValue = "true")
class MaritimeRecordedSnapshotReplayRunner implements ApplicationRunner {
	private final MaritimeRecordedSnapshotReplay replay;

	MaritimeRecordedSnapshotReplayRunner(MaritimeRecordedSnapshotReplay replay) {
		this.replay = replay;
	}

	@Override
	public void run(ApplicationArguments args) {
		replay.replay();
	}
}
