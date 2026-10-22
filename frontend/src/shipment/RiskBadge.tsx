import { type RiskLevel, type ShipmentPriority, type ShipmentRisk } from './api';

export const RISK_LABELS: Record<RiskLevel, string> = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High', CRITICAL: 'Critical' };

export const NOT_ASSESSED = 'Not assessed';

/** The shipment's risk level, or "Not assessed" when the feeder schedule is missing. List and detail share it. */
export function RiskBadge({ risk }: { risk: ShipmentRisk | null }) {
  if (!risk) return <span className="risk-badge risk-unassessed">{NOT_ASSESSED}</span>;
  return <span className={`risk-badge risk-${risk.level.toLowerCase()}`}>{RISK_LABELS[risk.level]}</span>;
}

/**
 * The priority score with the reasons behind it. The reasons come from the backend ranking, which builds them only
 * from the connection window, the time to the feeder departure, and the AIS evidence.
 */
export function PriorityScore({ priority, reference }: { priority: ShipmentPriority | null | undefined; reference: string }) {
  if (!priority) return null;
  return (
    <details className="priority-score">
      <summary aria-label={`Priority ${priority.score} of 100 for ${reference}. Why this rank`}>
        Priority {priority.score}
      </summary>
      <ul>
        {priority.reasons.map(reason => <li key={reason}>{reason}</li>)}
      </ul>
    </details>
  );
}
