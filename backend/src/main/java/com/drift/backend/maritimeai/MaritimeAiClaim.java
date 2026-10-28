package com.drift.backend.maritimeai;

import java.util.List;

/** A model claim is displayable only after each evidence id is validated against the input bundle. */
public record MaritimeAiClaim(String statement, List<String> evidenceIds, String applicability) { }
