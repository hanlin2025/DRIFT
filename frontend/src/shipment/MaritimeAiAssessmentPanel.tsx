import {
  type MaritimeAiAssessment,
  type MaritimeAiBusinessSnapshot,
  type MaritimeAiComparisonUnavailableReason,
  type MaritimeAiDeadlineState,
  type MaritimeAiClaim,
  type MaritimeAiEstimateFreshness,
  type MaritimeAiFact,
  type MaritimeAiRiskBasis,
  type MaritimeAiRiskReviewWindowState,
  type MaritimeAiRiskSnapshot,
  type MaritimeAiRiskSnapshotWindowState,
} from './api';

function humanizeCode(value: string | null | undefined) {
  if (!value) return 'Not recorded';
  return value.toLowerCase().split('_').map(part => part ? part[0].toUpperCase() + part.slice(1) : part).join(' ');
}

function formatScheduleFact(fact: NonNullable<MaritimeAiAssessment['deterministicSummary']>['shipmentScheduleFacts'][number]) {
  return [fact.subject, humanizeCode(fact.eventType), fact.timestamp].filter(Boolean).join(' · ') || 'Schedule information is not available.';
}

function formatDuration(seconds: number | null | undefined) {
  if (seconds == null || !Number.isSafeInteger(seconds) || seconds < 0) return null;
  if (seconds === 0) return '0 seconds';

  const units: [string, number][] = [['day', 86_400], ['hour', 3_600], ['minute', 60], ['second', 1]];
  let remaining = seconds;
  const parts: string[] = [];
  for (const [unit, unitSeconds] of units) {
    const amount = Math.floor(remaining / unitSeconds);
    if (amount > 0) parts.push(`${amount} ${unit}${amount === 1 ? '' : 's'}`);
    remaining %= unitSeconds;
  }
  return parts.join(' ');
}

function basisLabel(basis: MaritimeAiRiskBasis | null | undefined) {
  switch (basis) {
    case 'PLANNED_SHIPMENT_SCHEDULE': return 'Planned shipment schedule';
    case 'AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE': return 'AIS-estimated remaining connection window';
    default: return 'Basis not recorded';
  }
}

function windowStateLabel(state: MaritimeAiRiskSnapshotWindowState | MaritimeAiRiskReviewWindowState | null | undefined) {
  switch (state) {
    case 'AVAILABLE': return 'Available';
    case 'MISSED':
    case 'NO_REMAINING_WINDOW': return 'No positive remaining window (calculated)';
    case 'INVALID': return 'Invalid window';
    case 'INCOMPLETE': return 'Incomplete window information';
    default: return 'Window state not recorded';
  }
}

function freshnessLabel(state: MaritimeAiEstimateFreshness | null | undefined) {
  switch (state) {
    case 'AVAILABLE': return 'Available';
    case 'STALE': return 'Stale';
    case 'UNAVAILABLE': return 'Unavailable';
    case 'UNKNOWN': return 'Unknown';
    default: return 'Not recorded';
  }
}

function relationshipLabel(claim: MaritimeAiClaim) {
  switch (claim.relationshipType) {
    case 'SCHEDULE_REVIEW': return 'Schedule review';
    case 'PORT_CONTEXT_REVIEW': return 'Port context';
    case 'PORT_TIME_OVERLAP_REVIEW': return 'Port timing context';
    case 'REGIONAL_WEATHER_CONTEXT': return 'Regional weather context';
    case 'RISK_BUFFER_REVIEW': return 'Connection risk review';
    case 'DELAY_COMPARISON_REVIEW': return 'Importer deadline comparison';
    default: return 'Unclassified relationship';
  }
}

function deadlineStateLabel(state: MaritimeAiDeadlineState | null | undefined) {
  switch (state) {
    case 'RECORDED': return 'Recorded';
    case 'NOT_RECORDED': return 'Not recorded';
    case 'UNKNOWN': return 'Unknown';
    default: return 'Not available';
  }
}

function deadlineInstantLabel(state: MaritimeAiDeadlineState | null | undefined, instant: string | null | undefined) {
  if (state === 'NOT_RECORDED') return 'No deadline recorded at assessment time';
  if (state === 'UNKNOWN') return 'Unknown at assessment time';
  return instant || (state === 'RECORDED' ? 'Recorded deadline time not available' : 'Not recorded');
}

