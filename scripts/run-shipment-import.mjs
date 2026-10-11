import { randomBytes } from 'node:crypto';
import { spawn, execFileSync, spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const frontendDir = path.join(root, 'frontend');
const backendDir = path.join(root, 'backend');
const blocked = new Set(['drift', 'drift_test', 'postgres', 'template0', 'template1']);
const ownedName = /^drift_cdg130_[0-9]{14}_[a-f0-9]{12}$/;
const createdByThisProcess = new Set();
let activeBackend = null;
let activePlaywright = null;
let signals = 0;

const useDocker = spawnSync('docker', ['compose', 'version'], { cwd: root, encoding: 'utf8' }).status === 0;

function psql(database, statement) {
  const common = ['-U', 'drift', '-d', database, '-v', 'ON_ERROR_STOP=1', '-At', '-c', statement];
  if (useDocker) return execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'psql', ...common], { cwd: root, encoding: 'utf8' });
  return execFileSync('psql', ['-h', '127.0.0.1', ...common], { cwd: root, encoding: 'utf8', env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD || 'drift' } });
}

function databaseExists(name) {
  return psql('postgres', `SELECT COUNT(*) FROM pg_database WHERE datname = '${name}'`).trim() !== '0';
}

function generateName() {
  return `drift_cdg130_${new Date().toISOString().replace(/\D/g, '').slice(0, 14)}_${randomBytes(6).toString('hex')}`;
}

export async function createOwnedDatabase({ names } = {}) {
  for (const name of names ?? Array.from({ length: 5 }, generateName)) {
    if (blocked.has(name) || !ownedName.test(name) || databaseExists(name)) continue;
    try { psql('postgres', `CREATE DATABASE ${name}`); } catch (error) {
      const detail = `${error.stderr ?? ''}${error.message ?? ''}`;
      if (/already exists/i.test(detail)) continue;
      throw new Error(`Could not create ${name}. No existing database was dropped. ${detail.trim()}`);
    }
    createdByThisProcess.add(name);
    const token = randomBytes(32).toString('hex');
    try { psql(name, `COMMENT ON DATABASE ${name} IS '${token}'`); } catch (error) {
      try { await dropOwnedDatabase(name); } catch (dropError) {
        throw new Error(`Ownership was not recorded for ${name}, and dropping that new database failed. ${dropError.message}`);
      }
      throw new Error(`Ownership was not recorded for ${name}. The new database was dropped. ${error.message}`);
    }
    return { name, token };
  }
  throw new Error('Could not create a new disposable database. Existing databases were left in place.');
}

export async function dropOwnedDatabase(name) {
  if (!createdByThisProcess.has(name) || blocked.has(name) || !ownedName.test(name)) throw new Error(`Refusing to drop ${name}; this process did not create it.`);
  psql('postgres', `SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '${name}' AND pid <> pg_backend_pid()`);
  psql('postgres', `DROP DATABASE ${name}`);
  createdByThisProcess.delete(name);
}

function listeners(port) {
  try {
    const output = execFileSync('lsof', ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN', '-Fp'], { encoding: 'utf8' });
    return [...new Set([...output.matchAll(/^p(\d+)$/gm)].map(match => Number(match[1])))];
  } catch (error) { if (error.status === 1) return []; throw error; }
}

function processCwd(pid) {
  const output = execFileSync('lsof', ['-a', '-p', String(pid), '-d', 'cwd', '-Fn'], { encoding: 'utf8' });
  return output.split('\n').find(line => line.startsWith('n'))?.slice(1) ?? '';
}

function assertPortsAvailable() {
  const backend = listeners(8080);
  if (backend.length) throw new Error(`Port 8080 is already in use by pid ${backend.join(', ')}. This runner will not use or stop that process.`);
  for (const pid of listeners(5173)) if (processCwd(pid) !== frontendDir) throw new Error(`Port 5173 is already in use by pid ${pid} outside this frontend. This runner will not stop it.`);
}

function backendEnv(owned) {
  const url = `jdbc:postgresql://localhost:5432/${owned.name}`;
  return { ...process.env, JWT_SECRET: randomBytes(48).toString('base64url'), AIS_ENABLED: 'false', SERVER_PORT: '8080',
    SPRING_DATASOURCE_URL: url, SPRING_DATASOURCE_USERNAME: 'drift', SPRING_DATASOURCE_PASSWORD: 'drift',
    SPRING_FLYWAY_URL: url, SPRING_FLYWAY_USER: 'drift', SPRING_FLYWAY_PASSWORD: 'drift' };
}

function defaultSpawnBackend(env) {
  return spawn('bash', ['./mvnw', 'spring-boot:run'], { cwd: backendDir, env, detached: true, stdio: ['ignore', 'pipe', 'pipe'] });
}

function waitForBackend(child, owned) {
  const url = `jdbc:postgresql://localhost:5432/${owned.name}`;
  let log = '';
  child.stdout?.on('data', chunk => { log += chunk.toString(); });
  child.stderr?.on('data', chunk => { log += chunk.toString(); });
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = error => {
      if (settled) return; settled = true; clearTimeout(timer); clearInterval(check);
      child.off('exit', onExit); child.off('error', onError); error ? reject(error) : resolve();
    };
    const timer = setTimeout(() => finish(new Error('The backend did not start within 180 seconds.')), 180000);
    const onExit = code => finish(new Error(`The backend exited before it started (status ${code ?? 'unknown'}).`));
    const onError = error => finish(error);
    child.on('exit', onExit); child.on('error', onError);
    const check = setInterval(() => {
      if (!log.includes('Started BackendApplication')) return;
      const urls = log.match(/jdbc:postgresql:\S+/g) ?? [];
      if (!urls.some(item => item.startsWith(url)) || urls.some(item => item.startsWith('jdbc:postgresql:') && !item.startsWith(url))) return finish(new Error('The backend did not use the database this runner created.'));
      finish();
    }, 200);
  });
}

