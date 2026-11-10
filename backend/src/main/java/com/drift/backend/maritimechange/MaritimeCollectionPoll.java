package com.drift.backend.maritimechange;

import java.util.List;

record MaritimeCollectionPoll(String source, boolean successful, List<MaritimeSnapshot> snapshots) {
}