function deadlineApplicabilityLabel(applicable: boolean | null | undefined) {
  if (applicable === true) return 'Applies to this shipment’s transshipment port';
  if (applicable === false) return 'Does not apply to this shipment’s transshipment port';
  return 'Applicability not recorded';
}

function comparisonUnavailableReasonLabel(reason: MaritimeAiComparisonUnavailableReason | null | undefined) {
  return reason ? humanizeCode(reason) : 'Reason not recorded';
}

function canShowBusinessClaims(snapshot: MaritimeAiBusinessSnapshot | null | undefined) {
  const comparison = snapshot?.context?.comparison;
  return comparison != null && comparison.state !== 'UNKNOWN';
}

function sourceLabel(sourceType: string | null | undefined) {
  switch (sourceType) {
    case 'MPA_ETA_REVISION': return 'MPA vessel-arrival revision';
    case 'MPA_OFFICIAL_NOTICE': return 'MPA notice';
    case 'NEA_WEATHER': return 'NEA weather context';
    default: return humanizeCode(sourceType);
  }
}

function sourceModeLabel(sourceMode: string | null | undefined) {
  switch (sourceMode) {
    case 'LIVE': return 'Live source';
    case 'RECORDED_REPLAY': return 'Recorded replay';
    case 'RETROSPECTIVE_ANALYSIS': return 'Retrospective analysis';
    case 'SYNTHETIC_DEMONSTRATION': return 'Synthetic demonstration';
    case 'UNAVAILABLE': return 'Unavailable source';
    default: return humanizeCode(sourceMode);
  }
}

function sourceValue(fact: MaritimeAiFact) {
  if (fact.previousValue && fact.currentValue) return `${fact.previousValue} → ${fact.currentValue}`;
  if (fact.currentValue) return fact.currentValue;
  if (fact.previousValue) return fact.previousValue;
  return 'No source value was saved.';
}

function isVerifiedCaptureReplay(assessment: MaritimeAiAssessment) {
  return assessment.state === 'GENERATED' && assessment.validationStatus === 'VALID'
    && assessment.provider === 'openai-responses (verified capture replay)';
}

function isControlledStub(assessment: MaritimeAiAssessment) {
  return (assessment.provider ?? '').toLowerCase().includes('stub');
}

function hasProviderGeneratedInterpretation(assessment: MaritimeAiAssessment) {
  const provider = (assessment.provider ?? '').toLowerCase();
  return assessment.state === 'GENERATED' && assessment.validationStatus === 'VALID'
    && provider !== '' && provider !== 'unconfigured' && !isVerifiedCaptureReplay(assessment) && !isControlledStub(assessment);
}

function assessmentStateLabel(state: MaritimeAiAssessment['state']) {
  switch (state) {
    case 'GENERATED': return 'Generated';
    case 'ABSTAINED': return 'Abstained';
    case 'FAILED': return 'Failed';
  }
}

function isValidatedInterpretation(assessment: MaritimeAiAssessment) {
  return assessment.state === 'GENERATED' && assessment.validationStatus === 'VALID' && assessment.interpretation != null;
}

function selectedDuration(risk: MaritimeAiRiskSnapshot | null | undefined) {
  if (!risk || risk.selectedWindowState !== 'AVAILABLE') return null;
  return risk.windowSeconds;
}

function selectedOverrun(risk: MaritimeAiRiskSnapshot | null | undefined) {
  if (!risk || risk.selectedWindowState !== 'MISSED') return null;
  return risk.missedBySeconds;
}

