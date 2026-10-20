import { randomBytes } from 'node:crypto';
import { spawn, execFileSync, spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const frontendDir = path.join(root, 'frontend');
const backendDir = path.join(root, 'backend');
const host = '127.0.0.1';
const port = '5432';
const dbUser = 'drift';
const dbPassword = 'drift';
const adminDatabase = 'postgres';
const blocked = new Set(['drift', 'drift_test', 'postgres', 'template0', 'template1']);
const ownedName = /^drift_cdg171_[0-9]{14}_[a-f0-9]{12}$/;
let connection = null;
let activeBackend = null;
let activePlaywright = null;
let signals = 0;

function psql(database, statement) {
  if (!connection) throw new Error('PostgreSQL connection has not been selected.');
  if (!/^[A-Za-z0-9_]+$/.test(database)) throw new Error(`Refusing database name ${database}.`);
  const common = ['-h', host, '-p', port, '-U', dbUser, '-d', database, '-v', 'ON_ERROR_STOP=1', '-At', '-c', statement];
  const env = { ...process.env, PGPASSWORD: dbPassword };
  if (connection.mode === 'compose') {
    return execFileSync('docker', ['compose', '-p', connection.project, 'exec', '-T', 'postgres', 'psql', ...common], {
      cwd: root, encoding: 'utf8', env,
    }).trim();
  }
  return execFileSync('psql', common, { encoding: 'utf8', env }).trim();
}

function selectConnection() {
  const compose = spawnSync('docker', ['compose', 'version'], { cwd: root, encoding: 'utf8' });
  if (compose.status === 0) {
    const published = execFileSync('docker', [
      'ps', '--filter', 'publish=5432',
      '--format', '{{.Label "com.docker.compose.project"}}\t{{.Label "com.docker.compose.service"}}\t{{.ID}}',
    ], { encoding: 'utf8' }).trim();
    const rows = published ? published.split('\n') : [];
    if (rows.length !== 1) {
      throw new Error(`docker compose is available, but ${rows.length} containers publish port 5432. Refusing to create a database or choose a different instance.`);
    }
    const [project, service] = rows[0].split('\t');
    if (!/^[A-Za-z0-9][A-Za-z0-9_-]*$/.test(project) || service !== 'postgres') {
      throw new Error('Port 5432 is not served by the Compose postgres service. Refusing to use another connection.');
    }
    connection = { mode: 'compose', project };
    const serverPort = psql(adminDatabase, 'SELECT inet_server_port()::text');
    if (serverPort !== port) {
      throw new Error('The selected Compose Postgres did not identify itself on TCP port 5432. No database was created.');
    }
    console.log(`PostgreSQL connection: mode=compose project=${project} service=postgres host=${host} port=${port}`);
    return;
  }
  const hostPsql = spawnSync('psql', ['--version'], { encoding: 'utf8' });
  if (hostPsql.status !== 0) {
    throw new Error('Neither Docker Compose nor host psql is available. No database was contacted.');
  }
  connection = { mode: 'host', project: '' };
  let serverPort = '';
  try {
    serverPort = psql(adminDatabase, 'SELECT inet_server_port()::text');
  } catch (error) {
    throw new Error(`Host psql could not reach ${host}:${port}. No database was created. ${error instanceof Error ? error.message : error}`);
  }
  if (serverPort !== port) {
    throw new Error('The selected local Postgres did not identify itself on TCP port 5432. No database was created.');
  }
  console.log(`PostgreSQL connection: mode=host host=${host} port=${port}`);
}

function generateName() {
  const stamp = new Date().toISOString().replace(/\D/g, '').slice(0, 14);
  return `drift_cdg171_${stamp}_${randomBytes(6).toString('hex')}`;
}

function createOwnedDatabase() {
  for (let attempt = 0; attempt < 5; attempt += 1) {
    const name = generateName();
    if (blocked.has(name) || !ownedName.test(name)) continue;
    const existing = psql(adminDatabase, `SELECT COUNT(*) FROM pg_database WHERE datname = '${name}'`);
    if (existing !== '0') continue;
    psql(adminDatabase, `CREATE DATABASE ${name}`);
    const token = randomBytes(32).toString('hex');
    psql(adminDatabase, `COMMENT ON DATABASE ${name} IS '${token}'`);
    return { name, token };
  }
  throw new Error('Could not create a new disposable database. Existing databases were left in place.');
}

function assertDisposableTarget(owned) {
  if (blocked.has(owned.name) || !ownedName.test(owned.name)) {
    throw new Error(`Refusing database ${owned.name}.`);
  }
  const current = psql(owned.name, 'SELECT current_database()');
  const serverPort = psql(owned.name, 'SELECT inet_server_port()::text');
  const tables = psql(owned.name, "SELECT COUNT(*)::text FROM information_schema.tables WHERE table_schema = 'public'");
  const history = psql(owned.name, "SELECT CASE WHEN to_regclass('public.flyway_schema_history') IS NULL THEN 'absent' ELSE 'present' END");
  const comment = psql(owned.name, "SELECT COALESCE(pg_catalog.shobj_description(d.oid, 'pg_database'), '') FROM pg_catalog.pg_database d WHERE d.datname = current_database()");
  if (current !== owned.name || serverPort !== port || tables !== '0' || history !== 'absent' || comment !== owned.token) {
    throw new Error(`Refusing to start against ${current} (tables=${tables}, flyway=${history}, port=${serverPort}). The database was left in place.`);
  }
}

function listeners(listenPort) {
  try {
    const output = execFileSync('lsof', ['-nP', `-iTCP:${listenPort}`, '-sTCP:LISTEN', '-Fp'], { encoding: 'utf8' });
    return [...new Set([...output.matchAll(/^p(\d+)$/gm)].map(match => Number(match[1])))];
  } catch (error) {
    if (error.status === 1) return [];
    throw error;
  }
}

function assertPortsAvailable() {
  for (const listenPort of [8080, 5173]) {
    const pids = listeners(listenPort);
    if (pids.length) {
      throw new Error(`Port ${listenPort} is already in use by pid ${pids.join(', ')}. This runner will not use or stop that process.`);
    }
  }
}

function backendEnv(owned) {
  const url = `jdbc:postgresql://${host}:${port}/${owned.name}`;
  if (!url.endsWith(`/${owned.name}`) || url.endsWith('/drift') || url.endsWith('/drift_test')) {
    throw new Error(`Refusing datasource URL ${url}.`);
  }
  const env = {
    ...process.env,
    JWT_SECRET: randomBytes(48).toString('base64url'),
    AIS_ENABLED: 'false',
    AISSTREAM_API_KEY: '',
    SERVER_PORT: '8080',
    SPRING_DATASOURCE_URL: url,
    SPRING_DATASOURCE_USERNAME: dbUser,
    SPRING_DATASOURCE_PASSWORD: dbPassword,
    SPRING_FLYWAY_URL: url,
    SPRING_FLYWAY_USER: dbUser,
    SPRING_FLYWAY_PASSWORD: dbPassword,
  };
  if (env.AIS_ENABLED !== 'false' || env.SPRING_DATASOURCE_URL !== env.SPRING_FLYWAY_URL) {
    throw new Error('The backend environment does not keep AIS disabled and both URLs on the disposable database.');
  }
  return env;
}

function waitForBackend(child, owned) {
  const url = `jdbc:postgresql://${host}:${port}/${owned.name}`;
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
      if (!urls.length || urls.some(item => !item.startsWith(url))) {
        finish(new Error(`The backend did not stay on ${url}.`));
        return;
      }
      finish();
    }, 200);
  });
}

