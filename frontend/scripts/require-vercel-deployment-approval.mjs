if (process.env.VERCEL && process.env.DRIFT_VERCEL_DEPLOY_APPROVED !== 'true') {
  console.error(
    'Vercel build blocked: set DRIFT_VERCEL_DEPLOY_APPROVED=true only after deployment and security approval.'
  );
  process.exit(78);
}
