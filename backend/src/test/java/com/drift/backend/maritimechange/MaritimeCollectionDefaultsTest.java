package com.drift.backend.maritimechange;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MaritimeCollectionDefaultsTest {
	private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(
			MaritimeCollectionExecutorConfiguration.class, MaritimeSnapshotCollectionScheduler.class,
			MaritimeRecordedSnapshotReplayRunner.class);

	@Test
	void withoutConfigurationNoCollectorWorkerSchedulerOrReplayRunnerExists() {
		context.run(started -> {
			assertThat(started).hasNotFailed();
			assertThat(started).doesNotHaveBean("maritimeCollectionExecutor");
			assertThat(started).doesNotHaveBean(MaritimeSnapshotCollectionScheduler.class);
			assertThat(started).doesNotHaveBean(MaritimeRecordedSnapshotReplayRunner.class);
		});
	}

	@Test
	void anExplicitlyDisabledCollectionStaysOff() {
		context.withPropertyValues("app.maritime-change.collection.enabled=false",
				"app.maritime-change.replay.run-on-start=false").run(started -> {
			assertThat(started).doesNotHaveBean("maritimeCollectionExecutor");
			assertThat(started).doesNotHaveBean(MaritimeSnapshotCollectionScheduler.class);
			assertThat(started).doesNotHaveBean(MaritimeRecordedSnapshotReplayRunner.class);
		});
	}

	@Test
	void theCollectorWorkerIsCreatedOnlyWhenCollectionIsEnabled() {
		new ApplicationContextRunner().withUserConfiguration(MaritimeCollectionExecutorConfiguration.class)
				.withPropertyValues("app.maritime-change.collection.enabled=true")
				.run(started -> assertThat(started).hasBean("maritimeCollectionExecutor"));
	}
}
