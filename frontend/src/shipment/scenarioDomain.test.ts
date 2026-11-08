import { afterEach, describe, expect, it, vi } from 'vitest';
import { getScenarioSession, type ScenarioSessionEnvelope } from './scenarioApi';
import { decodeScenarioObservation, decodeScenarioPlannedWindow, decodeScenarioSnapshot, type ScenarioDomainSelection } from './scenarioDomain';

// Jackson 3 output from CDG-288 6ec4333: SyntheticDisruptionScenarioFixture.create("Api"),
// ShipmentConnectionRisk.assessControlledSnapshot, DelayComparison.of and
// MaritimeContextService.forSyntheticScenario. No server/database/provider was used.
// Only envelope/session IDs and ordinary navigation bindings are deterministic test inputs.
const backendPayloads: unknown[] = [
  {
    "apiVersion": "1",
    "sessionId": "d5f2390b-d610-4c5d-9b6c-7153f8a89fbf",
    "scenarioId": "CDG279-Api",
    "scenarioContractVersion": "cdg279.synthetic-disruption.v1",
    "state": "ACTIVE",
    "epochId": "74dab1c6-1d4b-4889-8dde-0b31986fed28",
    "revision": 1,
    "capabilities": {
      "canRead": true,
      "canStart": false,
      "canAdvance": true,
      "canReset": true,
      "canGenerateAssessment": false
    },
    "shipments": [
      {
        "scenarioShipmentKey": "A",
        "shipmentId": 142,
        "shipmentReference": "DEMO-A-PORTFOLIO",
        "scenarioShipmentReference": "DEMO-CDG279-Api-A"
      },
      {
        "scenarioShipmentKey": "B",
        "shipmentId": 143,
        "shipmentReference": "DEMO-B-PORTFOLIO",
        "scenarioShipmentReference": "DEMO-CDG279-Api-B"
      }
    ],
    "snapshot": {
      "snapshotId": "063052b2-fca1-4116-b559-f387ac6c2dc0",
      "epochId": "74dab1c6-1d4b-4889-8dde-0b31986fed28",
      "revision": 1,
      "stage": "BASELINE",
      "stageOrder": 0,
      "stageCount": 3,
      "fixtureStageId": "baseline",
      "simulationCutoffUtc": "2026-10-01T00:00:00Z",
      "mapPlaybackCutoffUtc": "2026-10-01T00:00:00Z",
      "shipments": [
        {
          "scenarioShipmentKey": "A",
          "sourceShipmentId": 142,
          "scenarioShipmentReference": "DEMO-CDG279-Api-A",
          "transshipmentPort": "Synthetic Multi-source Port",
          "plannedMotherArrivalAt": "2026-10-01T06:00:00Z",
          "plannedFeederDepartureAt": "2026-10-02T12:00:00Z",
          "plannedConnectionWindow": {
            "status": "AVAILABLE",
            "window": {
              "duration": "1 day 6 hours",
              "totalSeconds": 108000
            }
          },
          "remainingConnectionWindow": {
            "status": "AVAILABLE",
            "duration": "1 day 6 hours",
            "totalSeconds": 108000,
            "missedBy": null,
            "missedBySeconds": null
          },
          "arrivalEstimate": {
            "estimatedArrivalAt": "2026-10-01T06:00:00Z",
            "limitation": "Approximate arrival — based on straight-line distance and current speed.",
            "speedOverGroundKnots": 10,
            "classification": "ON_SCHEDULE",
            "difference": "On schedule · estimated at the planned time",
            "differenceSeconds": 0,
            "differenceNanos": 0,
            "lastReceivedAt": "2026-10-01T00:00:00Z",
            "possiblyOutdated": false,
            "unavailableReason": null
          },
          "risk": {
            "level": "LOW",
            "explanation": "At least 24 hours remain to transfer cargo, based on the mother vessel's estimated arrival."
          },
          "businessImpact": {
            "outcome": "NO_PREDICTED_IMPACT",
            "reason": null,
            "message": "No predicted impact on the recorded mother-vessel transshipment-arrival deadline.",
            "estimatedArrivalAt": "2026-10-01T06:00:00Z",
            "deadline": "2026-10-01T12:00:00Z",
            "recordedTransshipmentPort": "Synthetic Multi-source Port"
          }
        },
        {
          "scenarioShipmentKey": "B",
          "sourceShipmentId": 143,
          "scenarioShipmentReference": "DEMO-CDG279-Api-B",
          "transshipmentPort": "Synthetic Multi-source Port",
          "plannedMotherArrivalAt": "2026-10-01T06:00:00Z",
          "plannedFeederDepartureAt": "2026-10-02T12:00:00Z",
          "plannedConnectionWindow": {
            "status": "AVAILABLE",
            "window": {
              "duration": "1 day 6 hours",
              "totalSeconds": 108000
            }
          },
          "remainingConnectionWindow": {
            "status": "AVAILABLE",
            "duration": "1 day 6 hours",
            "totalSeconds": 108000,
            "missedBy": null,
            "missedBySeconds": null
          },
          "arrivalEstimate": {
            "estimatedArrivalAt": "2026-10-01T06:00:00Z",
            "limitation": "Approximate arrival — based on straight-line distance and current speed.",
            "speedOverGroundKnots": 10,
            "classification": "ON_SCHEDULE",
            "difference": "On schedule · estimated at the planned time",
            "differenceSeconds": 0,
            "differenceNanos": 0,
            "lastReceivedAt": "2026-10-01T00:00:00Z",
            "possiblyOutdated": false,
            "unavailableReason": null
          },
          "risk": {
            "level": "LOW",
            "explanation": "At least 24 hours remain to transfer cargo, based on the mother vessel's estimated arrival."
          },
          "businessImpact": {
            "outcome": "NO_PREDICTED_IMPACT",
            "reason": null,
            "message": "No predicted impact on the recorded mother-vessel transshipment-arrival deadline.",
            "estimatedArrivalAt": "2026-10-01T06:00:00Z",
            "deadline": "2026-10-02T10:00:00Z",
            "recordedTransshipmentPort": "Synthetic Multi-source Port"
          }
        }
      ],
      "aisObservations": [
        {
          "vesselRole": "MOTHER",
          "observedAtUtc": "2026-10-01T00:00:00Z",
          "receivedAtUtc": "2026-10-01T00:00:00Z",
          "sourceMode": "SYNTHETIC_DEMONSTRATION",
          "position": {
            "mmsi": "990279001",
            "vesselName": "CDG279 SYNTHETIC MOTHER Api",
            "latitude": 2.26,
            "longitude": 103.82,
            "speedOverGroundKnots": 10,
            "courseOverGroundDegrees": 180,
            "trueHeadingDegrees": 180,
            "navigationalStatus": 0,
            "positionValid": true,
            "aisUtcSecond": 0,
            "ingestedAt": "2026-10-01T00:00:00Z",
            "source": "SEEDED"
          }
        },
        {
          "vesselRole": "FEEDER",
          "observedAtUtc": "2026-10-01T00:00:00Z",
          "receivedAtUtc": "2026-10-01T00:00:00Z",
          "sourceMode": "SYNTHETIC_DEMONSTRATION",
          "position": {
            "mmsi": "990279002",
            "vesselName": "CDG279 SYNTHETIC FEEDER Api",
            "latitude": 1.26,
            "longitude": 103.82,
            "speedOverGroundKnots": 0,
            "courseOverGroundDegrees": null,
            "trueHeadingDegrees": null,
            "navigationalStatus": 5,
            "positionValid": true,
            "aisUtcSecond": 0,
            "ingestedAt": "2026-10-01T00:00:00Z",
            "source": "SEEDED"
          }
        }
      ],
      "maritimeContext": {
        "contractVersion": "cdg246.maritime-evidence-bundle.v1",
        "enabled": true,
        "sourceMode": "UNAVAILABLE",
        "assessmentCutoffUtc": "2026-10-01T00:00:00Z",
        "shipmentAssociationConfidence": "UNAVAILABLE",
        "limitation": "Human review is required. This bundle is a read-only replay/demo and does not change ETA, shipment, risk, alerts, or operational decisions.",
        "evidence": [],
        "notices": [],
        "aiExplanation": null,
        "suggestedActions": null
      }
    }
  },
  {
    "apiVersion": "1",
    "sessionId": "d5f2390b-d610-4c5d-9b6c-7153f8a89fbf",
    "scenarioId": "CDG279-Api",
    "scenarioContractVersion": "cdg279.synthetic-disruption.v1",
    "state": "ACTIVE",
    "epochId": "74dab1c6-1d4b-4889-8dde-0b31986fed28",
    "revision": 2,
    "capabilities": {
      "canRead": true,
      "canStart": false,
      "canAdvance": true,
      "canReset": true,
      "canGenerateAssessment": false
    },
    "shipments": [
      {
        "scenarioShipmentKey": "A",
        "shipmentId": 142,
        "shipmentReference": "DEMO-A-PORTFOLIO",
        "scenarioShipmentReference": "DEMO-CDG279-Api-A"
      },
      {
        "scenarioShipmentKey": "B",
        "shipmentId": 143,
        "shipmentReference": "DEMO-B-PORTFOLIO",
        "scenarioShipmentReference": "DEMO-CDG279-Api-B"
      }
    ],
    "snapshot": {
      "snapshotId": "063052b2-fca1-4116-b559-f387ac6c2dc1",
      "epochId": "74dab1c6-1d4b-4889-8dde-0b31986fed28",
      "revision": 2,
      "stage": "CHANGE",
      "stageOrder": 1,
      "stageCount": 3,
      "fixtureStageId": "material-slowdown",
      "simulationCutoffUtc": "2026-10-01T04:00:00Z",
      "mapPlaybackCutoffUtc": "2026-10-01T04:00:00Z",
      "shipments": [
        {
          "scenarioShipmentKey": "A",
          "sourceShipmentId": 142,
          "scenarioShipmentReference": "DEMO-CDG279-Api-A",
          "transshipmentPort": "Synthetic Multi-source Port",
          "plannedMotherArrivalAt": "2026-10-01T06:00:00Z",
          "plannedFeederDepartureAt": "2026-10-02T12:00:00Z",
          "plannedConnectionWindow": {
            "status": "AVAILABLE",
            "window": {
              "duration": "1 day 6 hours",
              "totalSeconds": 108000
            }
          },
          "remainingConnectionWindow": {
            "status": "AVAILABLE",
            "duration": "20 hours",
            "totalSeconds": 72000,
            "missedBy": null,
            "missedBySeconds": null
          },
          "arrivalEstimate": {
            "estimatedArrivalAt": "2026-10-01T16:00:00Z",
            "limitation": "Approximate arrival — based on straight-line distance and current speed.",
            "speedOverGroundKnots": 3,
            "classification": "BEHIND_PLAN",
            "difference": "Behind plan · estimated 10h later than planned",
            "differenceSeconds": 36000,
            "differenceNanos": 0,
            "lastReceivedAt": "2026-10-01T04:00:00Z",
            "possiblyOutdated": false,
            "unavailableReason": null
          },
          "risk": {
            "level": "MEDIUM",
            "explanation": "Less than 24 hours remain to transfer cargo, based on the mother vessel's estimated arrival."
          },
          "businessImpact": {
            "outcome": "EXCEEDS_DEADLINE",
            "reason": null,
            "message": "The predicted arrival exceeds the recorded mother-vessel transshipment-arrival deadline.",
            "estimatedArrivalAt": "2026-10-01T16:00:00Z",
            "deadline": "2026-10-01T12:00:00Z",
            "recordedTransshipmentPort": "Synthetic Multi-source Port"
          }
        },
        {
          "scenarioShipmentKey": "B",
          "sourceShipmentId": 143,
          "scenarioShipmentReference": "DEMO-CDG279-Api-B",
          "transshipmentPort": "Synthetic Multi-source Port",
          "plannedMotherArrivalAt": "2026-10-01T06:00:00Z",
          "plannedFeederDepartureAt": "2026-10-02T12:00:00Z",
          "plannedConnectionWindow": {
            "status": "AVAILABLE",
            "window": {
              "duration": "1 day 6 hours",
              "totalSeconds": 108000
            }
          },
          "remainingConnectionWindow": {
            "status": "AVAILABLE",
            "duration": "20 hours",
            "totalSeconds": 72000,
            "missedBy": null,
            "missedBySeconds": null
          },
          "arrivalEstimate": {
            "estimatedArrivalAt": "2026-10-01T16:00:00Z",
            "limitation": "Approximate arrival — based on straight-line distance and current speed.",
            "speedOverGroundKnots": 3,
            "classification": "BEHIND_PLAN",
            "difference": "Behind plan · estimated 10h later than planned",
            "differenceSeconds": 36000,
            "differenceNanos": 0,
            "lastReceivedAt": "2026-10-01T04:00:00Z",
            "possiblyOutdated": false,
            "unavailableReason": null
          },
          "risk": {
            "level": "MEDIUM",
            "explanation": "Less than 24 hours remain to transfer cargo, based on the mother vessel's estimated arrival."
          },
          "businessImpact": {
            "outcome": "NO_PREDICTED_IMPACT",
            "reason": null,
            "message": "No predicted impact on the recorded mother-vessel transshipment-arrival deadline.",
            "estimatedArrivalAt": "2026-10-01T16:00:00Z",
            "deadline": "2026-10-02T10:00:00Z",
            "recordedTransshipmentPort": "Synthetic Multi-source Port"
          }
        }
      ],
      "aisObservations": [
        {
          "vesselRole": "MOTHER",
          "observedAtUtc": "2026-10-01T04:00:00Z",
          "receivedAtUtc": "2026-10-01T04:00:00Z",
          "sourceMode": "SYNTHETIC_DEMONSTRATION",
          "position": {
            "mmsi": "990279001",
            "vesselName": "CDG279 SYNTHETIC MOTHER Api",
            "latitude": 1.86,
            "longitude": 103.82,
            "speedOverGroundKnots": 3,
            "courseOverGroundDegrees": 180,
            "trueHeadingDegrees": 180,
            "navigationalStatus": 0,
            "positionValid": true,
            "aisUtcSecond": 0,
            "ingestedAt": "2026-10-01T04:00:00Z",
            "source": "SEEDED"
          }
        },
        {
          "vesselRole": "FEEDER",
          "observedAtUtc": "2026-10-01T04:00:00Z",
          "receivedAtUtc": "2026-10-01T04:00:00Z",
          "sourceMode": "SYNTHETIC_DEMONSTRATION",
          "position": {
            "mmsi": "990279002",
            "vesselName": "CDG279 SYNTHETIC FEEDER Api",
            "latitude": 1.26,
            "longitude": 103.82,
            "speedOverGroundKnots": 0,
            "courseOverGroundDegrees": null,
            "trueHeadingDegrees": null,
            "navigationalStatus": 5,
            "positionValid": true,
            "aisUtcSecond": 0,
            "ingestedAt": "2026-10-01T04:00:00Z",
            "source": "SEEDED"
          }
        }
      ],
      "maritimeContext": {
        "contractVersion": "cdg246.maritime-evidence-bundle.v1",
        "enabled": true,
        "sourceMode": "SYNTHETIC_DEMONSTRATION",
        "assessmentCutoffUtc": "2026-10-01T04:00:00Z",
        "shipmentAssociationConfidence": "VERIFIED",
        "limitation": "Human review is required. This bundle is a read-only replay/demo and does not change ETA, shipment, risk, alerts, or operational decisions.",
        "evidence": [
          {
            "sourceType": "MPA_ETA_REVISION",
            "sourceMode": "SYNTHETIC_DEMONSTRATION",
            "evidenceId": "cdg279-v1-revision-1",
            "title": "Synthetic expected-arrival revision",
            "sourceIdentifier": "CDG279-SYNTHETIC-MPA-REVISION-1",
            "sourceUrl": "https://example.invalid/cdg279/revision-1",
            "citation": "Synthetic MPA-style revision 1; the record is fictional and is not an authoritative vessel ETA.",
            "sourceEventAt": null,
            "observedAt": "2026-10-01T04:00:00Z",
            "retrievedAtUtc": "2026-10-01T04:00:00Z",
            "freshness": "FRESH",
            "previousValue": "2026-10-01T06:00:00Z",
            "currentValue": "2026-10-01T15:00:00Z",
            "changeMagnitude": null,
            "activityCompatibility": "COMPATIBLE",
            "locationCompatibility": "COMPATIBLE",
            "timeCompatibility": "COMPATIBLE",
            "compatibilityState": "COMPATIBLE",
            "evidenceSufficient": true,
            "humanReviewRequired": true,
            "shipmentAssociationConfidence": "VERIFIED",
            "limitation": "The revised expected-arrival value is fictional and deliberately differs from the AIS-derived estimate; it does not replace or change risk. Fictional verified association only: this fixture binds both shipment references to one assumed mother-to-feeder visit. It is not AIS evidence of cargo carriage, a real booking, or a real vessel identity.",
            "vesselIdentity": "CDG279 SYNTHETIC MOTHER Api",
            "vesselRole": "MOTHER",
            "observationType": "EXPECTED_ARRIVAL",
            "associationScope": "VESSEL_OBSERVATION"
          }
        ],
        "notices": [],
        "aiExplanation": null,
        "suggestedActions": null
      }
    }
  },
  {
    "apiVersion": "1",
    "sessionId": "d5f2390b-d610-4c5d-9b6c-7153f8a89fbf",
    "scenarioId": "CDG279-Api",
    "scenarioContractVersion": "cdg279.synthetic-disruption.v1",
    "state": "COMPLETE",
    "epochId": "74dab1c6-1d4b-4889-8dde-0b31986fed28",
    "revision": 3,
    "capabilities": {
      "canRead": true,
      "canStart": false,
      "canAdvance": false,
      "canReset": true,
      "canGenerateAssessment": false
    },
    "shipments": [
      {
        "scenarioShipmentKey": "A",
        "shipmentId": 142,
        "shipmentReference": "DEMO-A-PORTFOLIO",
        "scenarioShipmentReference": "DEMO-CDG279-Api-A"
      },
      {
        "scenarioShipmentKey": "B",
        "shipmentId": 143,
        "shipmentReference": "DEMO-B-PORTFOLIO",
        "scenarioShipmentReference": "DEMO-CDG279-Api-B"
      }
    ],
    "snapshot": {
      "snapshotId": "063052b2-fca1-4116-b559-f387ac6c2dc2",
      "epochId": "74dab1c6-1d4b-4889-8dde-0b31986fed28",
      "revision": 3,
      "stage": "WORSENED",
      "stageOrder": 2,
      "stageCount": 3,
      "fixtureStageId": "worsened-connection",
      "simulationCutoffUtc": "2026-10-01T08:00:00Z",
      "mapPlaybackCutoffUtc": "2026-10-01T08:00:00Z",
      "shipments": [
        {
          "scenarioShipmentKey": "A",
          "sourceShipmentId": 142,
          "scenarioShipmentReference": "DEMO-CDG279-Api-A",
          "transshipmentPort": "Synthetic Multi-source Port",
          "plannedMotherArrivalAt": "2026-10-01T06:00:00Z",
          "plannedFeederDepartureAt": "2026-10-02T12:00:00Z",
          "plannedConnectionWindow": {
            "status": "AVAILABLE",
            "window": {
              "duration": "1 day 6 hours",
              "totalSeconds": 108000
            }
          },
          "remainingConnectionWindow": {
            "status": "AVAILABLE",
            "duration": "4 hours",
            "totalSeconds": 14400,
            "missedBy": null,
            "missedBySeconds": null
          },
          "arrivalEstimate": {
            "estimatedArrivalAt": "2026-10-02T08:00:00Z",
            "limitation": "Approximate arrival — based on straight-line distance and current speed.",
            "speedOverGroundKnots": 1,
            "classification": "BEHIND_PLAN",
            "difference": "Behind plan · estimated 26h later than planned",
            "differenceSeconds": 93600,
            "differenceNanos": 0,
            "lastReceivedAt": "2026-10-01T08:00:00Z",
            "possiblyOutdated": false,
            "unavailableReason": null
          },
          "risk": {
            "level": "HIGH",
            "explanation": "Less than 12 hours remain to transfer cargo, based on the mother vessel's estimated arrival."
          },
          "businessImpact": {
            "outcome": "EXCEEDS_DEADLINE",
            "reason": null,
            "message": "The predicted arrival exceeds the recorded mother-vessel transshipment-arrival deadline.",
            "estimatedArrivalAt": "2026-10-02T08:00:00Z",
            "deadline": "2026-10-01T12:00:00Z",
            "recordedTransshipmentPort": "Synthetic Multi-source Port"
          }
        },
        {
          "scenarioShipmentKey": "B",
          "sourceShipmentId": 143,
          "scenarioShipmentReference": "DEMO-CDG279-Api-B",
          "transshipmentPort": "Synthetic Multi-source Port",
          "plannedMotherArrivalAt": "2026-10-01T06:00:00Z",
          "plannedFeederDepartureAt": "2026-10-02T12:00:00Z",
          "plannedConnectionWindow": {
            "status": "AVAILABLE",
            "window": {
              "duration": "1 day 6 hours",
              "totalSeconds": 108000
            }
          },
          "remainingConnectionWindow": {
            "status": "AVAILABLE",
            "duration": "4 hours",
            "totalSeconds": 14400,
            "missedBy": null,
            "missedBySeconds": null
          },
          "arrivalEstimate": {
            "estimatedArrivalAt": "2026-10-02T08:00:00Z",
            "limitation": "Approximate arrival — based on straight-line distance and current speed.",
            "speedOverGroundKnots": 1,
            "classification": "BEHIND_PLAN",
            "difference": "Behind plan · estimated 26h later than planned",
            "differenceSeconds": 93600,
            "differenceNanos": 0,
            "lastReceivedAt": "2026-10-01T08:00:00Z",
            "possiblyOutdated": false,
            "unavailableReason": null
          },
          "risk": {
            "level": "HIGH",
            "explanation": "Less than 12 hours remain to transfer cargo, based on the mother vessel's estimated arrival."
          },
          "businessImpact": {
            "outcome": "NO_PREDICTED_IMPACT",
            "reason": null,
            "message": "No predicted impact on the recorded mother-vessel transshipment-arrival deadline.",
            "estimatedArrivalAt": "2026-10-02T08:00:00Z",
            "deadline": "2026-10-02T10:00:00Z",
            "recordedTransshipmentPort": "Synthetic Multi-source Port"
          }
        }
      ],
      "aisObservations": [
        {
          "vesselRole": "MOTHER",
          "observedAtUtc": "2026-10-01T08:00:00Z",
          "receivedAtUtc": "2026-10-01T08:00:00Z",
          "sourceMode": "SYNTHETIC_DEMONSTRATION",
          "position": {
            "mmsi": "990279001",
            "vesselName": "CDG279 SYNTHETIC MOTHER Api",
            "latitude": 1.66,
            "longitude": 103.82,
            "speedOverGroundKnots": 1,
            "courseOverGroundDegrees": 180,
            "trueHeadingDegrees": 180,
            "navigationalStatus": 0,
            "positionValid": true,
            "aisUtcSecond": 0,
            "ingestedAt": "2026-10-01T08:00:00Z",
            "source": "SEEDED"
          }
        },
        {
          "vesselRole": "FEEDER",
          "observedAtUtc": "2026-10-01T08:00:00Z",
          "receivedAtUtc": "2026-10-01T08:00:00Z",
          "sourceMode": "SYNTHETIC_DEMONSTRATION",
          "position": {
            "mmsi": "990279002",
            "vesselName": "CDG279 SYNTHETIC FEEDER Api",
            "latitude": 1.26,
            "longitude": 103.82,
            "speedOverGroundKnots": 0,
            "courseOverGroundDegrees": null,
            "trueHeadingDegrees": null,
            "navigationalStatus": 5,
            "positionValid": true,
            "aisUtcSecond": 0,
            "ingestedAt": "2026-10-01T08:00:00Z",
            "source": "SEEDED"
          }
        }
      ],
      "maritimeContext": {
        "contractVersion": "cdg246.maritime-evidence-bundle.v1",
        "enabled": true,
        "sourceMode": "SYNTHETIC_DEMONSTRATION",
        "assessmentCutoffUtc": "2026-10-01T08:00:00Z",
        "shipmentAssociationConfidence": "VERIFIED",
        "limitation": "Human review is required. This bundle is a read-only replay/demo and does not change ETA, shipment, risk, alerts, or operational decisions.",
        "evidence": [
          {
            "sourceType": "MPA_ETA_REVISION",
            "sourceMode": "SYNTHETIC_DEMONSTRATION",
            "evidenceId": "cdg279-v1-revision-1",
            "title": "Synthetic expected-arrival revision",
            "sourceIdentifier": "CDG279-SYNTHETIC-MPA-REVISION-1",
            "sourceUrl": "https://example.invalid/cdg279/revision-1",
            "citation": "Synthetic MPA-style revision 1; the record is fictional and is not an authoritative vessel ETA.",
            "sourceEventAt": null,
            "observedAt": "2026-10-01T04:00:00Z",
            "retrievedAtUtc": "2026-10-01T04:00:00Z",
            "freshness": "FRESH",
            "previousValue": "2026-10-01T06:00:00Z",
            "currentValue": "2026-10-01T15:00:00Z",
            "changeMagnitude": null,
            "activityCompatibility": "COMPATIBLE",
            "locationCompatibility": "COMPATIBLE",
            "timeCompatibility": "COMPATIBLE",
            "compatibilityState": "COMPATIBLE",
            "evidenceSufficient": true,
            "humanReviewRequired": true,
            "shipmentAssociationConfidence": "VERIFIED",
            "limitation": "The revised expected-arrival value is fictional and deliberately differs from the AIS-derived estimate; it does not replace or change risk. Fictional verified association only: this fixture binds both shipment references to one assumed mother-to-feeder visit. It is not AIS evidence of cargo carriage, a real booking, or a real vessel identity.",
            "vesselIdentity": "CDG279 SYNTHETIC MOTHER Api",
            "vesselRole": "MOTHER",
            "observationType": "EXPECTED_ARRIVAL",
            "associationScope": "VESSEL_OBSERVATION"
          },
          {
            "sourceType": "MPA_OFFICIAL_NOTICE",
            "sourceMode": "SYNTHETIC_DEMONSTRATION",
            "evidenceId": "cdg279-v1-notice-2",
            "title": "Synthetic port-area notice",
            "sourceIdentifier": "CDG279-SYNTHETIC-PORT-NOTICE-2",
            "sourceUrl": "https://example.invalid/cdg279/notice-2",
            "citation": "Synthetic port-area notice; fictional effective window 2026-10-01T08:00:00Z through 2026-10-02T12:00:00Z.",
            "sourceEventAt": null,
            "observedAt": "2026-10-01T08:00:00Z",
            "retrievedAtUtc": "2026-10-01T08:00:00Z",
            "freshness": "FRESH",
            "previousValue": null,
            "currentValue": "Synthetic port-area notice effective from 2026-10-01T08:00:00Z through 2026-10-02T12:00:00Z.",
            "changeMagnitude": null,
            "activityCompatibility": "UNKNOWN",
            "locationCompatibility": "COMPATIBLE",
            "timeCompatibility": "COMPATIBLE",
            "compatibilityState": "COMPATIBLE",
            "evidenceSufficient": true,
            "humanReviewRequired": true,
            "shipmentAssociationConfidence": "VERIFIED",
            "limitation": "Fictional port-area context only. The effective window is not a publication time, and this notice does not establish vessel impact or causation. Fictional verified association only: this fixture binds both shipment references to one assumed mother-to-feeder visit. It is not AIS evidence of cargo carriage, a real booking, or a real vessel identity.",
            "vesselIdentity": null,
            "vesselRole": null,
            "observationType": null,
            "associationScope": "PORT_CONTEXT"
          },
          {
            "sourceType": "NEA_WEATHER",
            "sourceMode": "SYNTHETIC_DEMONSTRATION",
            "evidenceId": "cdg279-v1-weather-2",
            "title": "Synthetic regional weather context",
            "sourceIdentifier": "CDG279-SYNTHETIC-WEATHER-2",
            "sourceUrl": "https://example.invalid/cdg279/weather-2",
            "citation": "Synthetic regional weather context for the fictional port area; no quantitative weather value is asserted.",
            "sourceEventAt": null,
            "observedAt": "2026-10-01T08:00:00Z",
            "retrievedAtUtc": "2026-10-01T08:00:00Z",
            "freshness": "FRESH",
            "previousValue": null,
            "currentValue": "Synthetic regional context observed at the stage 2 cutoff.",
            "changeMagnitude": null,
            "activityCompatibility": "UNKNOWN",
            "locationCompatibility": "COMPATIBLE",
            "timeCompatibility": "COMPATIBLE",
            "compatibilityState": "COMPATIBLE",
            "evidenceSufficient": true,
            "humanReviewRequired": true,
            "shipmentAssociationConfidence": "VERIFIED",
            "limitation": "Fictional regional context only. It does not establish vessel-specific conditions or cause the slowdown. Fictional verified association only: this fixture binds both shipment references to one assumed mother-to-feeder visit. It is not AIS evidence of cargo carriage, a real booking, or a real vessel identity.",
            "vesselIdentity": null,
            "vesselRole": null,
            "observationType": null,
            "associationScope": "REGIONAL_CONTEXT"
          },
          {
            "sourceType": "MPA_OFFICIAL_NOTICE",
            "sourceMode": "SYNTHETIC_DEMONSTRATION",
            "evidenceId": "cdg279-v1-notice-other-area-2",
            "title": "Synthetic notice for a different area",
            "sourceIdentifier": "CDG279-SYNTHETIC-OTHER-AREA-NOTICE-2",
            "sourceUrl": "https://example.invalid/cdg279/notice-other-area-2",
            "citation": "Synthetic notice for a different fictional area; retained as an incompatibility control only.",
            "sourceEventAt": null,
            "observedAt": "2026-10-01T08:00:00Z",
            "retrievedAtUtc": "2026-10-01T08:00:00Z",
            "freshness": "FRESH",
            "previousValue": null,
            "currentValue": "Different Synthetic Area; fictional effective window 2026-10-01T08:00:00Z through 2026-10-02T12:00:00Z.",
            "changeMagnitude": null,
            "activityCompatibility": "INCOMPATIBLE",
            "locationCompatibility": "INCOMPATIBLE",
            "timeCompatibility": "COMPATIBLE",
            "compatibilityState": "INCOMPATIBLE",
            "evidenceSufficient": true,
            "humanReviewRequired": true,
            "shipmentAssociationConfidence": "VERIFIED",
            "limitation": "Fictional negative control. It is incompatible with this visit and must be excluded from inference. Fictional verified association only: this fixture binds both shipment references to one assumed mother-to-feeder visit. It is not AIS evidence of cargo carriage, a real booking, or a real vessel identity.",
            "vesselIdentity": null,
            "vesselRole": null,
            "observationType": null,
            "associationScope": "PORT_CONTEXT"
          },
          {
            "sourceType": "MPA_ETA_REVISION",
            "sourceMode": "SYNTHETIC_DEMONSTRATION",
            "evidenceId": "cdg279-v1-stale-negative-2",
            "title": "Synthetic stale expected-arrival control",
            "sourceIdentifier": "CDG279-SYNTHETIC-STALE-REVISION-2",
            "sourceUrl": "https://example.invalid/cdg279/stale-revision-2",
            "citation": "Synthetic old expected-arrival control retained to verify stale evidence exclusion.",
            "sourceEventAt": null,
            "observedAt": "2026-10-01T01:00:00Z",
            "retrievedAtUtc": "2026-10-01T08:00:00Z",
            "freshness": "STALE",
            "previousValue": "2026-10-01T06:00:00Z",
            "currentValue": "2026-10-01T15:00:00Z",
            "changeMagnitude": null,
            "activityCompatibility": "COMPATIBLE",
            "locationCompatibility": "COMPATIBLE",
            "timeCompatibility": "COMPATIBLE",
            "compatibilityState": "COMPATIBLE",
            "evidenceSufficient": true,
            "humanReviewRequired": true,
            "shipmentAssociationConfidence": "VERIFIED",
            "limitation": "Fictional stale negative control. Its observation is seven hours before the cutoff and it is excluded from inference. Fictional verified association only: this fixture binds both shipment references to one assumed mother-to-feeder visit. It is not AIS evidence of cargo carriage, a real booking, or a real vessel identity.",
            "vesselIdentity": "CDG279 SYNTHETIC MOTHER Api",
            "vesselRole": "MOTHER",
            "observationType": "EXPECTED_ARRIVAL",
            "associationScope": "VESSEL_OBSERVATION"
          },
          {
            "sourceType": "NEA_WEATHER",
            "sourceMode": "SYNTHETIC_DEMONSTRATION",
            "evidenceId": "cdg279-v1-unknown-negative-2",
            "title": "Synthetic weather with unknown observation time",
            "sourceIdentifier": "CDG279-SYNTHETIC-UNKNOWN-WEATHER-2",
            "sourceUrl": "https://example.invalid/cdg279/unknown-weather-2",
            "citation": "Synthetic regional weather control with no known observation time.",
            "sourceEventAt": null,
            "observedAt": null,
            "retrievedAtUtc": "2026-10-01T08:00:00Z",
            "freshness": "UNKNOWN",
            "previousValue": null,
            "currentValue": "Regional context only; observation time unknown.",
            "changeMagnitude": null,
            "activityCompatibility": "UNKNOWN",
            "locationCompatibility": "UNKNOWN",
            "timeCompatibility": "UNKNOWN",
            "compatibilityState": "UNKNOWN",
            "evidenceSufficient": false,
            "humanReviewRequired": true,
            "shipmentAssociationConfidence": "VERIFIED",
            "limitation": "Fictional unknown-freshness negative control; not eligible as assessment evidence. Fictional verified association only: this fixture binds both shipment references to one assumed mother-to-feeder visit. It is not AIS evidence of cargo carriage, a real booking, or a real vessel identity.",
            "vesselIdentity": null,
            "vesselRole": null,
            "observationType": null,
            "associationScope": "REGIONAL_CONTEXT"
          }
        ],
        "notices": [
          {
            "noticeId": "cdg279-v1-notice-2",
            "title": "Synthetic port-area notice",
            "officialSourceUrl": "https://example.invalid/cdg279/notice-2",
            "citation": "Synthetic port-area notice; fictional effective window 2026-10-01T08:00:00Z through 2026-10-02T12:00:00Z.",
            "publishedAt": null,
            "observedAt": "2026-10-01T08:00:00Z",
            "effectiveFrom": null,
            "effectiveUntil": null,
            "supersessionStatus": "UNKNOWN",
            "compatibilityState": "COMPATIBLE",
            "evidenceSufficient": true,
            "humanReviewRequired": true,
            "shipmentAssociationConfidence": "VERIFIED",
            "activityEvidence": [
              "UNKNOWN"
            ],
            "locationEvidence": [
              "COMPATIBLE"
            ],
            "limitation": "Fictional port-area context only. The effective window is not a publication time, and this notice does not establish vessel impact or causation. Fictional verified association only: this fixture binds both shipment references to one assumed mother-to-feeder visit. It is not AIS evidence of cargo carriage, a real booking, or a real vessel identity."
          },
          {
            "noticeId": "cdg279-v1-notice-other-area-2",
            "title": "Synthetic notice for a different area",
            "officialSourceUrl": "https://example.invalid/cdg279/notice-other-area-2",
            "citation": "Synthetic notice for a different fictional area; retained as an incompatibility control only.",
            "publishedAt": null,
            "observedAt": "2026-10-01T08:00:00Z",
            "effectiveFrom": null,
            "effectiveUntil": null,
            "supersessionStatus": "UNKNOWN",
            "compatibilityState": "INCOMPATIBLE",
            "evidenceSufficient": true,
            "humanReviewRequired": true,
            "shipmentAssociationConfidence": "VERIFIED",
            "activityEvidence": [
              "INCOMPATIBLE"
            ],
            "locationEvidence": [
              "INCOMPATIBLE"
            ],
            "limitation": "Fictional negative control. It is incompatible with this visit and must be excluded from inference. Fictional verified association only: this fixture binds both shipment references to one assumed mother-to-feeder visit. It is not AIS evidence of cargo carriage, a real booking, or a real vessel identity."
          }
        ],
        "aiExplanation": null,
        "suggestedActions": null
      }
    }
  }
];

