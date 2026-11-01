import { randomBytes } from 'node:crypto';
import { spawn, execFileSync, spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const frontendDir = path.join(root, 'frontend');
const backendDir = path.join(root, 'backend');
const blocked = new Set(['drift', 'drift_test', 'postgres', 'template0', 'template1']);
const impactOverrideSwitch = 'cdg275-e2e';
const ownedName = /^drift_cdg230_[0-9]{14}_[a-f0-9]{12}$/;
const createdByThisProcess = new Set();
let activeBackend = null;
let activePlaywright = null;
let signals = 0;

function dockerCompose() {
  const probe = spawnSync('docker', ['compose', 'version'], { cwd: root, encoding: 'utf8' });
  return probe.status === 0;
}

const useDocker = dockerCompose();

function psql(database, statement) {
  const common = ['-U', 'drift', '-d', database, '-v', 'ON_ERROR_STOP=1', '-At', '-c', statement];
  const host = process.env.DRIFT_E2E_PGHOST ?? '';
  const port = process.env.DRIFT_E2E_PGPORT ?? '';
  const container = process.env.DRIFT_E2E_PGCONTAINER ?? '';
  if (port === '5432') throw new Error('Refusing the shared database port.');
  if (container === 'drift-postgres') throw new Error('Refusing the shared database container.');
  if (container) {
    return execFileSync('docker', ['exec', '-i', container, 'psql', ...common], {
      cwd: root,
      encoding: 'utf8',
    });
  }
  if (host && port) {
    return execFileSync('psql', ['-h', host, '-p', port, ...common], {
      cwd: root,
      encoding: 'utf8',
      env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD || 'drift' },
    });
  }
  if (useDocker) {
    return execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'psql', ...common],
      { cwd: root, encoding: 'utf8' });
  }
  return execFileSync('psql', ['-h', '127.0.0.1', ...common], {
    cwd: root,
    encoding: 'utf8',
    env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD || 'drift' },
  });
}

function databaseExists(name) {
  return psql('postgres', `SELECT COUNT(*) FROM pg_database WHERE datname = '${name}'`).trim() !== '0';
}

function generateName() {
  const stamp = new Date().toISOString().replace(/\D/g, '').slice(0, 14);
  return `drift_cdg230_${stamp}_${randomBytes(6).toString('hex')}`;
}

export async function createOwnedDatabase({ names } = {}) {
  const candidates = names ?? Array.from({ length: 5 }, generateName);
  for (const name of candidates) {
    if (blocked.has(name) || !ownedName.test(name)) continue;
    if (databaseExists(name)) continue;
    try {
      psql('postgres', `CREATE DATABASE ${name}`);
    } catch (error) {
      const detail = `${error.stderr ?? ''}${error.message ?? ''}`;
      if (/already exists/i.test(detail)) continue;
      throw new Error(`Could not create ${name}. No existing database was dropped. ${detail.trim()}`);
    }
    createdByThisProcess.add(name);
    const token = randomBytes(32).toString('hex');
    try {
      psql(name, `COMMENT ON DATABASE ${name} IS '${token}'`);
    } catch (error) {
      try {
        await dropOwnedDatabase(name);
      } catch (dropError) {
        throw new Error(`Ownership was not recorded for ${name}, and dropping that new database failed. ${dropError.message}`);
      }
      throw new Error(`Ownership was not recorded for ${name}. The new database was dropped. ${error.message}`);
    }
    return { name, token };
  }
  throw new Error('Could not create a new disposable database. Existing databases were left in place.');
}

export async function dropOwnedDatabase(name) {
  if (!createdByThisProcess.has(name) || blocked.has(name) || !ownedName.test(name)) {
    throw new Error(`Refusing to drop ${name}; this process did not create it.`);
  }
  psql('postgres', `SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '${name}' AND pid <> pg_backend_pid()`);
  psql('postgres', `DROP DATABASE ${name}`);
  createdByThisProcess.delete(name);
}

