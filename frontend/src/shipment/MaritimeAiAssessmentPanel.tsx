import { type MaritimeAiAssessment } from './api';

export function MaritimeAiAssessmentPanel({ assessments, unavailable }: { assessments: MaritimeAiAssessment[]; unavailable: boolean }) {
  if (unavailable) return <section className="notice" aria-label="AI maritime interpretation">AI maritime interpretation is unavailable. Maritime evidence remains available.</section>;
  if (assessments.length === 0) return <section className="notice" aria-label="AI maritime interpretation">No AI maritime interpretation has been generated for this shipment.</section>;
  const assessment = assessments[0];
  return <section className="shipment-record maritime-context" aria-label="AI maritime interpretation">
    <p className="eyebrow">AI MARITIME INTERPRETATION</p>
    <p><strong>State:</strong> {assessment.state} · validation {assessment.validationStatus} · model {assessment.provider || 'not configured'} {assessment.model}</p>
    {assessment.state === 'GENERATED' && assessment.interpretation ? <>
      <p>{assessment.interpretation.summary}</p>
      {assessment.interpretation.claims.map((claim, index) => <p key={index}><strong>Claim:</strong> {claim.statement} <span className="refresh-note">Evidence: {claim.evidenceIds.join(', ')} · {claim.applicability}</span></p>)}
      {assessment.interpretation.uncertainties.map((uncertainty, index) => <p key={`u-${index}`} className="refresh-note">Uncertainty: {uncertainty}</p>)}
      {assessment.interpretation.followUpConsiderations.map((item, index) => <p key={`f-${index}`}>Non-binding follow-up: {item}</p>)}
    </> : <p>{assessment.limitation ?? 'The model did not produce a validated interpretation.'}</p>}
    <p className="refresh-note">This is AI-generated advisory context, not the CDG-48 recommendation, CDG-52 decision, shipment risk, ETA, or an operational action.</p>
  </section>;
}
