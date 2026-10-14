import { pathToFileURL } from 'node:url';
import { runShipmentDetail } from './run-shipment-detail.mjs';

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  runShipmentDetail({
    databasePrefix: 'drift_cdg139',
    playwrightArgs: ['playwright', 'test', 'e2e/operational-state.spec.ts', '--project=desktop'],
  }).then(code => {
    process.exitCode = code;
  }).catch(error => {
    console.error(error instanceof Error ? error.message : error);
    process.exitCode = 1;
  });
}