function RiskFacts({ assessment, snapshot, riskReview }: {
  assessment: MaritimeAiAssessment;
  snapshot: MaritimeAiRiskSnapshot | null;
  riskReview: NonNullable<MaritimeAiClaim['riskReview']> | null;
}) {
  const severity = snapshot?.severity ?? riskReview?.severity;
  const basis = snapshot?.selectedWindowBasis ?? riskReview?.selectedWindowBasis;
  const windowState = snapshot?.selectedWindowState ?? riskReview?.selectedWindowState;
  const estimateFreshness = snapshot?.estimateFreshness ?? riskReview?.estimateFreshness;
  const windowDuration = formatDuration(selectedDuration(snapshot));
  const overrun = formatDuration(selectedOverrun(snapshot));
  const limitations = snapshot?.calculationLimitations ?? [];

  return <section className="maritime-ai-risk" aria-label="Assessment-time connection risk">
    <h3>Why this shipment warrants review</h3>
    <p className="maritime-ai-assessment-time">Risk at assessment time{assessment.generatedAtUtc ? ` · ${assessment.generatedAtUtc}` : ''}. This snapshot does not describe the shipment's current operational state.</p>
    <dl>
      <div><dt>Backend risk severity</dt><dd>{severity ? humanizeCode(severity) : 'Not recorded'}</dd></div>
      <div><dt>Risk basis</dt><dd>{basisLabel(basis)}</dd></div>
      <div><dt>Selected window state</dt><dd>{windowStateLabel(windowState)}</dd></div>
      {(windowDuration || (basis === 'PLANNED_SHIPMENT_SCHEDULE' && snapshot?.windowSeconds === 0)) && <div><dt>Selected connection window</dt><dd>{windowDuration}</dd></div>}
      {overrun && <div><dt>Calculated overrun</dt><dd>{overrun}</dd></div>}
      <div><dt>AIS estimate freshness</dt><dd>{freshnessLabel(estimateFreshness)}</dd></div>
    </dl>
    {overrun && <p className="maritime-ai-note">The overrun is a backend window calculation, not confirmation that a connection was missed.</p>}
    {snapshot && !snapshot.assessed && <p className="maritime-ai-note">The saved backend snapshot records that connection risk was not assessed.</p>}
    {snapshot == null && <p className="maritime-ai-note">This older assessment has no persisted numeric window snapshot; no current shipment values are substituted.</p>}
    {limitations.length > 0 && <section aria-label="Risk calculation limitations">
      <p><strong>Calculation limitations at assessment time</strong></p>
      <ul>{limitations.map((limitation, index) => <li key={`risk-limitation-${index}`}>{limitation}</li>)}</ul>
    </section>}
    {snapshot?.unavailableReason && <p className="maritime-ai-note">Risk assessment status: {snapshot.unavailableReason}</p>}
  </section>;
}

function BusinessContextDetails({ snapshot }: { snapshot: MaritimeAiBusinessSnapshot }) {
  const deadline = snapshot.context?.deadline;
  const comparison = snapshot.context?.comparison;

  return <section aria-label="Assessment-time importer business context">
    <h4>Assessment-time importer business context</h4>
    <dl>
      <div><dt>Deadline state</dt><dd>{deadlineStateLabel(deadline?.state)}</dd></div>
      <div><dt>Transshipment-arrival deadline at assessment time</dt><dd>{deadlineInstantLabel(deadline?.state, deadline?.instant)}</dd></div>
      <div><dt>Importer-confirmed transshipment port</dt><dd>{deadline?.recordedTransshipmentPort || 'Not recorded'}</dd></div>
      <div><dt>Deadline applicability</dt><dd>{deadlineApplicabilityLabel(deadline?.applicableToCurrentPort)}</dd></div>
      <div><dt>Comparison state</dt><dd>{comparison ? humanizeCode(comparison.state) : 'Unknown'}</dd></div>
      {comparison?.state === 'AVAILABLE' && <div><dt>Comparison outcome</dt><dd>{comparison.outcome ? humanizeCode(comparison.outcome) : 'Outcome not recorded'}</dd></div>}
      {comparison?.state === 'UNAVAILABLE' && <div><dt>Comparison unavailable reason</dt><dd>{comparisonUnavailableReasonLabel(comparison.unavailableReason)}</dd></div>}
      {comparison?.state === 'UNKNOWN' && <div><dt>Comparison outcome</dt><dd>Unknown at assessment time</dd></div>}
      {comparison?.estimatedArrivalAt && <div><dt>Arrival estimate used or retained</dt><dd>{comparison.estimatedArrivalAt}</dd></div>}
      {comparison?.comparedDeadline && <div><dt>Deadline used for comparison</dt><dd>{comparison.comparedDeadline}</dd></div>}
      {comparison?.recordedTransshipmentPort && <div><dt>Comparison transshipment port</dt><dd>{comparison.recordedTransshipmentPort}</dd></div>}
    </dl>
    <p><strong>Canonical business references:</strong> {snapshot.canonicalFactReferences.length ? snapshot.canonicalFactReferences.join(', ') : 'none recorded'}.</p>
    <p className="maritime-ai-note">These are the facts saved with this assessment; the shipment’s current deadline is not substituted here.</p>
  </section>;
}