const sessionId = 'd5f2390b-d610-4c5d-9b6c-7153f8a89fbf';
async function fixture(stage = 2): Promise<ScenarioSessionEnvelope> {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(backendPayloads[stage]), {
    headers: { 'Content-Type': 'application/json' },
  })));
  return getScenarioSession('token', sessionId);
}
function selection(envelope: ScenarioSessionEnvelope, key: 'A' | 'B' = 'A'): ScenarioDomainSelection {
  const binding = envelope.shipments.find(row => row.scenarioShipmentKey === key)!;
  return { sessionId: envelope.sessionId, epochId: envelope.epochId, revision: envelope.revision,
    snapshotId: envelope.snapshot!.snapshotId, shipmentId: binding.shipmentId, scenarioShipmentKey: key };
}
// Deliberately malformed test wires are mutable; production never casts around a decoder.
function mutate(envelope: ScenarioSessionEnvelope, change: (wire: any) => void): ScenarioSessionEnvelope {
  const copy = structuredClone(envelope);
  change(copy);
  return copy;
}
afterEach(() => vi.unstubAllGlobals());

describe('real serialized CDG-288 stages', () => {
  it.each([0, 1, 2])('decodes stage %i through the existing transport, for both A/B', async index => {
    const source = await fixture(index);
    for (const key of ['A', 'B'] as const) {
      const facts = decodeScenarioSnapshot(source, selection(source, key));
      expect(facts).toBeDefined();
      expect(facts?.stage).toBe(['BASELINE', 'CHANGE', 'WORSENED'][index]);
      expect(facts?.selectedShipment.scenarioShipmentKey).toBe(key);
      expect(facts?.selectedShipment.sourceShipmentId).toBe(key === 'A' ? 142 : 143);
      expect(facts?.selectedShipment.scenarioShipmentReference).toBe(`DEMO-CDG279-Api-${key}`);
      expect(facts?.shipments).toEqual(source.snapshot?.shipments);
      expect(facts?.maritimeContext).toEqual(source.snapshot?.maritimeContext);
      expect(facts?.aisObservations[0].position).toEqual(source.snapshot?.aisObservations[0].position);
      expect(facts?.aisObservations[0].usablePosition?.source).toBe('SEEDED');
    }
    expect(fetch).toHaveBeenCalledTimes(1); // The domain boundary makes no request.
  });
  it('preserves source ETA versus calculated ETA and the separate A/B fixture deadlines', async () => {
    const source = await fixture(1);
    const facts = decodeScenarioSnapshot(source, selection(source))!;
    expect(facts.selectedShipment.arrivalEstimate.estimatedArrivalAt).toBe('2026-10-01T16:00:00Z');
    expect(facts.maritimeContext.evidence[0].currentValue).toBe('2026-10-01T15:00:00Z');
    expect(facts.shipments.map(row => row.businessImpact.deadline)).toEqual(['2026-10-01T12:00:00Z', '2026-10-02T10:00:00Z']);
    expect(facts.shipments.map(row => row.businessImpact.outcome)).toEqual(['EXCEEDS_DEADLINE', 'NO_PREDICTED_IMPACT']);
    expect(facts.selectedBinding.shipmentReference).toBe('DEMO-A-PORTFOLIO');
    expect(facts.selectedBinding.shipmentReference).not.toBe(facts.selectedShipment.scenarioShipmentReference);
  });
  it('retains incompatible, stale and unknown evidence and both notices without filtering', async () => {
    const source = await fixture();
    const facts = decodeScenarioSnapshot(source, selection(source))!;
    expect(facts.maritimeContext.evidence).toHaveLength(6);
    expect(facts.maritimeContext.notices).toHaveLength(2);
    expect(facts.maritimeContext.evidence).toEqual(source.snapshot!.maritimeContext.evidence);
    expect(facts.maritimeContext.evidence.find(row => row.freshness === 'UNKNOWN')?.observedAt).toBeNull();
    expect(facts.maritimeContext.evidence.find(row => row.compatibilityState === 'INCOMPATIBLE')).toBeDefined();
  });
  it('preserves future source ETA values without treating them as future observations', async () => {
    const source = await fixture(1);
    const changed = mutate(source, wire => { wire.snapshot.maritimeContext.evidence[0].sourceEventAt = '2026-10-01T15:00:00Z'; });
    const facts = decodeScenarioSnapshot(changed, selection(source))!;
    expect(facts.maritimeContext.evidence[0].sourceEventAt).toBe('2026-10-01T15:00:00Z');
    expect(facts.maritimeContext.evidence[0].retrievedAtUtc).toBe('2026-10-01T04:00:00Z');
  });
  it('returns an immutable independent copy and does not freeze or mutate the coordinator envelope', async () => {
    const source = await fixture();
    const before = structuredClone(source);
    const facts = decodeScenarioSnapshot(source, selection(source))!;
    expect(facts).toBeDefined();
    expect(Object.isFrozen(facts)).toBe(true);
    expect(Object.isFrozen(facts.maritimeContext.evidence[0])).toBe(true);
    expect(Object.isFrozen(facts.shipments[0].businessImpact)).toBe(true);
    expect(Object.isFrozen(facts.aisObservations[0].position)).toBe(true);
    expect(Object.isFrozen(source.snapshot)).toBe(false);
    expect(source).toEqual(before);
    expect(facts.shipments).not.toBe(source.snapshot!.shipments);
  });
  it('selects by returned binding, not array position or reference suffix', async () => {
    const source = await fixture();
    const reordered = mutate(source, wire => { wire.shipments.reverse(); wire.snapshot.shipments.reverse(); wire.snapshot.aisObservations.reverse(); });
    expect(decodeScenarioSnapshot(reordered, selection(source, 'B'))?.selectedShipment.sourceShipmentId).toBe(143);
  });
});

