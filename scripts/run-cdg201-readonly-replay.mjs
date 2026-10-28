import { runVesselDetails } from './run-vessel-details.mjs';

const status = await runVesselDetails({
  playwrightArgs: ['playwright', 'test', 'e2e/cdg201-readonly-replay.spec.ts', '--project=desktop'],
});
process.exitCode = status;
