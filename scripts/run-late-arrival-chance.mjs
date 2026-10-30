import { randomBytes } from 'node:crypto';
import { spawn, execFileSync, spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const frontendDir = path.join(root, 'frontend');
const backendDir = path.join(root, 'backend');
const blocked = new Set(['drift', 'drift_test', 'postgres', 'template0', 'template1']);
const ownedName = /^drift_cdg210_browser_[0-9]{14}_[a-f0-9]{12}$/;
let activeBackend = null;
let activePlaywright = null;
let signals = 0;

function commandWorks(command, args) {
  return spawnSync(command, args, { cwd: root, encoding: 'utf8' }).status === 0;
}

function selectAccess() {
  const requested = process.env.DRIFT_E2E_PSQL;
  if (requested === 'compose') {
    if (!commandWorks('docker', ['compose', 'version'])) {
      throw new Error('DRIFT_E2E_PSQL=compose but Docker Compose is not available. No other database path was tried.');
    }
    return 'compose';
  }
  if (requested === 'host') {
    if (!commandWorks('psql', ['--version'])) {
      throw new Error('DRIFT_E2E_PSQL=host but psql is not available. No other database path was tried.');
    }
    return 'host';
  }
  if (requested) {
    throw new Error(`DRIFT_E2E_PSQL=${requested} is not a database access path. No database was contacted.`);
  }
  if (commandWorks('docker', ['compose', 'version'])) return 'compose';
  if (commandWorks('psql', ['--version'])) return 'host';
  throw new Error('Neither Docker Compose nor host psql is available. No database was contacted.');
}

const access = selectAccess();

function psql(database, statement) {
  const common = ['-h', '127.0.0.1', '-p', '5432', '-U', 'drift', '-d', database, '-v', 'ON_ERROR_STOP=1', '-At', '-c', statement];
  if (access === 'compose') {
    return execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'psql', ...common],
      { cwd: root, encoding: 'utf8', env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD || 'drift' } });
  }
  return execFileSync('psql', common, {
    encoding: 'utf8',
    env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD || 'drift' },
  });
}

function generateName() {
  const stamp = new Date().toISOString().replace(/\D/g, '').slice(0, 14);
  return `drift_cdg210_browser_${stamp}_${randomBytes(6).toString('hex')}`;
}

function createOwnedDatabase() {
  const name = generateName();
  if (blocked.has(name) || !ownedName.test(name)) {
    throw new Error(`Refusing database name ${name}.`);
  }
  const existing = psql('postgres', `SELECT COUNT(*) FROM pg_database WHERE datname = '${name}'`).trim();
  if (existing !== '0') throw new Error(`${name} already exists. No database was dropped.`);
  psql('postgres', `CREATE DATABASE ${name}`);
  const token = randomBytes(32).toString('hex');
  psql('postgres', `COMMENT ON DATABASE ${name} IS '${token}'`);
  return { name, token };
}

function assertDisposableTarget(owned) {
  if (blocked.has(owned.name) || !ownedName.test(owned.name) || owned.name === 'drift' || owned.name === 'drift_test') {
    throw new Error(`Refusing database ${owned.name}.`);
  }
  const current = psql(owned.name, 'SELECT current_database()').trim();
  const port = psql(owned.name, 'SELECT inet_server_port()::text').trim();
  const tables = psql(owned.name, "SELECT COUNT(*)::text FROM information_schema.tables WHERE table_schema = 'public'").trim();
  const history = psql(owned.name, "SELECT CASE WHEN to_regclass('public.flyway_schema_history') IS NULL THEN 'absent' ELSE 'present' END").trim();
  const comment = psql(owned.name, "SELECT COALESCE(pg_catalog.shobj_description(d.oid, 'pg_database'), '') FROM pg_catalog.pg_database d WHERE d.datname = current_database()").trim();
  if (current !== owned.name || port !== '5432' || tables !== '0' || history !== 'absent' || comment !== owned.token) {
    throw new Error(`Refusing to start against ${current} (tables=${tables}, flyway=${history}, port=${port}).`);
  }
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
    if (pids.length) {
      throw new Error(`Port ${port} is already in use by pid ${pids.join(', ')}. This runner will not use or stop that process.`);
    }
  }
}

function backendEnv(owned) {
  const url = `jdbc:postgresql://127.0.0.1:5432/${owned.name}`;
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
    SPRING_DATASOURCE_USERNAME: 'drift',
    SPRING_DATASOURCE_PASSWORD: 'drift',
    SPRING_FLYWAY_URL: url,
    SPRING_FLYWAY_USER: 'drift',
    SPRING_FLYWAY_PASSWORD: 'drift',
  };
  if (env.AIS_ENABLED !== 'false' || env.SPRING_DATASOURCE_URL !== env.SPRING_FLYWAY_URL) {
    throw new Error('The backend environment does not keep AIS disabled and both URLs on the disposable database.');
  }
  return env;
}

function waitForBackend(child, owned) {
  const url = `jdbc:postgresql://127.0.0.1:5432/${owned.name}`;
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
      else resolve(log);
    };
    const timer = setTimeout(() => finish(new Error('The backend did not start within 180 seconds.')), 180000);
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
  const child = spawn('npx', ['playwright', 'test', 'e2e/late-arrival-chance.spec.ts', '--grep', '@disposable-database'], {
    cwd: frontendDir,
    env: { ...process.env, DRIFT_E2E_DATABASE: owned.name, DRIFT_E2E_OWNER_TOKEN: owned.token, DRIFT_E2E_PSQL: access, CI: '1' },
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

export async function runLateArrivalChance() {
  console.log(`Database access: ${access === 'compose' ? 'docker compose exec' : 'psql -h 127.0.0.1'}.`);
  assertPortsAvailable();
  const owned = createOwnedDatabase();
  console.log(`Late-arrival chance browser database: ${owned.name}`);
  assertDisposableTarget(owned);
  const env = backendEnv(owned);
  console.log(`Validated empty ${owned.name} on 127.0.0.1:5432 before startup. AIS_ENABLED=${env.AIS_ENABLED}.`);
  let failure = null;
  let executed = 0;
  try {
    activeBackend = spawn('bash', ['./mvnw', 'spring-boot:run'], {
      cwd: backendDir, env, detached: true, stdio: ['ignore', 'pipe', 'pipe'],
    });
    await waitForBackend(activeBackend, owned);
    const result = await runPlaywright(owned);
    executed = executedCount(result.output);
    console.log(`Late-arrival chance browser tests executed: ${executed}`);
    if (result.code !== 0 || executed === 0) {
      throw new Error(`Browser coverage did not execute a passing disposable-database test (exit ${result.code}, executed ${executed}).`);
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
  return executed;
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
  runLateArrivalChance().then(count => {
    console.log(`Late-arrival chance browser tests executed: ${count}`);
    process.exitCode = signals > 0 ? 130 : 0;
  }).catch(error => {
    console.error(error instanceof Error ? error.message : error);
    process.exitCode = 1;
  });
}
