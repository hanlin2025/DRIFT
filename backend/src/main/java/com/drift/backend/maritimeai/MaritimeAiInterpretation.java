package com.drift.backend.maritimeai;

import java.util.List;

public record MaritimeAiInterpretation(
		String summary,
		List<MaritimeAiClaim> claims,
		List<String> uncertainties,
		List<String> followUpConsiderations,
		boolean evidenceSufficient,
		String formalRecommendationCode) { }
