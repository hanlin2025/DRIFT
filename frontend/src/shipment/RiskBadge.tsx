import { type RiskLevel, type ShipmentRisk } from './api';

export const RISK_LABELS: Record<RiskLevel, string> = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High', CRITICAL: 'Critical' };

export const NOT_ASSESSED = 'Not assessed';

/** The shipment's risk level, or "Not assessed" when the feeder schedule is missing. List and detail share it. */
export function RiskBadge({ risk }: { risk: ShipmentRisk | null }) {
  if (!risk) return <span className="risk-badge risk-unassessed">{NOT_ASSESSED}</span>;
  return <span className={`risk-badge risk-${risk.level.toLowerCase()}`}>{RISK_LABELS[risk.level]}</span>;
}
