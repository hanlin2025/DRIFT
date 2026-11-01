import { formatWhen } from './ShipmentForm';
import { type DelayComparison, type Shipment } from './api';

const LOCAL_TIME = 'Shown in your local time.';
const SEPARATE = 'This is only the recorded mother-vessel transshipment-arrival deadline. It is separate from the planned-arrival comparison and from connection risk.';
const NOT_COMPLETED = 'No deadline assessment was completed.';

export function DelayComparisonFact({ shipment }: { shipment: Shipment }) {
  const assessment = shipment.delayComparison;
  if (!assessment) return null;
  const notAssessed = assessment.reason === 'STALE_PREDICTION' || assessment.reason === 'INCOMPATIBLE_MILESTONE';
  const prediction = predictionValue(shipment, assessment);
  const deadline = assessment.deadline
    ? <time dateTime={assessment.deadline}>{formatWhen(assessment.deadline)}</time>
    : 'Not recorded';
  const showsTime = assessment.reason !== 'MISSING_PREDICTION' && Boolean(assessment.estimatedArrivalAt) || Boolean(assessment.deadline);

  return (
    <div>
      <dt>Deadline assessment</dt>
      <dd>
        {notAssessed && <p>{NOT_COMPLETED}</p>}
        <p>{assessment.message}</p>
        <dl className="arrival-comparison">
          <div>
            <dt>{notAssessed ? 'Predicted transshipment arrival, not assessed' : 'Predicted transshipment arrival'}</dt>
            <dd>{prediction}</dd>
          </div>
          <div>
            <dt>{notAssessed ? 'Recorded deadline, not assessed' : 'Recorded deadline'}</dt>
            <dd>{deadline}</dd>
          </div>
          <div>
            <dt>Recorded port</dt>
            <dd>{assessment.recordedTransshipmentPort?.trim() || 'Not recorded'}</dd>
          </div>
          {assessment.reason === 'INCOMPATIBLE_MILESTONE' && (
            <div>
              <dt>Current transshipment port</dt>
              <dd>{shipment.transshipmentPort}</dd>
            </div>
          )}
        </dl>
        {showsTime && <span className="deadline-note">{LOCAL_TIME}</span>}
        <span className="deadline-note">{SEPARATE}</span>
      </dd>
    </div>
  );
}

function predictionValue(shipment: Shipment, assessment: DelayComparison) {
  if (assessment.reason === 'MISSING_PREDICTION') {
    const explanation = shipment.arrivalComparison?.unavailableReason?.message;
    return explanation ? `Unavailable. ${explanation}` : 'Unavailable';
  }
  if (!assessment.estimatedArrivalAt) return 'Not recorded';
  return <time dateTime={assessment.estimatedArrivalAt}>{formatWhen(assessment.estimatedArrivalAt)}</time>;
}
