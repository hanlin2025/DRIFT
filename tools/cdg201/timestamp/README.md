# CDG-201 timestamp utility

This is the curated, version-controlled source for derived MPA/NEA timestamp
representations. It does not contact an API or modify raw evidence.

- MPA source timestamps without an offset are interpreted as `Asia/Singapore`.
- Raw source strings remain unchanged; SGT and UTC are derived representations.
- Retrieval timestamps remain UTC.
- Offset-aware NEA timestamps retain their instant and are converted to SGT only
  for display or Singapore-date grouping.

The active standalone collectors in `~/opt/data/change_detection/` remain
operationally authoritative until the team explicitly adopts a deployment path.