function listeners(port) {
  try {
    const output = execFileSync('lsof', ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN', '-Fp'], { encoding: 'utf8' });
    return [...new Set([...output.matchAll(/^p(\d+)$/gm)].map(match => Number(match[1])))];
  } catch (error) {
    if (error.status === 1) return [];
    throw error;
  }
}

function processCwd(pid) {
  const output = execFileSync('lsof', ['-a', '-p', String(pid), '-d', 'cwd', '-Fn'], { encoding: 'utf8' });
  return output.split('\n').find(line => line.startsWith('n'))?.slice(1) ?? '';
}

function webPort() {
  return process.env.DRIFT_E2E_WEB_PORT || '5173';
}

function assertPortsAvailable() {
  const backend = listeners(8080);
  if (backend.length) {
    throw new Error(`Port 8080 is already in use by pid ${backend.join(', ')}. This runner will not use or stop that process.`);
  }
  const port = webPort();
  for (const pid of listeners(port)) {
    if (processCwd(pid) !== frontendDir) {
      throw new Error(`Port ${port} is already in use by pid ${pid} outside this frontend. This runner will not stop it.`);
    }
  }
}

function databaseUrl(owned) {
  const host = process.env.DRIFT_E2E_PGHOST || 'localhost';
  const port = process.env.DRIFT_E2E_PGPORT || '5432';
  if (port === '5432' && process.env.DRIFT_E2E_PGHOST) {
    throw new Error('Refusing the shared database port.');
  }
  return `jdbc:postgresql://${host}:${port}/${owned.name}`;
}

function backendEnv(owned, impactOutcome) {
  const url = databaseUrl(owned);
  return {
    ...process.env,
    JWT_SECRET: randomBytes(48).toString('base64url'),
    AIS_ENABLED: 'false',
    DRIFT_RECOMMENDATION_IMPACT_OVERRIDE: impactOverrideSwitch,
    DRIFT_RECOMMENDATION_IMPACT_OUTCOME: impactOutcome,
    SERVER_PORT: '8080',
    SPRING_DATASOURCE_URL: url,
    SPRING_DATASOURCE_USERNAME: 'drift',
    SPRING_DATASOURCE_PASSWORD: 'drift',
    SPRING_FLYWAY_URL: url,
    SPRING_FLYWAY_USER: 'drift',
    SPRING_FLYWAY_PASSWORD: 'drift',
  };
}

function defaultSpawnBackend(env) {
  return spawn('bash', ['./mvnw', 'spring-boot:run'], {
    cwd: backendDir, env, detached: true, stdio: ['ignore', 'pipe', 'pipe'],
  });
}

function waitForBackend(child, owned) {
  const url = databaseUrl(owned);
  let log = '';
  const append = chunk => { log += chunk.toString(); };
  child.stdout?.on('data', append);
  child.stderr?.on('data', append);
  return new Promise((resolve, reject) => {
    let check;
    let settled = false;
    const finish = error => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      clearInterval(check);
      child.off('exit', onExit);
      child.off('error', onError);
      if (error) reject(error);
      else resolve();
    };
    const timer = setTimeout(() => finish(new Error(`The backend did not start within 180 seconds.\n${log.slice(-4000)}`)), 180000);
    const onExit = code => finish(new Error(`The backend exited before it started (status ${code ?? 'unknown'}).\n${log.slice(-4000)}`));
    const onError = error => finish(error);
    child.on('exit', onExit);
    child.on('error', onError);
    check = setInterval(() => {
      if (!log.includes('Started BackendApplication')) return;
      const urls = log.match(/jdbc:postgresql:\S+/g) ?? [];
      if (!urls.some(item => item.startsWith(url)) || urls.some(item => item.startsWith('jdbc:postgresql:') && !item.startsWith(url))) {
        finish(new Error('The backend did not use the database this runner created.'));
        return;
      }
      finish();
    }, 200);
  });
}

function assertListenerIsOurs(child) {
  const group = execFileSync('ps', ['-o', 'pgid=', '-p', String(child.pid)], { encoding: 'utf8' }).trim();
  const pids = listeners(8080);
  if (!pids.length) throw new Error('The backend this runner started is not listening on port 8080.');
  for (const pid of pids) {
    const listenerGroup = execFileSync('ps', ['-o', 'pgid=', '-p', String(pid)], { encoding: 'utf8' }).trim();
    if (listenerGroup !== group) {
      throw new Error(`Port 8080 is served by pid ${pid}, not the backend this runner started.`);
    }
  }
}