function assertListenerIsOurs(child) {
  const group = execFileSync('ps', ['-o', 'pgid=', '-p', String(child.pid)], { encoding: 'utf8' }).trim();
  const pids = listeners(8080);
  if (!pids.length) throw new Error('The backend this runner started is not listening on port 8080.');
  for (const pid of pids) if (execFileSync('ps', ['-o', 'pgid=', '-p', String(pid)], { encoding: 'utf8' }).trim() !== group) throw new Error(`Port 8080 is served by pid ${pid}, not the backend this runner started.`);
}

function runPlaywright(owned, args) {
  const child = spawn('npx', args ?? ['playwright', 'test', 'e2e/shipment-import.spec.ts', '--project=desktop'], { cwd: frontendDir, env: { ...process.env, DRIFT_E2E_DATABASE: owned.name, DRIFT_E2E_OWNER_TOKEN: owned.token }, stdio: 'inherit' });
  activePlaywright = child;
  return new Promise((resolve, reject) => {
    const onError = error => finish(error); const onExit = code => finish(null, code);
    let settled = false;
    const finish = (error, code) => { if (settled) return; settled = true; child.off('error', onError); child.off('exit', onExit); activePlaywright = null; error ? reject(error) : resolve(code ?? 1); };
    child.on('error', onError); child.on('exit', onExit);
  });
}

async function stopProcessGroup(child) {
  if (!child?.pid || child.exitCode !== null || child.signalCode) return;
  const exited = new Promise(resolve => child.once('exit', () => resolve(true)));
  const waitForExit = timeout => Promise.race([exited, new Promise(resolve => setTimeout(() => resolve(false), timeout))]);
  try { process.kill(-child.pid, 'SIGTERM'); } catch (error) { if (error.code !== 'ESRCH') throw error; }
  if (await waitForExit(15000)) return;
  try { process.kill(-child.pid, 'SIGKILL'); } catch (error) { if (error.code !== 'ESRCH') throw error; }
  if (!(await waitForExit(5000))) throw new Error('A process this runner started did not exit after it was signalled.');
}

export async function runShipmentImport(options = {}) {
  assertPortsAvailable();
  const owned = await (options.createDatabase ?? createOwnedDatabase)();
  let code = 1; let failure = null; const cleanupFailures = [];
  try {
    activeBackend = (options.spawnBackend ?? defaultSpawnBackend)(backendEnv(owned));
    await waitForBackend(activeBackend, owned);
    if (!options.spawnBackend) assertListenerIsOurs(activeBackend);
    code = await runPlaywright(owned, options.playwrightArgs);
  } catch (error) { failure = error; } finally {
    const backend = activeBackend; activeBackend = null;
    if (backend) try { await stopProcessGroup(backend); } catch (error) { cleanupFailures.push(error.message); }
    if (createdByThisProcess.has(owned.name)) try { await dropOwnedDatabase(owned.name); } catch (error) { cleanupFailures.push(error.message); }
  }
  if (cleanupFailures.length) throw new Error(`Cleanup failed.${failure instanceof Error ? ` ${failure.message}` : ''} ${cleanupFailures.join(' ')}`);
  if (failure) throw failure;
  return code;
}

function stopForSignal() {
  signals += 1;
  if (signals > 1) { console.error('Cleanup was interrupted and is not guaranteed.'); process.exit(1); }
  try { activePlaywright?.kill('SIGTERM'); } catch { /* cleanup path reports leftovers */ }
  try { if (activeBackend) process.kill(-activeBackend.pid, 'SIGTERM'); } catch { /* cleanup path reports leftovers */ }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  process.on('SIGINT', stopForSignal); process.on('SIGTERM', stopForSignal);
  runShipmentImport().then(code => { process.exitCode = signals ? 130 : code; }).catch(error => { console.error(error instanceof Error ? error.message : error); process.exitCode = 1; });
}