export function MaritimeAiAssessmentPanel({ assessments, unavailable, generating, onGenerate }: {
  assessments: MaritimeAiAssessment[];
  unavailable: boolean;
  generating: boolean;
  onGenerate: () => void;
}) {
  if (unavailable) return <section className="notice" aria-label="AI maritime interpretation">AI maritime interpretation is unavailable. Maritime evidence remains available.</section>;
  if (assessments.length === 0) return <section className="notice" aria-label="AI maritime interpretation">
    <p>No AI maritime interpretation has been generated for this shipment.</p>
    <button type="button" onClick={onGenerate} disabled={generating}>{generating ? 'Generating advisory…' : 'Generate AI maritime interpretation'}</button>
  </section>;

  const assessment = assessments[0];
  const validated = isValidatedInterpretation(assessment);
  const claims = validated ? assessment.interpretation?.claims ?? [] : [];
  const riskClaims = claims.filter(claim => claim.relationshipType === 'RISK_BUFFER_REVIEW');
  const businessSnapshot = assessment.deterministicSummary?.businessContext ?? null;
  const businessClaims = canShowBusinessClaims(businessSnapshot)
    ? claims.filter(claim => claim.relationshipType === 'DELAY_COMPARISON_REVIEW')
    : [];
  const evidenceClaims = claims.filter(claim => claim.relationshipType !== 'RISK_BUFFER_REVIEW' && claim.relationshipType !== 'DELAY_COMPARISON_REVIEW');
  const riskSnapshot = assessment.deterministicSummary?.connectionRisk ?? null;
  const riskReview = riskClaims.find(claim => claim.riskReview)?.riskReview ?? null;
  const snapshotContradictsRiskClaim = riskClaims.length > 0 && riskSnapshot?.assessed === false;
  const showRiskReview = riskClaims.length > 0 && !snapshotContradictsRiskClaim;
  const citedEvidenceIds = new Set(claims.flatMap(claim => claim.evidenceIds ?? []));
  const evidenceFacts = (assessment.deterministicSummary?.evidenceFacts ?? []).filter(fact => fact.evidenceId && citedEvidenceIds.has(fact.evidenceId));
  const scheduleFacts = assessment.deterministicSummary?.shipmentScheduleFacts ?? [];
  const interpretation = validated ? assessment.interpretation : null;
  const uncertainties = interpretation?.uncertainties ?? [];
  const followUpConsiderations = interpretation?.followUpConsiderations ?? [];

  return <section className="shipment-record maritime-context maritime-ai-panel" aria-label="AI maritime interpretation">
    <p className="eyebrow">MARITIME ASSESSMENT</p>
    <p className="maritime-ai-status"><strong>Assessment status</strong> {assessmentStateLabel(assessment.state)} · {assessment.validationStatus === 'VALID' ? 'validated' : humanizeCode(assessment.validationStatus)} · assessment time {assessment.generatedAtUtc ?? 'not recorded'}</p>
    {isVerifiedCaptureReplay(assessment) && <p className="notice">Verified OpenAI provider-capture replay. No provider request was made when this assessment was saved.</p>}
    {isControlledStub(assessment) && <p className="notice">Controlled synthetic stub assessment. It uses fictional evidence and is not an external provider response.</p>}
    {hasProviderGeneratedInterpretation(assessment) && <p className="success-notice maritime-ai-provider-label">Provider-generated assessment from {assessment.provider} at the recorded assessment time.</p>}

    {interpretation ? <>
      {evidenceFacts.length > 0 || evidenceClaims.length > 0 ? <section className="maritime-ai-evidence" aria-label="Observed maritime evidence">
        <h3>Observed maritime evidence</h3>
        {evidenceFacts.map((fact, index) => <article className="maritime-ai-observation" key={`observation-${fact.evidenceId ?? index}`}>
          <p><strong>{sourceLabel(fact.sourceType)}</strong>{fact.eventType ? ` · ${humanizeCode(fact.eventType)}` : ''}</p>
          <p>{sourceValue(fact)}</p>
        </article>)}
        {evidenceClaims.length > 0 && <div className="maritime-ai-claims" aria-label="Evidence interpretation">
          <p><strong>Evidence interpretation</strong></p>
          <ul>{evidenceClaims.map((claim, index) => <li key={`evidence-claim-${index}`}>{claim.statement}</li>)}</ul>
        </div>}
      </section> : <section className="maritime-ai-evidence" aria-label="Observed maritime evidence">
        <h3>Observed maritime evidence</h3>
        <p>No cited source observations were saved with this assessment.</p>
      </section>}

      {showRiskReview ? <>
        <RiskFacts assessment={assessment} snapshot={riskSnapshot} riskReview={riskReview} />
        <section className="maritime-ai-implications" aria-label="Tentative risk-review implications">
          <p><strong>Tentative operational implication</strong></p>
          <ul>{riskClaims.map((claim, index) => <li key={`risk-claim-${index}`}>{claim.statement}</li>)}</ul>
          <p className="maritime-ai-note">The maritime observations and backend risk are separate facts. This review does not confirm a delay or missed connection.</p>
        </section>
      </> : riskClaims.length > 0 ? <p className="notice">The saved risk snapshot says risk was not assessed, so the risk-review claim is not displayed.</p> : businessClaims.length === 0 ? <p className="maritime-ai-note">This assessment contains evidence interpretation without a validated connection risk-review claim.</p> : null}

      {businessClaims.length > 0 && <section className="maritime-ai-implications maritime-ai-business-implications" aria-label="Business implications">
        <h3>Business implications</h3>
        <p><strong>Validated importer deadline comparison</strong></p>
        <ul>{businessClaims.map((claim, index) => <li key={`business-claim-${index}`}>{claim.statement}</li>)}</ul>
        <p className="maritime-ai-note">This is a backend comparison against the importer’s saved transshipment-arrival deadline at assessment time. It is not a final-delivery or cargo-outcome assessment.</p>
      </section>}

      <section className="maritime-ai-uncertainty" aria-label="Assessment uncertainty and human follow-up">
        <div><h3>Uncertainty</h3>
          {uncertainties.length ? <ul>{uncertainties.map((item, index) => <li key={`uncertainty-${index}`}>{item}</li>)}</ul> : <p>No additional uncertainty was recorded.</p>}
        </div>
        <div><h3>Human verification</h3>
          {followUpConsiderations.length ? <ul>{followUpConsiderations.map((item, index) => <li key={`follow-up-${index}`}>{item}</li>)}</ul> : <p>No human verification steps were recorded.</p>}
        </div>
      </section>
    </> : <section className="maritime-ai-unavailable" aria-label="No validated interpretation">
      <h3>{assessment.state === 'ABSTAINED' ? 'No validated interpretation was produced' : 'Validated interpretation unavailable'}</h3>
      <p>{assessment.limitation ?? 'This assessment did not produce an interpretation that is safe to display.'}</p>
    </section>}

    {scheduleFacts.length > 0 && <section className="maritime-ai-schedule" aria-label="Stored shipment schedule at assessment time">
      <h3>Stored shipment schedule at assessment time</h3>
      <ul>{scheduleFacts.map((fact, index) => <li key={`schedule-${fact.factReference}-${index}`}>{formatScheduleFact(fact)}</li>)}</ul>
    </section>}

    <details className="maritime-ai-details">
      <summary>Assessment and evidence details</summary>
      <p><strong>Provider:</strong> {assessment.provider || 'not configured'} · <strong>model:</strong> {assessment.model || 'not recorded'} · <strong>prompt:</strong> {assessment.promptVersion || 'not recorded'}.</p>
      <p><strong>Assessment cutoff:</strong> {assessment.assessmentCutoffUtc || 'not recorded'} · <strong>bundle:</strong> {assessment.bundleVersion || 'not recorded'} · <strong>hash:</strong> {assessment.bundleHash || 'not recorded'}.</p>
      {interpretation && <p><strong>Interpretation overview:</strong> {interpretation.summary}</p>}
      {evidenceFacts.length > 0 && <section aria-label="Cited source details">
        <h4>Cited source details</h4>
        {evidenceFacts.map((fact, index) => <section key={`evidence-${index}`} aria-label={`${sourceLabel(fact.sourceType)} ${fact.evidenceId ?? index}`}>
          <p><strong>{sourceLabel(fact.sourceType)}</strong> · {sourceModeLabel(fact.sourceMode)} · evidence ID {fact.evidenceId ?? 'not recorded'}.</p>
          <p>Event: {fact.eventType ? humanizeCode(fact.eventType) : 'not recorded'} · source event: {fact.timestamp ?? 'not recorded'} · observed: {fact.observedAt ?? 'not recorded'} · retrieved: {fact.retrievedAtUtc ?? 'not recorded'}.</p>
          <p>Citation: {fact.citation || 'not recorded'} · evidence reference: {fact.factReference || 'not recorded'}.</p>
        </section>)}
        <p className="maritime-ai-note">Evidence freshness was not included in this saved fact contract; source event, observation, and retrieval times are shown when available.</p>
      </section>}
      {assessment.deterministicSummary?.limitations?.length ? <section aria-label="Evidence limitations"><h4>Evidence limitations</h4><ul>{assessment.deterministicSummary.limitations.map((limitation, index) => <li key={`limitation-${index}`}>{limitation}</li>)}</ul></section> : null}
      {riskSnapshot && <section aria-label="Assessment-time risk calculation details">
        <h4>Assessment-time risk calculation details</h4>
        <dl>
          <div><dt>Assessed</dt><dd>{riskSnapshot.assessed ? 'Yes' : 'No'}</dd></div>
          <div><dt>Severity</dt><dd>{riskSnapshot.severity ? humanizeCode(riskSnapshot.severity) : 'Not recorded'}</dd></div>
          <div><dt>Selected basis</dt><dd>{basisLabel(riskSnapshot.selectedWindowBasis)}</dd></div>
          <div><dt>Selected window state</dt><dd>{windowStateLabel(riskSnapshot.selectedWindowState)}</dd></div>
          <div><dt>Estimate freshness</dt><dd>{freshnessLabel(riskSnapshot.estimateFreshness)}</dd></div>
          {formatDuration(selectedDuration(riskSnapshot)) && <div><dt>Selected window duration</dt><dd>{formatDuration(selectedDuration(riskSnapshot))}</dd></div>}
          {formatDuration(selectedOverrun(riskSnapshot)) && <div><dt>Calculated overrun</dt><dd>{formatDuration(selectedOverrun(riskSnapshot))}</dd></div>}
          {riskSnapshot.estimateUnavailableCode && <div><dt>Estimate availability detail</dt><dd>{humanizeCode(riskSnapshot.estimateUnavailableCode)}</dd></div>}
          {riskSnapshot.unavailableReason && <div><dt>Risk availability detail</dt><dd>{riskSnapshot.unavailableReason}</dd></div>}
        </dl>
        {riskSnapshot.calculationLimitations.length > 0 && <ul>{riskSnapshot.calculationLimitations.map((limitation, index) => <li key={`risk-detail-${index}`}>{limitation}</li>)}</ul>}
        <p><strong>Canonical risk references:</strong> {riskSnapshot.canonicalFactReferences.length ? riskSnapshot.canonicalFactReferences.join(', ') : 'none recorded'}.</p>
      </section>}
      {businessSnapshot && <BusinessContextDetails snapshot={businessSnapshot} />}
      {claims.length > 0 && <section aria-label="Claim grounding details">
        <h4>Claim grounding</h4>
        {claims.map((claim, index) => <section key={`claim-${index}`} aria-label={`Grounding for claim ${index + 1}`}>
          <p>{relationshipLabel(claim)} · applicability {humanizeCode(claim.applicability)}.</p>
          <p>Evidence IDs: {claim.evidenceIds?.length ? claim.evidenceIds.join(', ') : 'none recorded'}.</p>
          <p>Canonical references: {claim.factReferences?.length ? claim.factReferences.join(', ') : 'none recorded'}.</p>
          {claim.riskReview && <p>Validated risk review: {claim.riskReview.severity ? humanizeCode(claim.riskReview.severity) : 'severity not recorded'} · {basisLabel(claim.riskReview.selectedWindowBasis)} · {windowStateLabel(claim.riskReview.selectedWindowState)} · AIS estimate {freshnessLabel(claim.riskReview.estimateFreshness)}.</p>}
        </section>)}
      </section>}
      <p>This advisory does not change shipment timing, risk, alerts, recommendations, or operational actions.</p>
    </details>
    <button type="button" onClick={onGenerate} disabled={generating}>{generating ? 'Generating advisory…' : 'Generate new interpretation'}</button>
  </section>;
}
