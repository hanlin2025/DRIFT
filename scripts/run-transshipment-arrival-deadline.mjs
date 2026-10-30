import { randomBytes } from 'node:crypto';
import { spawn, execFileSync, spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const frontendDir = path.join(root, 'frontend');
const backendDir = path.join(root, 'backend');
const container = 'drift-cdg204-pg';
const hostPort = '55432';
const blocked = new Set(['drift', 'drift_test', 'postgres', 'template0', 'template1']);
const ownedName = /^drift_cdg204_[0-9]{14}_[a-f0-9]{12}$/;
let activeBackend = null;
let activePlaywright = null;
let signals = 0;

function psql(database, statement) {
  return execFileSync('docker', ['exec', '-e', 'PGPASSWORD=drift', container, 'psql', '-h', '127.0.0.1', '-U', 'drift', '-d', database, '-v', 'ON_ERROR_STOP=1', '-At', '-c', statement], {
    cwd: root,
    encoding: 'utf8',
  }).trim();
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

function assertPortsAvailable() {
  for (const port of [8080, 5173]) {
    const pids = listeners(port);
    if (pids.length) throw new Error(`Port ${port} is already in use by pid ${pids.join(', ')}. This runner will not use or stop that process.`);
  }
}

function ensureDatabaseServer() {
  const existing = spawnSync('docker', ['inspect', container, '--format', '{{.Name}} {{.Config.Image}} {{.State.Status}}'], { encoding: 'utf8' });
  if (existing.status !== 0) {
    const created = spawnSync('docker', ['run', '-d', '--name', container,
      '-e', 'POSTGRES_USER=drift', '-e', 'POSTGRES_PASSWORD=drift', '-e', 'POSTGRES_DB=postgres',
      '-p', `127.0.0.1:${hostPort}:5432`, 'postgres:16'], { encoding: 'utf8' });
    if (created.status !== 0) throw new Error(`Could not start ${container}. ${created.stderr}`);
  } else if (!existing.stdout.includes('postgres:16')) {
    throw new Error(`Refusing to use ${container} because it is not the Postgres container for this task.`);
  } else if (existing.stdout.includes('exited')) {
    const started = spawnSync('docker', ['start', container], { encoding: 'utf8' });
    if (started.status !== 0) throw new Error(`Could not start ${container}. ${started.stderr}`);
  }
  for (let attempt = 0; attempt < 30; attempt += 1) {
    const ready = spawnSync('docker', ['exec', container, 'pg_isready', '-U', 'drift', '-d', 'postgres'], { encoding: 'utf8' });
    if (ready.status === 0) return;
    spawnSync('sleep', ['1']);
  }
  throw new Error(`${container} did not accept connections.`);
}

function createOwnedDatabase() {
  const stamp = new Date().toISOString().replace(/\D/g, '').slice(0, 14);
  const name = `drift_cdg204_${stamp}_${randomBytes(6).toString('hex')}`;
  if (blocked.has(name) || !ownedName.test(name)) throw new Error(`Refusing database name ${name}.`);
  psql('postgres', `CREATE DATABASE ${name}`);
  const token = randomBytes(32).toString('hex');
  psql('postgres', `COMMENT ON DATABASE ${name} IS '${token}'`);
  const identity = psql(name, `SELECT current_database() || '|' || inet_server_port()::text || '|' || (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public') || '|' || CASE WHEN to_regclass('public.flyway_schema_history') IS NULL THEN 'absent' ELSE 'present' END || '|' || COALESCE(pg_catalog.shobj_description(d.oid, 'pg_database'), '') FROM pg_catalog.pg_database d WHERE d.datname = current_database()`);
  const [current, port, tables, history, comment] = identity.split('|');
  if (current !== name || port !== '5432' || tables !== '0' || history !== 'absent' || comment !== token) {
    throw new Error(`Refusing database ${current} before migration (tables=${tables}, flyway=${history}, port=${port}). The database was not dropped.`);
  }
  console.log(`TransshipmentDeadlineDatabase name=${name} publicTableCount=0 flywaySchemaHistoryPresent=false`);
  return { name, token };
}

function backendEnv(owned) {
  const url = `jdbc:postgresql://127.0.0.1:${hostPort}/${owned.name}`;
  return {
    ...process.env,
    JWT_SECRET: randomBytes(48).toString('base64url'),
    AIS_ENABLED: 'false',
    RISK_ALERTS_EVALUATION_ENABLED: 'false',
    RISK_ALERTS_MAIL_ENABLED: 'false',
    RISK_ALERTS_DISPATCH_ENABLED: 'false',
    RISK_ALERTS_ACTIVATION_COMMAND: 'false',
    SERVER_PORT: '8080',
    SPRING_DATASOURCE_URL: url,
    SPRING_DATASOURCE_USERNAME: 'drift',
    SPRING_DATASOURCE_PASSWORD: 'drift',
    SPRING_FLYWAY_URL: url,
    SPRING_FLYWAY_USER: 'drift',
    SPRING_FLYWAY_PASSWORD: 'drift',
    SPRING_FLYWAY_OUT_OF_ORDER: 'false',
  };
}

function waitForBackend(child, owned) {
  const url = `jdbc:postgresql://127.0.0.1:${hostPort}/${owned.name}`;
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
    const timer = setTimeout(() => finish(new Error('The backend did not start within 180 seconds.')), 180000);
    const onExit = code => finish(new Error(`The backend exited before it started (status ${code ?? 'unknown'}).`));
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
      if (log.includes('flyway.out-of-order') && log.includes('true')) {
        finish(new Error('The backend enabled Flyway out-of-order execution.'));
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
    if (listenerGroup !== group) throw new Error(`Port 8080 is served by pid ${pid}, not the backend this runner started.`);
  }
}

function runPlaywright(owned) {
  const child = spawn('npx', ['playwright', 'test', 'e2e/transshipment-arrival-deadline.spec.ts', '--project=desktop'], {
    cwd: frontendDir,
    env: {
      ...process.env,
      DRIFT_E2E_DATABASE: owned.name,
      DRIFT_E2E_OWNER_TOKEN: owned.token,
      DRIFT_E2E_CONTAINER: container,
      DRIFT_API_ORIGIN: 'http://127.0.0.1:8080',
    },
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

async function main() {
  assertPortsAvailable();
  ensureDatabaseServer();
  const owned = createOwnedDatabase();
  let testCode = 1;
  let failure = null;
  try {
    activeBackend = spawn('bash', ['./mvnw', 'spring-boot:run'], {
      cwd: backendDir, env: backendEnv(owned), detached: true, stdio: ['ignore', 'pipe', 'pipe'],
    });
    await waitForBackend(activeBackend, owned);
    assertListenerIsOurs(activeBackend);
    testCode = await runPlaywright(owned);
  } catch (error) {
    failure = error;
  } finally {
    const backend = activeBackend;
    activeBackend = null;
    if (backend) await stopProcessGroup(backend);
  }
  console.log(`Retained disposable database ${owned.name} in container ${container}.`);
  if (failure) throw failure;
  return testCode;
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  process.on('SIGINT', stopForSignal);
  process.on('SIGTERM', stopForSignal);
  main().then(code => {
    process.exitCode = signals > 0 ? 130 : code;
  }).catch(error => {
    console.error(error instanceof Error ? error.message : error);
    process.exitCode = 1;
  });
}