describe('calculation variants and unavailable observations', () => {
  it.each(['INCOMPLETE', 'INVALID'] as const)('preserves planned %s and its null invariant', status => {
    expect(decodeScenarioPlannedWindow({ status, window: null })).toEqual({ status, window: null });
    expect(decodeScenarioPlannedWindow({ status, window: { duration: 'server value', totalSeconds: 9 } })).toBeUndefined();
  });
  it('preserves a nonstandard backend duration without computing a replacement', async () => {
    const source = await fixture();
    const changed = mutate(source, wire => {
      wire.snapshot.shipments[0].plannedConnectionWindow = { status: 'AVAILABLE', window: { duration: 'server duration', totalSeconds: 17 } };
      wire.snapshot.shipments[0].remainingConnectionWindow = { status: 'MISSED', duration: null, totalSeconds: null, missedBy: 'server overrun', missedBySeconds: 11 };
      wire.snapshot.shipments[0].risk = { level: 'LOW', explanation: 'Authoritative server wording.' };
    });
    const facts = decodeScenarioSnapshot(changed, selection(source))!;
    expect(facts.selectedShipment.plannedConnectionWindow.window?.totalSeconds).toBe(17);
    expect(facts.selectedShipment.remainingConnectionWindow.status).toBe('MISSED');
    expect(facts.selectedShipment.risk).toEqual({ level: 'LOW', explanation: 'Authoritative server wording.' });
  });
  it('accepts the backend unavailable-arrival DTO without deriving an ETA or missing fields', async () => {
    const source = await fixture();
    const changed = mutate(source, wire => {
      const row = wire.snapshot.shipments[0];
      for (const field of Object.keys(row.arrivalEstimate)) row.arrivalEstimate[field] = null;
      row.arrivalEstimate.unavailableReason = { code: 'UNUSABLE_SPEED', message: 'The mother vessel speed is unavailable.' };
      row.businessImpact.outcome = null;
      row.businessImpact.reason = 'MISSING_PREDICTION';
      row.businessImpact.estimatedArrivalAt = null;
    });
    const facts = decodeScenarioSnapshot(changed, selection(source))!;
    expect(facts.selectedShipment.arrivalEstimate.estimatedArrivalAt).toBeNull();
    expect(facts.selectedShipment.arrivalEstimate.unavailableReason?.code).toBe('UNUSABLE_SPEED');
    expect(facts.selectedShipment.businessImpact.reason).toBe('MISSING_PREDICTION');
  });
  it.each([false, null])('never promotes positionValid=%s into a usable map position', async positionValid => {
    const source = await fixture();
    const changed = mutate(source, wire => { wire.snapshot.aisObservations[0].position.positionValid = positionValid; });
    const facts = decodeScenarioSnapshot(changed, selection(source))!;
    expect(facts.aisObservations[0].position.positionValid).toBe(positionValid);
    expect(facts.aisObservations[0].usablePosition).toBeNull();
  });
  it('preserves nullable source readings and missing coordinates as explicitly unusable', async () => {
    const source = await fixture();
    const changed = mutate(source, wire => {
      const position = wire.snapshot.aisObservations[1].position;
      position.positionValid = false; position.latitude = null; position.longitude = null;
      position.vesselName = null; position.speedOverGroundKnots = null; position.navigationalStatus = null; position.aisUtcSecond = null;
    });
    const facts = decodeScenarioSnapshot(changed, selection(source))!;
    expect(facts.aisObservations[1].usablePosition).toBeNull();
    expect(facts.aisObservations[1].position.vesselName).toBeNull();
    expect(facts.aisObservations[1].position.speedOverGroundKnots).toBeNull();
    expect(facts.aisObservations[1].position).not.toHaveProperty('imo');
    expect(facts.aisObservations[1]).not.toHaveProperty('captureId');
    expect(facts.aisObservations[1]).not.toHaveProperty('trackId');
  });
  it('preserves original offset and nanosecond source strings without UTC rebasing', async () => {
    const source = await fixture();
    const changed = mutate(source, wire => {
      wire.snapshot.aisObservations[1].observedAtUtc = '2026-10-01T15:59:59.123456789+08:00';
      wire.snapshot.aisObservations[1].receivedAtUtc = '2026-10-01T07:59:59.987654321Z';
    });
    const facts = decodeScenarioSnapshot(changed, selection(source))!;
    expect(facts.aisObservations[1].observedAtUtc).toBe('2026-10-01T15:59:59.123456789+08:00');
    expect(facts.aisObservations[1].receivedAtUtc).toBe('2026-10-01T07:59:59.987654321Z');
  });
});

