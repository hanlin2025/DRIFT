import { createHash, randomBytes } from 'node:crypto';
import { spawn, execFileSync, spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const backendDir = path.join(root, 'backend');
const blocked = new Set(['drift', 'drift_test', 'postgres', 'template0', 'template1']);
const ownedName = /^drift_cdg312_[0-9]{14}_[a-f0-9]{12}$/;
const backendUrl = 'http://localhost:8080';
const mailpitUrl = process.env.MAILPIT_URL || 'http://localhost:8025';
const createdByThisProcess = new Set();
let activeBackend = null;
let signals = 0;

function dockerCompose() {
  const probe = spawnSync('docker', ['compose', 'version'], { cwd: root, encoding: 'utf8' });
  return probe.status === 0;
}

const useDocker = dockerCompose();

function psql(database, statement, variables = {}) {
  const common = ['-U', 'drift', '-d', database, '-v', 'ON_ERROR_STOP=1', '-At',
    ...Object.entries(variables).flatMap(([key, value]) => ['-v', `${key}=${value}`])];
  if (useDocker) {
    return execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'psql', ...common],
      { cwd: root, input: statement, encoding: 'utf8' });
  }
  return execFileSync('psql', ['-h', '127.0.0.1', ...common], {
    cwd: root,
    input: statement,
    encoding: 'utf8',
    env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD || 'drift' },
  });
}

function databaseExists(name) {
  return psql('postgres', `SELECT COUNT(*) FROM pg_database WHERE datname = '${name}'`).trim() !== '0';
}

