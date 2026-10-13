import { randomBytes } from 'node:crypto';
import { spawn, execFileSync, spawnSync } from 'node:child_process';
import { readFileSync, rmSync } from 'node:fs';
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
const ownedName = /^drift_cdg83_[0-9]{14}_[a-f0-9]{12}$/;
const blocked = new Set(['drift', 'drift_test', 'postgres', 'template0', 'template1']);
const createdByThisProcess = new Set();
let connection = null;
let activeBackend = null;
let activePlaywright = null;
let signals = 0;

function psql(database, statement) {
  if (!connection) throw new Error('PostgreSQL connection has not been selected.');
  if (!/^[A-Za-z0-9_]+$/.test(database)) {
    throw new Error(`Refusing database name ${database}`);
  }
  const common = ['-U', connection.user, '-d', database, '-v', 'ON_ERROR_STOP=1', '-At', '-c', statement];
  if (connection.mode === 'compose') {
    return execFileSync('docker', ['compose', '-p', connection.project, 'exec', '-T', 'postgres', 'psql', ...common], {
      cwd: root,
      encoding: 'utf8',
      env: { ...process.env, PGPASSWORD: connection.password },
    }).trim();
  }
  return execFileSync('psql', ['-h', connection.host, '-p', connection.port, ...common], {
    encoding: 'utf8',
    env: { ...process.env, PGPASSWORD: connection.password },
  }).trim();
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
    connection = { mode: 'compose', project, host, port, user: dbUser, password: dbPassword, systemIdentifier: '' };
    const identity = psql(adminDatabase, 'SELECT system_identifier::text FROM pg_control_system()');
    const serverPort = psql(adminDatabase, 'SHOW port');
    if (!/^[0-9]+$/.test(identity) || serverPort !== port) {
      throw new Error('The selected Compose Postgres did not identify itself on port 5432. No database was created.');
    }
    connection.systemIdentifier = identity;
    console.log(`PostgreSQL connection: mode=compose project=${project} service=postgres system_identifier=${identity} host=${host} port=${port}`);
    return;
  }
  connection = { mode: 'local', project: '', host, port, user: dbUser, password: dbPassword, systemIdentifier: '' };
  let identity = '';
  try {
    identity = psql(adminDatabase, 'SELECT system_identifier::text FROM pg_control_system()');
  } catch (error) {
    throw new Error(`docker compose is unavailable and local psql could not reach ${host}:${port}. No database was created. ${error instanceof Error ? error.message : error}`);
  }
  const serverPort = psql(adminDatabase, 'SHOW port');
  if (!/^[0-9]+$/.test(identity) || serverPort !== port) {
    throw new Error('The selected local Postgres did not identify itself on port 5432. No database was created.');
  }
  connection.systemIdentifier = identity;
  console.log(`PostgreSQL connection: mode=local system_identifier=${identity} host=${host} port=${port}`);
}

function assertSameInstance(database) {
  const found = psql(database, 'SELECT system_identifier::text FROM pg_control_system()');
  if (found !== connection.systemIdentifier) {
    throw new Error(`PostgreSQL instance changed from ${connection.systemIdentifier} to ${found}.`);
  }
}

function generateName() {
  const stamp = new Date().toISOString().replace(/\D/g, '').slice(0, 14);
  return `drift_cdg83_${stamp}_${randomBytes(6).toString('hex')}`;
}

function databaseExists(name) {
  return psql(adminDatabase, `SELECT COUNT(*) FROM pg_database WHERE datname = '${name}'`) !== '0';
}

