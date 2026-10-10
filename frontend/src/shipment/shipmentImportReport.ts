import { type ShipmentImportError } from './api';

const HEADER = ['Row', 'Column', 'Error Code', 'Reason'];

export function shipmentImportErrorReport(errors: ShipmentImportError[]): string {
  const rows = errors.map(error => [
    String(error.rowNumber),
    error.column ?? '',
    error.code,
    error.message,
  ]);
  return [HEADER, ...rows].map(row => row.map(csvValue).join(',')).join('\r\n') + '\r\n';
}

function csvValue(value: string): string {
  return /[",\r\n]/.test(value) ? `"${value.replaceAll('"', '""')}"` : value;
}