describe('whole-snapshot rejection', () => {
  const cases: [string, (wire: any) => void][] = [
    ['epoch mismatch', v => { v.snapshot.epochId = '4618e3c7-2006-4901-a211-01fbbaf8186c'; }],
    ['revision mismatch', v => { v.snapshot.revision = 2; }],
    ['stage order mismatch', v => { v.snapshot.stageOrder = 1; }],
    ['fixture stage mismatch', v => { v.snapshot.fixtureStageId = 'baseline'; }],
    ['map cutoff mismatch', v => { v.snapshot.mapPlaybackCutoffUtc = '2026-10-01T04:00:00Z'; }],
    ['assessment cutoff mismatch', v => { v.snapshot.maritimeContext.assessmentCutoffUtc = '2026-10-01T04:00:00Z'; }],
    ['unqualified cutoff', v => { v.snapshot.simulationCutoffUtc = '2026-10-01T08:00:00'; }],
    ['wrong source shipment', v => { v.snapshot.shipments[0].sourceShipmentId = 143; }],
    ['wrong row key', v => { v.snapshot.shipments[0].scenarioShipmentKey = 'B'; }],
    ['wrong fictional reference', v => { v.snapshot.shipments[0].scenarioShipmentReference = 'DEMO-A-PORTFOLIO'; }],
    ['wrong binding ID', v => { v.shipments[0].shipmentId = 99; }],
    ['duplicate binding', v => { v.shipments[1] = v.shipments[0]; }],
    ['missing B row', v => { v.snapshot.shipments.pop(); }],
    ['duplicate vessel role', v => { v.snapshot.aisObservations[1].vesselRole = 'MOTHER'; }],
    ['missing vessel role', v => { v.snapshot.aisObservations.pop(); }],
    ['same vessel identity for both roles', v => { v.snapshot.aisObservations[1].position.mmsi = v.snapshot.aisObservations[0].position.mmsi; }],
    ['null risk', v => { v.snapshot.shipments[1].risk = null; }],
    ['unknown risk level', v => { v.snapshot.shipments[1].risk.level = 'SAFE'; }],
    ['empty risk explanation', v => { v.snapshot.shipments[1].risk.explanation = ''; }],
    ['missing arrival', v => { delete v.snapshot.shipments[1].arrivalEstimate; }],
    ['nonfinite arrival speed', v => { v.snapshot.shipments[1].arrivalEstimate.speedOverGroundKnots = NaN; }],
    ['invalid arrival instant', v => { v.snapshot.shipments[1].arrivalEstimate.estimatedArrivalAt = '2026-02-30T00:00:00Z'; }],
    ['mismatched estimate source time', v => { v.snapshot.shipments[1].arrivalEstimate.lastReceivedAt = '2026-10-01T04:00:00Z'; }],
    ['mismatched estimate speed', v => { v.snapshot.shipments[1].arrivalEstimate.speedOverGroundKnots = 3; }],
    ['mismatched impact estimate', v => { v.snapshot.shipments[1].businessImpact.estimatedArrivalAt = '2026-10-02T06:00:00Z'; }],
    ['inconsistent planned null', v => { v.snapshot.shipments[1].plannedConnectionWindow.window = null; }],
    ['null remaining window', v => { v.snapshot.shipments[1].remainingConnectionWindow = null; }],
    ['missing null overrun field', v => { delete v.snapshot.shipments[1].remainingConnectionWindow.missedBy; }],
    ['both remaining and missed', v => { v.snapshot.shipments[1].remainingConnectionWindow.missedBySeconds = 1; }],
    ['inconsistent shared port', v => { v.snapshot.shipments[1].transshipmentPort = 'Different visit'; }],
    ['inconsistent shared schedule', v => { v.snapshot.shipments[1].plannedFeederDepartureAt = '2026-10-03T12:00:00Z'; }],
    ['invalid position flag', v => { v.snapshot.aisObservations[0].position.positionValid = 'true'; }],
    ['missing position flag', v => { delete v.snapshot.aisObservations[0].position.positionValid; }],
    ['out of range latitude', v => { v.snapshot.aisObservations[0].position.latitude = 91; }],
    ['out of range longitude', v => { v.snapshot.aisObservations[0].position.longitude = -181; }],
    ['nonfinite latitude', v => { v.snapshot.aisObservations[0].position.latitude = Infinity; }],
    ['nonfinite speed', v => { v.snapshot.aisObservations[0].position.speedOverGroundKnots = NaN; }],
    ['negative speed', v => { v.snapshot.aisObservations[0].position.speedOverGroundKnots = -1; }],
    ['unconverted speed sentinel', v => { v.snapshot.aisObservations[0].position.speedOverGroundKnots = 102.3; }],
    ['invalid course', v => { v.snapshot.aisObservations[0].position.courseOverGroundDegrees = 360; }],
    ['nonfinite course', v => { v.snapshot.aisObservations[0].position.courseOverGroundDegrees = Infinity; }],
    ['invalid heading', v => { v.snapshot.aisObservations[0].position.trueHeadingDegrees = 511; }],
    ['fractional heading', v => { v.snapshot.aisObservations[0].position.trueHeadingDegrees = 1.5; }],
    ['invalid navigation status', v => { v.snapshot.aisObservations[0].position.navigationalStatus = 16; }],
    ['invalid AIS second', v => { v.snapshot.aisObservations[0].position.aisUtcSecond = 60; }],
    ['missing numeric reading', v => { delete v.snapshot.aisObservations[0].position.speedOverGroundKnots; }],
    ['bad MMSI', v => { v.snapshot.aisObservations[0].position.mmsi = 'real vessel'; }],
    ['missing identity', v => { delete v.snapshot.aisObservations[0].position.mmsi; }],
    ['invalid name', v => { v.snapshot.aisObservations[0].position.vesselName = 7; }],
    ['forged live source', v => { v.snapshot.aisObservations[0].position.source = 'AIS_STREAM'; }],
    ['forged recorded mode', v => { v.snapshot.aisObservations[0].sourceMode = 'RECORDED_REPLAY'; }],
    ['future observed instant', v => { v.snapshot.aisObservations[0].observedAtUtc = '2026-10-01T08:00:00.000000001Z'; }],
    ['future received instant', v => { v.snapshot.aisObservations[0].receivedAtUtc = '2026-10-01T08:00:00.000000001Z'; }],
    ['future ingested instant', v => { v.snapshot.aisObservations[0].position.ingestedAt = '2026-10-01T08:00:00.000000001Z'; }],
    ['reversed observation chronology', v => { v.snapshot.aisObservations[0].receivedAtUtc = '2026-10-01T07:59:59Z'; }],
    ['malformed nested evidence', v => { v.snapshot.maritimeContext.evidence[0].evidenceSufficient = 'yes'; }],
    ['missing evidence identity', v => { delete v.snapshot.maritimeContext.evidence[0].evidenceId; }],
    ['missing evidence timestamp', v => { delete v.snapshot.maritimeContext.evidence[0].retrievedAtUtc; }],
    ['future evidence observation', v => { v.snapshot.maritimeContext.evidence[0].observedAt = '2026-10-01T08:00:00.000000001Z'; }],
    ['future evidence retrieval', v => { v.snapshot.maritimeContext.evidence[0].retrievedAtUtc = '2026-10-01T08:00:00.000000001Z'; }],
    ['forged evidence source mode', v => { v.snapshot.maritimeContext.evidence[0].sourceMode = 'LIVE'; }],
    ['forged bundle mode', v => { v.snapshot.maritimeContext.sourceMode = 'RECORDED_REPLAY'; }],
    ['forged bundle association', v => { v.snapshot.maritimeContext.shipmentAssociationConfidence = 'UNVERIFIED_IMO_ONLY'; }],
    ['wrong evidence vessel', v => { v.snapshot.maritimeContext.evidence[0].vesselIdentity = 'Unrelated vessel'; }],
    ['wrong evidence role', v => { v.snapshot.maritimeContext.evidence[0].vesselRole = 'FEEDER'; }],
    ['forged evidence review flag', v => { v.snapshot.maritimeContext.evidence[0].humanReviewRequired = false; }],
    ['duplicate evidence identity', v => { v.snapshot.maritimeContext.evidence[1].evidenceId = v.snapshot.maritimeContext.evidence[0].evidenceId; }],
    ['malformed notice', v => { v.snapshot.maritimeContext.notices[0].activityEvidence = ['VALID', 7]; }],
    ['wrong notice identity', v => { v.snapshot.maritimeContext.notices[0].noticeId = 'another-row'; }],
    ['missing notice', v => { v.snapshot.maritimeContext.notices.pop(); }],
    ['forged effective window', v => { v.snapshot.maritimeContext.notices[0].effectiveFrom = '2026-10-01T08:00:00Z'; }],
    ['forged activity compatibility', v => { v.snapshot.maritimeContext.evidence[0].activityCompatibility = 'CONFIRMED'; }],
    ['forged notice compatibility', v => { v.snapshot.maritimeContext.notices[0].compatibilityState = 'INCOMPATIBLE'; }],
    ['forged notice time', v => { v.snapshot.maritimeContext.notices[0].observedAt = '2026-10-01T09:00:00Z'; }],
    ['unsafe evidence URL', v => { v.snapshot.maritimeContext.evidence[0].sourceUrl = 'javascript:alert(1)'; }],
    ['unexpected AI explanation', v => { v.snapshot.maritimeContext.aiExplanation = 'canned output'; }],
  ];
  it.each(cases)('rejects the entire snapshot for %s', async (_, change) => {
    const source = await fixture();
    expect(decodeScenarioSnapshot(mutate(source, change), selection(source))).toBeUndefined();
    expect(fetch).toHaveBeenCalledTimes(1); // No live-value lookup or assessment fallback.
  });
  it.each(['sessionId', 'epochId', 'snapshotId', 'revision', 'shipmentId', 'scenarioShipmentKey'] as const)(
    'rejects the wrong selected %s', async field => {
      const source = await fixture();
      const expected = { ...selection(source) };
      if (field === 'revision' || field === 'shipmentId') expected[field] = 99;
      else if (field === 'scenarioShipmentKey') expected[field] = 'B';
      else expected[field] = '4618e3c7-2006-4901-a211-01fbbaf8186c';
      expect(decodeScenarioSnapshot(source, expected)).toBeUndefined();
    });
  it('returns no domain facts for UNSTARTED or read denial', async () => {
    const source = await fixture();
    expect(decodeScenarioSnapshot({ ...source, state: 'UNSTARTED', revision: 0, snapshot: null }, selection(source))).toBeUndefined();
    expect(decodeScenarioSnapshot({ ...source, capabilities: { ...source.capabilities, canRead: false } }, selection(source))).toBeUndefined();
  });
  it('does not turn a missing/null observation or malformed planned object into defaults', () => {
    expect(decodeScenarioObservation(null, '2026-10-01T00:00:00Z')).toBeUndefined();
    expect(decodeScenarioObservation({}, '2026-10-01T00:00:00Z')).toBeUndefined();
    expect(decodeScenarioPlannedWindow({ status: 'AVAILABLE', window: null })).toBeUndefined();
    expect(decodeScenarioPlannedWindow({ status: 'AVAILABLE', window: { duration: 'x', totalSeconds: -1 } })).toBeUndefined();
    expect(decodeScenarioPlannedWindow({ status: 'AVAILABLE', window: { duration: 'x', totalSeconds: NaN } })).toBeUndefined();
  });
});