function createOwnedDatabase() {
  for (let attempt = 0; attempt < 5; attempt += 1) {
    const name = generateName();
    if (blocked.has(name) || !ownedName.test(name) || databaseExists(name)) continue;
    psql(adminDatabase, `CREATE DATABASE ${name}`);
    createdByThisProcess.add(name);
    const token = randomBytes(32).toString('hex');
    psql(adminDatabase, `COMMENT ON DATABASE ${name} IS '${token}'`);
    const current = psql(name, 'SELECT current_database()');
    const tables = psql(name, `SELECT COUNT(*) FROM pg_catalog.pg_class AS relation
      JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = relation.relnamespace
      WHERE namespace.nspname = 'public' AND relation.relkind IN ('r', 'p', 'v', 'm')`);
    const history = psql(name, "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL");
    const comment = psql(name, `SELECT coalesce(pg_catalog.shobj_description(oid, 'pg_database'), '')
      FROM pg_database WHERE datname = current_database()`);
    if (current !== name || tables !== '0' || !['f', 'false'].includes(history) || comment !== token) {
      throw new Error(`Refusing to start against ${name}: it was not a fresh owned database. It will be left in place.`);
    }
    return { name, token };
  }
  throw new Error('Could not create a new disposable database. Existing databases were left in place.');
}

function ownershipMatches(name, token) {
  if (!createdByThisProcess.has(name) || blocked.has(name) || !ownedName.test(name)) return false;
  const found = psql(adminDatabase, `SELECT coalesce(pg_catalog.shobj_description(oid, 'pg_database'), '')
    FROM pg_database WHERE datname = '${name}'`);
  return found === token;
}

function dropOwnedDatabase(name, token) {
  if (!ownershipMatches(name, token)) {
    throw new Error(`Refusing to drop ${name}; ownership could not be confirmed. It was left in place.`);
  }
  let lastError = null;
  for (let attempt = 0; attempt < 10; attempt += 1) {
    try {
      psql(adminDatabase, `DROP DATABASE ${name}`);
      createdByThisProcess.delete(name);
      return;
    } catch (error) {
      lastError = error;
      execFileSync('sleep', ['1']);
    }
  }
  throw new Error(`Database ${name} was left in place. ${lastError instanceof Error ? lastError.message : lastError}`);
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
      throw new Error(`Port ${listenPort} is already in use by pid ${pids.join(', ')}. This runner will not stop that process or create a database.`);
    }
  }
}

function jdbcUrl(name) {
  return `jdbc:postgresql://${host}:${port}/${name}`;
}

function backendEnv(owned) {
  const url = jdbcUrl(owned.name);
  return {
    ...process.env,
    JWT_SECRET: randomBytes(48).toString('base64url'),
    AIS_ENABLED: 'false',
    APP_AIS_ENABLED: 'false',
    SERVER_PORT: '8080',
    SPRING_DATASOURCE_URL: url,
    SPRING_DATASOURCE_USERNAME: dbUser,
    SPRING_DATASOURCE_PASSWORD: dbPassword,
    SPRING_FLYWAY_URL: url,
    SPRING_FLYWAY_USER: dbUser,
    SPRING_FLYWAY_PASSWORD: dbPassword,
    SPRING_MAIL_HOST: host,
    SPRING_MAIL_PORT: '1025',
  };
}

function backendArguments(owned) {
  const url = jdbcUrl(owned.name);
  const report = `/tmp/drift-cdg83-guard-${owned.token}.json`;
  return {
    report,
    arguments: [
      `--spring.datasource.url=${url}`,
      `--spring.datasource.username=${dbUser}`,
      `--spring.datasource.password=${dbPassword}`,
      `--spring.flyway.url=${url}`,
      `--spring.flyway.user=${dbUser}`,
      `--spring.flyway.password=${dbPassword}`,
      '--app.ais.enabled=false',
      `--spring.mail.host=${host}`,
      '--spring.mail.port=1025',
      '--server.address=127.0.0.1',
      '--server.port=8080',
      `--drift.startup-guard.expected-url=${url}`,
      `--drift.startup-guard.ownership-token=${owned.token}`,
      `--drift.startup-guard.report=${report}`,
    ].join(' '),
  };
}

function compileTests() {
  execFileSync('bash', ['./mvnw', '-DskipTests', 'test-compile'], {
    cwd: backendDir,
    encoding: 'utf8',
    timeout: 180000,
    stdio: 'inherit',
  });
}