function runPlaywright(owned, args) {
  const child = spawn('npx', args ?? ['playwright', 'test', 'e2e/recommendation-decision.spec.ts', '--project=desktop'], {
    cwd: frontendDir,
    env: { ...process.env, DRIFT_E2E_DATABASE: owned.name, DRIFT_E2E_OWNER_TOKEN: owned.token },
    stdio: 'inherit',
  });
  activePlaywright = child;
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = (error, code) => {
      if (settled) return;
      settled = true;
      child.off('error', onError);
      child.off('exit', onExit);
      activePlaywright = null;
      if (error) reject(error);
      else resolve(code ?? 1);
    };
    const onError = error => finish(error);
    const onExit = code => finish(null, code);
    child.on('error', onError);
    child.on('exit', onExit);
  });
}

async function stopProcessGroup(child) {
  if (!child?.pid || child.exitCode !== null || child.signalCode) return;
  const exited = new Promise(resolve => {
    if (child.exitCode !== null || child.signalCode) resolve(true);
    else child.once('exit', () => resolve(true));
  });
  const waitForExit = timeout => new Promise(resolve => {
    let settled = false;
    const finish = value => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(value);
    };
    const timer = setTimeout(() => finish(false), timeout);
    exited.then(() => finish(true));
  });
  try {
    process.kill(-child.pid, 'SIGTERM');
  } catch (error) {
    if (error.code !== 'ESRCH') throw error;
  }
  if (await waitForExit(15000)) return;
  try {
    process.kill(-child.pid, 'SIGKILL');
  } catch (error) {
    if (error.code !== 'ESRCH') throw error;
  }
  if (await waitForExit(5000)) return;
  throw new Error('A process this runner started did not exit after it was signalled.');
}

const passes = (process.env.DRIFT_E2E_IMPACT_OUTCOMES ?? 'NO_PREDICTED_IMPACT,NOT_RETURNED')
	.split(',')
	.filter(Boolean)
	.map(outcome => ({
		outcome,
		grep: outcome === 'NOT_RETURNED' ? '@impact-not-returned' : '@completed-impact',
	}));

async function runPass(owned, pass, options) {
  if (listeners(8080).length) {
    throw new Error('Port 8080 is still in use, so the next recommendation decision pass cannot start.');
  }
  activeBackend = (options.spawnBackend ?? defaultSpawnBackend)(backendEnv(owned, pass.outcome));
  let testCode = 1;
  try {
    await waitForBackend(activeBackend, owned);
    if (!options.spawnBackend) assertListenerIsOurs(activeBackend);
    const args = options.playwrightArgs ?? [
      'playwright', 'test', 'e2e/recommendation-decision.spec.ts', '--project=desktop', '--max-failures=1', '--grep', pass.grep,
    ];
    testCode = await runPlaywright(owned, args);
  } finally {
    const backend = activeBackend;
    activeBackend = null;
    if (backend) await stopProcessGroup(backend);
  }
  return testCode;
}

export async function runRecommendationDecision(options = {}) {
  assertPortsAvailable();
  const owned = await (options.createDatabase ?? createOwnedDatabase)();
  let testCode = 0;
  let failure = null;
  const failures = [];
  try {
    for (const pass of passes) {
      const code = await runPass(owned, pass, options);
      if (code !== 0 && testCode === 0) testCode = code;
    }
  } catch (error) {
    failure = error;
  } finally {
    if (owned && createdByThisProcess.has(owned.name) && process.env.DRIFT_E2E_RETAIN_DATABASE !== 'true') {
      try {
        await dropOwnedDatabase(owned.name);
      } catch (error) {
        failures.push(error instanceof Error ? error.message : String(error));
      }
    }
  }
  if (failures.length) {
    const earlier = failure instanceof Error ? ` ${failure.message}` : '';
    throw new Error(`Cleanup failed.${earlier} ${failures.join(' ')}`);
  }
  if (failure) throw failure;
  return testCode;
}

function stopForSignal() {
  signals += 1;
  if (signals > 1) {
    console.error('Cleanup was interrupted and is not guaranteed.');
    process.exit(1);
  }
  if (activePlaywright) {
    try { activePlaywright.kill('SIGTERM'); } catch { /* the exit path reports a leftover process */ }
  }
  if (activeBackend) {
    try { process.kill(-activeBackend.pid, 'SIGTERM'); } catch { /* same */ }
  }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  process.on('SIGINT', stopForSignal);
  process.on('SIGTERM', stopForSignal);
  runRecommendationDecision().then(code => {
    process.exitCode = signals > 0 ? 130 : code;
  }).catch(error => {
    console.error(error instanceof Error ? error.message : error);
    process.exitCode = 1;
  });
}
