import { describe, expect, it } from 'vitest';
import { shipmentImportErrorReport } from './shipmentImportReport';

describe('shipment import error report', () => {
  it('writes every error as a CSV record and escapes CSV-sensitive values', () => {
    const report = shipmentImportErrorReport([
      { rowNumber: 4, column: 'Origin Port', code: 'MISSING_VALUE', message: 'Origin is required' },
      { rowNumber: 4, column: 'Mother Vessel', code: 'INVALID_VALUE', message: 'Use "MV, Example"\nwith a valid name' },
    ]);

    expect(report).toBe('Row,Column,Error Code,Reason\r\n4,Origin Port,MISSING_VALUE,Origin is required\r\n4,Mother Vessel,INVALID_VALUE,"Use ""MV, Example""\nwith a valid name"\r\n');
  });
});