function spawnBackend(owned) {
  const launch = backendArguments(owned);
  const child = spawn('bash', [
    './mvnw',
    '-Dspring-boot.run.useTestClasspath=true',
    `-Dspring-boot.run.additional-classpath-elements=${path.join(backendDir, 'target/test-classes')}`,
    '-Dspring-boot.run.main-class=com.drift.backend.e2e.GuardedTestApplication',
    `-Dspring-boot.run.arguments=${launch.arguments}`,
    'spring-boot:run',
  ], {
    cwd: backendDir,
    env: backendEnv(owned),
    detached: true,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  return { child, report: launch.report };
}

function rememberOutput(child) {
  let log = '';
  const append = chunk => {
    log = (log + chunk.toString()).slice(-8000);
  };
  child.stdout?.on('data', append);
  child.stderr?.on('data', append);
  return () => log;
}

function waitForReport(child, reportPath) {
  const deadline = Date.now() + 240000;
  return new Promise((resolve, reject) => {
    const check = setInterval(() => {
      if (child.exitCode !== null || child.signalCode) {
        clearInterval(check);
        reject(new Error('The backend exited before pre-migration validation was recorded.'));
        return;
      }
      try {
        const report = JSON.parse(readFileSync(reportPath, 'utf8'));
        clearInterval(check);
        resolve(report);
      } catch (error) {
        if (error.code !== 'ENOENT' && !(error instanceof SyntaxError)) {
          clearInterval(check);
          reject(error);
          return;
        }
        if (Date.now() > deadline) {
          clearInterval(check);
          reject(new Error('Pre-migration validation was not recorded within 240 seconds.'));
        }
      }
    }, 250);
  });
}

function assertReport(report, owned) {
  const url = jdbcUrl(owned.name);
  const failures = [];
  if (report.phase !== 'before-context-refresh') failures.push('phase');
  if (report.validatedBeforeContextRefresh !== true) failures.push('validation flag');
  if (report.flywaySchemaHistoryPresent !== false) failures.push('Flyway history already existed');
  if (report.publicTableCount !== 0) failures.push('public tables already existed');
  if (report.currentDatabase !== owned.name) failures.push('database name');
  if (report.serverPort !== 5432) failures.push('server port');
  if (report.datasourceUrl !== url || report.flywayUrl !== url) failures.push('JDBC URL');
  if (report.datasourceUsername !== dbUser || report.flywayUser !== dbUser) failures.push('database user');
  if (report.aisProperty !== 'app.ais.enabled' || report.aisValue !== 'false') failures.push('AIS');
  if (report.mailHost !== host || report.mailPort !== '1025') failures.push('mail');
  if (failures.length) {
    throw new Error(`Pre-migration validation rejected startup: ${failures.join(', ')}.`);
  }
}

function processGroup(pid) {
  return execFileSync('ps', ['-o', 'pgid=', '-p', String(pid)], { encoding: 'utf8' }).trim();
}

function waitForListener(child) {
  const deadline = Date.now() + 60000;
  const group = processGroup(child.pid);
  return new Promise((resolve, reject) => {
    const check = setInterval(() => {
      if (child.exitCode !== null || child.signalCode) {
        clearInterval(check);
        reject(new Error('The backend exited after validation and before it listened.'));
        return;
      }
      const pids = listeners(8080);
      if (!pids.length) {
        if (Date.now() > deadline) {
          clearInterval(check);
          reject(new Error('The validated backend did not listen on 127.0.0.1:8080 within 60 seconds.'));
        }
        return;
      }
      for (const pid of pids) {
        const listenerGroup = processGroup(pid);
        if (listenerGroup !== group) {
          clearInterval(check);
          reject(new Error(`Port 8080 is served by pid ${pid}, not the backend this runner started.`));
          return;
        }
      }
      clearInterval(check);
      resolve();
    }, 250);
  });
}

function runPlaywright(owned, reportPath) {
  const child = spawn('npx', ['playwright', 'test', 'e2e/shipment-archive.spec.ts', '--project=desktop'], {
    cwd: frontendDir,
    env: {
      ...process.env,
      DRIFT_E2E_DATABASE: owned.name,
      DRIFT_E2E_OWNER_TOKEN: owned.token,
      DRIFT_E2E_GUARD_REPORT: reportPath,
      DRIFT_E2E_DB_MODE: connection.mode,
      DRIFT_E2E_COMPOSE_PROJECT: connection.project,
      DRIFT_E2E_SYSTEM_IDENTIFIER: connection.systemIdentifier,
      DRIFT_E2E_DB_HOST: host,
      DRIFT_E2E_DB_PORT: port,
      DRIFT_E2E_DB_USER: dbUser,
      DRIFT_E2E_DB_PASSWORD: dbPassword,
      DRIFT_API_ORIGIN: `http://${host}:8080`,
    },
    stdio: 'inherit',
  });
  activePlaywright = child;
  return new Promise((resolve, reject) => {
    child.on('error', reject);
    child.on('exit', code => {
      activePlaywright = null;
      resolve(code ?? 1);
    });
  });
}

async function stopProcessGroup(child) {
  if (!child?.pid || child.exitCode !== null || child.signalCode) return;
  const exited = new Promise(resolve => child.once('exit', () => resolve(true)));
  const wait = timeout => Promise.race([
    exited,
    new Promise(resolve => setTimeout(() => resolve(false), timeout)),
  ]);
  process.kill(-child.pid, 'SIGTERM');
  if (await wait(15000)) return;
  process.kill(-child.pid, 'SIGKILL');
  if (await wait(5000)) return;
  throw new Error(`Process ${child.pid} started by this runner did not exit. It was left running.`);
}

async function run() {
  assertPortsAvailable();
  selectConnection();
  compileTests();
  const owned = createOwnedDatabase();
  let reportPath = '';
  let testCode = 1;
  let failure = null;
  const failures = [];
  try {
    assertSameInstance(owned.name);
    const launched = spawnBackend(owned);
    activeBackend = launched.child;
    reportPath = launched.report;
    const log = rememberOutput(activeBackend);
    try {
      const report = await waitForReport(activeBackend, reportPath);
      assertReport(report, owned);
      console.log(`Pre-migration validation: ${JSON.stringify(report)}`);
      await waitForListener(activeBackend);
      assertSameInstance(owned.name);
      const migrated = psql(owned.name, "SELECT string_agg(version, ',' ORDER BY installed_rank) FROM flyway_schema_history");
      console.log(`Flyway history after the validated backend started: ${migrated}`);
    } catch (error) {
      const detail = log();
      throw new Error(`${error.message}${detail ? `\n${detail}` : ''}`);
    }
    testCode = await runPlaywright(owned, reportPath);
    if (testCode !== 0) throw new Error(`Playwright exited with status ${testCode}.`);
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
      if (failures.some(item => item.includes('left running'))) {
        failures.push(`Database ${owned.name} was left in place because its backend process could not be confirmed stopped.`);
      } else {
        try {
          dropOwnedDatabase(owned.name, owned.token);
        } catch (error) {
          failures.push(error instanceof Error ? error.message : String(error));
        }
      }
    }
    if (reportPath) {
      try {
        rmSync(reportPath, { force: true });
      } catch (error) {
        failures.push(error instanceof Error ? error.message : String(error));
      }
    }
  }
  if (failures.length || failure) {
    const earlier = failure instanceof Error ? failure.message : '';
    throw new Error(`${earlier}${failures.length ? `\nCleanup: ${failures.join(' ')}` : ''}`.trim());
  }
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
  if (activeBackend?.pid) {
    try { process.kill(-activeBackend.pid, 'SIGTERM'); } catch { /* same */ }
  }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  process.on('SIGINT', stopForSignal);
  process.on('SIGTERM', stopForSignal);
  run().then(code => {
    process.exitCode = signals > 0 ? 130 : code;
  }).catch(error => {
    console.error(error instanceof Error ? error.message : error);
    process.exitCode = 1;
  });
}
