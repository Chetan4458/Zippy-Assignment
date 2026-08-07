import { spawn, spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';

const children = new Map();
let shuttingDown = false;
let shutdownPromise;
let backendReady = false;
const backendPortValue = process.env.SERVER_PORT || '8080';
const backendPort = Number(backendPortValue);

if (!/^\d{1,5}$/u.test(backendPortValue) || !Number.isInteger(backendPort) || backendPort < 1 || backendPort > 65_535) {
  console.error('SERVER_PORT must be an integer between 1 and 65535.');
  process.exit(1);
}

const backendHealthUrl = `http://127.0.0.1:${backendPort}/api/health`;

function quoteWindows(value) {
  if (/[^A-Za-z0-9_./:-]/u.test(value)) {
    return `"${value.replaceAll('"', '\\"')}"`;
  }
  return value;
}

function commandSpec(command, args) {
  if (process.platform !== 'win32' || command === process.execPath) {
    return { command, args };
  }

  const commandLine = [command, ...args].map(quoteWindows).join(' ');
  return {
    command: process.env.ComSpec ?? 'cmd.exe',
    args: ['/d', '/s', '/c', commandLine],
  };
}

function ensureFrontendDependencies() {
  const requiredFiles = [
    'node_modules/@vitejs/plugin-react/package.json',
    'node_modules/react/package.json',
    'node_modules/react-dom/package.json',
    'node_modules/vite/bin/vite.js',
  ];

  if (requiredFiles.every((file) => existsSync(file))) {
    return;
  }

  console.log('Frontend dependencies are missing; running npm ci...');
  const spec = commandSpec('npm', ['ci', '--no-audit', '--no-fund']);
  const install = spawnSync(spec.command, spec.args, {
    stdio: 'inherit',
    shell: false,
  });

  if (install.error) {
    throw new Error(`Unable to run npm ci: ${install.error.message}`);
  }

  if (install.status !== 0 || !requiredFiles.every((file) => existsSync(file))) {
    throw new Error('npm ci did not install the required frontend dependencies.');
  }
}

function start(label, command, args) {
  const spec = commandSpec(command, args);
  const child = spawn(spec.command, spec.args, {
    stdio: 'inherit',
    shell: false,
    detached: process.platform !== 'win32',
  });

  children.set(child, label);

  child.once('error', (error) => {
    console.error(`${label} failed to start: ${error.message}`);
    if (!shuttingDown) {
      void shutdown(1);
    }
  });

  child.once('exit', (code, signal) => {
    children.delete(child);
    if (!shuttingDown) {
      const reason = signal ? `signal ${signal}` : `exit code ${code ?? 'unknown'}`;
      const failureCode = Number.isInteger(code) && code > 0 ? code : 1;

      if (label === 'Backend' && !backendReady) {
        console.error(`Backend exited before ${backendHealthUrl} reported ready (${reason}).`);
        if (code === 0) {
          console.error('The Maven wrapper reported exit code 0, but Spring Boot did not start successfully; the launcher will exit with code 1.');
        } else {
          console.error(`The launcher will exit with code ${failureCode}.`);
        }
        console.error('Review the Spring Boot/Maven output above for the original startup failure. Run `npm start` to reproduce it without the frontend supervisor.');
      } else {
        console.error(`${label} stopped unexpectedly (${reason}); the launcher will exit with code ${failureCode}.`);
      }

      void shutdown(failureCode);
    }
  });

  return child;
}

function hasExited(child) {
  return child.exitCode !== null || child.signalCode !== null;
}

async function stop(child) {
  if (!child.pid || hasExited(child)) {
    return;
  }

  if (process.platform === 'win32') {
    // Ctrl+C is delivered to the shared Windows console before this handler runs.
    // Give Spring/JVM shutdown hooks time to close the file-backed database first.
    await Promise.race([
      new Promise((resolve) => child.once('exit', resolve)),
      delay(5000),
    ]);
    if (hasExited(child)) return;

    await new Promise((resolve) => {
      const killer = spawn('taskkill.exe', ['/pid', String(child.pid), '/t'], {
        stdio: 'ignore',
        shell: false,
      });
      killer.once('error', resolve);
      killer.once('exit', resolve);
    });
    await Promise.race([
      new Promise((resolve) => child.once('exit', resolve)),
      delay(3000),
    ]);
    if (hasExited(child)) return;

    await new Promise((resolve) => {
      const killer = spawn('taskkill.exe', ['/pid', String(child.pid), '/t', '/f'], {
        stdio: 'ignore',
        shell: false,
      });
      killer.once('error', resolve);
      killer.once('exit', resolve);
    });
    return;
  }

  try {
    process.kill(-child.pid, 'SIGTERM');
  } catch {
    child.kill('SIGTERM');
  }

  await Promise.race([
    new Promise((resolve) => child.once('exit', resolve)),
    delay(3000),
  ]);

  if (!hasExited(child)) {
    try {
      process.kill(-child.pid, 'SIGKILL');
    } catch {
      child.kill('SIGKILL');
    }
  }
}

function shutdown(code = 0) {
  if (shutdownPromise) {
    return shutdownPromise;
  }

  shuttingDown = true;
  shutdownPromise = Promise.allSettled([...children.keys()].map(stop)).then(() => {
    process.exit(code);
  });
  return shutdownPromise;
}

process.once('SIGINT', () => void shutdown(0));
process.once('SIGTERM', () => void shutdown(0));

try {
  ensureFrontendDependencies();
} catch (error) {
  console.error(error instanceof Error ? error.message : error);
  process.exit(1);
}

const backend = start('Backend', 'mvn', ['-q', '-f', 'backend/pom.xml', 'spring-boot:run']);

async function waitForBackend() {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    if (hasExited(backend) || shuttingDown) {
      return false;
    }

    try {
      const response = await fetch(backendHealthUrl, {
        signal: AbortSignal.timeout(1000),
      });
      if (response.ok) {
        backendReady = true;
        return true;
      }
    } catch {
      // Keep polling until Spring Boot is ready.
    }

    await delay(1000);
  }

  return false;
}

if (!(await waitForBackend())) {
  if (!shuttingDown) {
    console.error(`Backend did not become ready on ${backendHealthUrl} within 60 seconds; the launcher will exit with code 1.`);
    console.error('Review the Spring Boot/Maven output above for the original startup failure. Run `npm start` to reproduce it directly.');
    await shutdown(1);
  }
} else if (!shuttingDown) {
  start('Frontend', process.execPath, ['node_modules/vite/bin/vite.js']);
}