function generateName() {
  const stamp = new Date().toISOString().replace(/\D/g, '').slice(0, 14);
  return `drift_cdg312_${stamp}_${randomBytes(6).toString('hex')}`;
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

function assertPortsAvailable() {
  const backend = listeners(8080);
  if (backend.length) {
    throw new Error(`Port 8080 is already in use by pid ${backend.join(', ')}. This runner will not use or stop that process.`);
  }
}

async function assertMailpitRunning() {
  try {
    const response = await fetch(`${mailpitUrl}/api/v1/info`);
    if (response.ok) return;
  } catch {
    // reported below
  }
  throw new Error(`Mailpit is not reachable at ${mailpitUrl}. Start it with: docker compose up -d mailpit`);
}

function backendEnv(owned) {
  const url = `jdbc:postgresql://localhost:5432/${owned.name}`;
  return {
    ...process.env,
    JWT_SECRET: randomBytes(48).toString('base64url'),
    AIS_ENABLED: 'false',
    SERVER_PORT: '8080',
    SPRING_DATASOURCE_URL: url,
    SPRING_DATASOURCE_USERNAME: 'drift',
    SPRING_DATASOURCE_PASSWORD: 'drift',
    SPRING_FLYWAY_URL: url,
    SPRING_FLYWAY_USER: 'drift',
    SPRING_FLYWAY_PASSWORD: 'drift',
    MAIL_HOST: process.env.MAIL_HOST || 'localhost',
    MAIL_PORT: process.env.MAIL_PORT || '1025',
    PASSWORD_RESET_LINK_BASE_URL: 'http://localhost:5173/reset-password',
  };
}

function defaultSpawnBackend(env) {
  return spawn('bash', ['./mvnw', 'spring-boot:run'], {
    cwd: backendDir, env, detached: true, stdio: ['ignore', 'pipe', 'pipe'],
  });
}

function waitForBackend(child, owned) {
  const url = `jdbc:postgresql://localhost:5432/${owned.name}`;
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

function check(condition, message) {
  if (!condition) throw new Error(`Password reset round trip failed: ${message}`);
}

async function api(pathname, { method = 'POST', body, token } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(`${backendUrl}${pathname}`, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : null, cacheControl: response.headers.get('cache-control') };
}

async function mailTo(email, { attempts = 75 } = {}) {
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    const search = await fetch(`${mailpitUrl}/api/v1/search?query=${encodeURIComponent(`to:"${email}"`)}`);
    check(search.ok, `Mailpit search returned ${search.status}`);
    const { messages = [] } = await search.json();
    if (messages.length) {
      const message = await fetch(`${mailpitUrl}/api/v1/message/${messages[0].ID}`);
      check(message.ok, `Mailpit message returned ${message.status}`);
      return { count: messages.length, ...(await message.json()) };
    }
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  return null;
}

function tokenFromMail(text) {
  const link = text.match(/https?:\/\/\S+#token=\S+/)?.[0];
  check(link, 'the email does not contain a reset link');
  const url = new URL(link);
  check(url.pathname === '/reset-password', `the link points at ${url.pathname}, not /reset-password`);
  check(!url.search, 'the token must be in the fragment, not the query string');
  const token = decodeURIComponent(new URLSearchParams(url.hash.slice(1)).get('token') ?? '');
  check(/^[A-Za-z0-9_-]{43}$/.test(token), 'the token is not 43 URL-safe characters');
  return token;
}

async function registerAccount(owned, email, password) {
  const invitation = randomBytes(32).toString('base64url');
  psql(owned.name, `INSERT INTO invitations (email, company_id, role, token_hash, expires_at)
    SELECT :'email', id, 'FREIGHT_FORWARDER', :'hash', NOW() + INTERVAL '1 hour'
    FROM companies WHERE code = 'HARBOURLINE_DEMO';`, {
    email, hash: createHash('sha256').update(invitation).digest('hex'),
  });
  const registered = await api('/api/register', {
    body: { fullName: 'Reset Check', email, password, invitationToken: invitation },
  });
  check(registered.status === 201, `registration returned ${registered.status}`);
}

export async function runRoundTrip(owned) {
  const suffix = randomBytes(8).toString('hex');
  const email = `cdg312-${suffix}@example.com`;
  const oldPassword = 'OldPass123';
  const newPassword = 'NewPass456';
  await registerAccount(owned, email, oldPassword);
  const before = await api('/api/login', { body: { email, password: oldPassword } });
  check(before.status === 200, `login before the reset returned ${before.status}`);

  const unknownEmail = `cdg312-missing-${suffix}@example.com`;
  const unknown = await api('/api/auth/forgot-password', { body: { email: unknownEmail } });
  const forgot = await api('/api/auth/forgot-password', { body: { email } });
  check(forgot.status === 200 && forgot.cacheControl === 'no-store', `forgot-password returned ${forgot.status}`);
  check(unknown.status === 200 && JSON.stringify(unknown.body) === JSON.stringify(forgot.body),
    'an unknown email must get the same response as a registered one');
  check(!JSON.stringify(forgot.body).includes('token'), 'forgot-password must not return a token');

  const mail = await mailTo(email);
  check(mail, `no email reached Mailpit for ${email}`);
  check(mail.count === 1, `expected one email, found ${mail.count}`);
  const token = tokenFromMail(mail.Text);
  console.log('Reset email received in Mailpit');
  check(await mailTo(unknownEmail, { attempts: 5 }) === null, 'an unknown email must not be sent anything');

  const reset = await api('/api/auth/reset-password', { body: { token, password: newPassword } });
  check(reset.status === 200, `reset-password returned ${reset.status}: ${JSON.stringify(reset.body)}`);
  check(reset.cacheControl === 'no-store', 'reset-password must not be cached');
  console.log('Password reset with the emailed link');

  const session = await api('/api/session', { method: 'GET', token: before.body.token });
  check(session.status === 401, `a session from before the reset returned ${session.status}`);
  const withNew = await api('/api/login', { body: { email, password: newPassword } });
  check(withNew.status === 200, `login with the new password returned ${withNew.status}`);
  const withOld = await api('/api/login', { body: { email, password: oldPassword } });
  check(withOld.status === 401, `login with the old password returned ${withOld.status}`);
  console.log('New password logs in; old password and old session are refused');

  const reused = await api('/api/auth/reset-password', { body: { token, password: 'Another789' } });
  check(reused.status === 400, `reusing the link returned ${reused.status}`);
  check(reused.body?.errors?.token === 'This reset link is invalid or has expired. Please request a new one.',
    `reusing the link returned ${JSON.stringify(reused.body)}`);
  const stillNew = await api('/api/login', { body: { email, password: newPassword } });
  check(stillNew.status === 200, 'a reused link must not change the password');
  console.log('Reused link gets the invalid-link 400');
  console.log('Password reset round trip passed');
  return 0;
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

export async function runPasswordReset(options = {}) {
  assertPortsAvailable();
  await assertMailpitRunning();
  const owned = await (options.createDatabase ?? createOwnedDatabase)();
  let testCode = 1;
  let failure = null;
  const failures = [];
  try {
    activeBackend = (options.spawnBackend ?? defaultSpawnBackend)(backendEnv(owned));
    await waitForBackend(activeBackend, owned);
    if (!options.spawnBackend) assertListenerIsOurs(activeBackend);
    testCode = await (options.roundTrip ?? runRoundTrip)(owned);
  } catch (error) {
    failure = error;
  } finally {
    const backend = activeBackend;
    activeBackend = null;
    if (backend) {
      try {
        await stopProcessGroup(backend);
      } catch (error) {
        failures.push(error instanceof Error ? error.message : String(error));
      }
    }
    if (owned && createdByThisProcess.has(owned.name)) {
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
  if (activeBackend) {
    try { process.kill(-activeBackend.pid, 'SIGTERM'); } catch { /* the exit path reports a leftover process */ }
  }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  process.on('SIGINT', stopForSignal);
  process.on('SIGTERM', stopForSignal);
  runPasswordReset().then(code => {
    process.exitCode = signals > 0 ? 130 : code;
  }).catch(error => {
    console.error(error instanceof Error ? error.message : error);
    process.exitCode = 1;
  });
}