function runPlaywright(owned) {
  const child = spawn('npx', ['playwright', 'test', 'e2e/shipment-paging.spec.ts', '--grep', '@disposable-database'], {
    cwd: frontendDir,
    env: {
      ...process.env,
      CI: '1',
      DRIFT_E2E_DATABASE: owned.name,
      DRIFT_E2E_OWNER_TOKEN: owned.token,
      DRIFT_E2E_PSQL: connection.mode,
      DRIFT_E2E_COMPOSE_PROJECT: connection.project,
      DRIFT_E2E_DB_HOST: host,
      DRIFT_E2E_DB_PORT: port,
      DRIFT_E2E_DB_USER: dbUser,
      DRIFT_E2E_DB_PASSWORD: dbPassword,
      DRIFT_API_ORIGIN: `http://${host}:8080`,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  activePlaywright = child;
  let output = '';
  child.stdout?.on('data', chunk => { output += chunk.toString(); process.stdout.write(chunk); });
  child.stderr?.on('data', chunk => { output += chunk.toString(); process.stderr.write(chunk); });
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = (error, code) => {
      if (settled) return;
      settled = true;
      child.off('error', onError);
      child.off('exit', onExit);
      activePlaywright = null;
      if (error) reject(error);
      else resolve({ code: code ?? 1, output });
    };
    const onError = error => finish(error);
    const onExit = code => finish(null, code);
    child.on('error', onError);
    child.on('exit', onExit);
  });
}

function executedCount(output) {
  const matches = [...output.matchAll(/(\d+)\s+passed/g)];
  if (!matches.length) return 0;
  return Number(matches[matches.length - 1][1]);
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

export async function runShipmentPaging() {
  assertPortsAvailable();
  selectConnection();
  const owned = createOwnedDatabase();
  console.log(`Shipment paging database: ${owned.name}`);
  assertDisposableTarget(owned);
  const env = backendEnv(owned);
  console.log(`Validated empty ${owned.name} on ${host}:${port} before startup. AIS_ENABLED=${env.AIS_ENABLED}. Datasource and Flyway URL ${env.SPRING_DATASOURCE_URL}.`);
  let failure = null;
  let executed = 0;
  try {
    activeBackend = spawn('bash', ['./mvnw', 'spring-boot:run'], {
      cwd: backendDir, env, detached: true, stdio: ['ignore', 'pipe', 'pipe'],
    });
    await waitForBackend(activeBackend, owned);
    const result = await runPlaywright(owned);
    executed = executedCount(result.output);
    console.log(`Shipment paging tests passed: ${executed}`);
    if (result.code !== 0 || executed === 0) {
      throw new Error(`Paging coverage did not execute a passing disposable-database test (exit ${result.code}, passed ${executed}).`);
    }
  } catch (error) {
    failure = error;
  } finally {
    const backend = activeBackend;
    activeBackend = null;
    if (activePlaywright) {
      try { activePlaywright.kill('SIGTERM'); } catch { /* the runner reports a leftover process */ }
    }
    if (backend) await stopProcessGroup(backend);
    console.log(`Left disposable database ${owned.name} in place.`);
  }
  if (failure) throw failure;
  return { executed, database: owned.name };
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
  runShipmentPaging().then(result => {
    console.log(`Shipment paging tests passed: ${result.executed}. Database left in place: ${result.database}`);
    process.exitCode = signals > 0 ? 130 : 0;
  }).catch(error => {
    console.error(error instanceof Error ? error.message : error);
    process.exitCode = 1;
  });
}
