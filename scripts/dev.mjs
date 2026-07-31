import { spawn } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';

function start(command, args, options = {}) {
  const child = spawn(command, args, {
    stdio: 'inherit',
    shell: false,
    ...options,
  });

  child.on('exit', (code) => {
    if (code && !shutdownInitiated) {
      shutdown(code);
    }
  });

  return child;
}

function quoteWindows(value) {
  if (/[\s"]/u.test(value)) {
    return `"${value.replaceAll('"', '\\"')}"`;
  }
  return value;
}

function startBatch(command, args) {
  const commandLine = [command, ...args].map(quoteWindows).join(' ');
  return spawn(process.env.ComSpec ?? 'cmd.exe', ['/d', '/s', '/c', commandLine], {
    stdio: 'inherit',
    shell: false,
  });
}

let shutdownInitiated = false;
const backend = process.platform === 'win32'
  ? startBatch('mvn', ['-q', '-f', 'backend/pom.xml', 'spring-boot:run'])
  : start('mvn', ['-q', '-f', 'backend/pom.xml', 'spring-boot:run']);

async function waitForBackend() {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    try {
      const response = await fetch('http://127.0.0.1:8080/api/system/overview', {
        signal: AbortSignal.timeout(1000),
      });
      if (response.ok) {
        return true;
      }
    } catch {
      // Keep polling until Spring Boot is ready.
    }

    await delay(1000);
  }

  return false;
}

const backendReady = await waitForBackend();
if (!backendReady) {
  console.error('Backend did not become ready on http://127.0.0.1:8080.');
  shutdown(1);
}

const frontend = start(process.execPath, ['node_modules/vite/bin/vite.js']);

function shutdown(code = 0) {
  if (shutdownInitiated) {
    process.exit(code);
  }
  shutdownInitiated = true;
  backend.kill();
  frontend.kill();
  process.exit(code);
}

process.on('SIGINT', () => shutdown(0));
process.on('SIGTERM', () => shutdown(0));
