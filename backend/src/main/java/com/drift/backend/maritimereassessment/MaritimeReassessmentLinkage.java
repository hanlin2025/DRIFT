package com.drift.backend.maritimereassessment;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Linkage stored in the existing output_json column. A row written by another assessment stays readable. */
final class MaritimeReassessmentLinkage {

	static final String VERSION = "cdg267.reassessment-linkage.v1";
	private static final ObjectMapper JSON = new ObjectMapper();

	private final List<String> snapshotIds;
	private final List<String> excludedSnapshotIds;
	private final String sourceFreshness;
	private final boolean forwardLooking;
	private final String datasetId;
	private final String associationCeiling;
	private final String interpretation;

	private MaritimeReassessmentLinkage(List<String> snapshotIds, List<String> excludedSnapshotIds, String sourceFreshness,
			boolean forwardLooking, String datasetId, String associationCeiling, String interpretation) {
		this.snapshotIds = snapshotIds;
		this.excludedSnapshotIds = excludedSnapshotIds;
		this.sourceFreshness = sourceFreshness;
		this.forwardLooking = forwardLooking;
		this.datasetId = datasetId;
		this.associationCeiling = associationCeiling;
		this.interpretation = interpretation;
	}

	static String write(MaritimeReassessmentBundle bundle, String interpretation) {
		ObjectNode node = JSON.createObjectNode();
		node.put("linkageVersion", VERSION);
		node.set("snapshotIds", strings(bundle.snapshotIds()));
		node.set("excludedSnapshotIds", strings(bundle.excludedSnapshotIds()));
		node.put("sourceFreshness", bundle.sourceFreshness());
		node.put("forwardLooking", bundle.forwardLooking());
		node.put("datasetId", bundle.datasetId());
		node.put("associationCeiling", bundle.associationCeiling());
		if (interpretation == null) {
			node.putNull("interpretation");
		} else {
			node.put("interpretation", interpretation);
		}
		return JSON.writeValueAsString(node);
	}

	static MaritimeReassessmentLinkage read(String outputJson) {
		if (outputJson == null || outputJson.isBlank()) {
			return unknown();
		}
		try {
			JsonNode node = JSON.readTree(outputJson);
			if (node == null || !node.isObject() || !VERSION.equals(text(node.get("linkageVersion")))) {
				return unknown();
			}
			return new MaritimeReassessmentLinkage(strings(node.get("snapshotIds")), strings(node.get("excludedSnapshotIds")),
					text(node.get("sourceFreshness")).isEmpty() ? "UNKNOWN" : text(node.get("sourceFreshness")),
					node.path("forwardLooking").asBoolean(false), text(node.get("datasetId")),
					text(node.get("associationCeiling")), blankToNull(text(node.get("interpretation"))));
		} catch (RuntimeException ex) {
			return unknown();
		}
	}

	private static MaritimeReassessmentLinkage unknown() {
		return new MaritimeReassessmentLinkage(List.of(), List.of(), "UNKNOWN", false, "", "", null);
	}

	private static ArrayNode strings(List<String> values) {
		ArrayNode array = JSON.createArrayNode();
		for (String value : values) {
			array.add(value);
		}
		return array;
	}

	private static List<String> strings(JsonNode node) {
		if (node == null || !node.isArray()) {
			return List.of();
		}
		List<String> values = new ArrayList<>();
		for (JsonNode item : node) {
			if (item != null && item.isString() && !item.asString().isBlank()) {
				values.add(item.asString());
			}
		}
		return List.copyOf(values);
	}

	private static String text(JsonNode node) {
		if (node == null || node.isNull() || !node.isString()) {
			return "";
		}
		return node.asString();
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	List<String> snapshotIds() { return snapshotIds; }
	String sourceFreshness() { return sourceFreshness; }
	boolean forwardLooking() { return forwardLooking; }
	String interpretation() { return interpretation; }
}
