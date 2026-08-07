const DEFAULT_TIMEOUT_MS = 12_000;
export const API_KEY_HEADER = 'X-API-Key';

let sessionApiKey = '';
let authGeneration = 0;
let authSnapshot = { status: 'unknown', message: '' };
const authListeners = new Set();

function publishAuth(status, message = '') {
  if (authSnapshot.status === status && authSnapshot.message === message) return;
  authSnapshot = { status, message };
  authListeners.forEach((listener) => listener(authSnapshot));
}

export function getSessionApiKey() {
  return sessionApiKey;
}

export function setSessionApiKey(value) {
  sessionApiKey = String(value || '').trim();
  authGeneration += 1;
  publishAuth(sessionApiKey ? 'checking' : 'unknown');
  return Boolean(sessionApiKey);
}

export function clearSessionApiKey() {
  sessionApiKey = '';
  authGeneration += 1;
  publishAuth('unknown');
}

export function getAuthSnapshot() {
  return authSnapshot;
}

export function subscribeAuth(listener) {
  authListeners.add(listener);
  return () => authListeners.delete(listener);
}

export class ApiError extends Error {
  constructor(message, { status = 0, code = 'REQUEST_FAILED', details = [] } = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

function combineSignals(signals) {
  const activeSignals = signals.filter(Boolean);
  if (!activeSignals.length) return undefined;
  if (activeSignals.length === 1) return activeSignals[0];
  if (typeof AbortSignal.any === 'function') return AbortSignal.any(activeSignals);

  const controller = new AbortController();
  const abort = () => controller.abort();
  activeSignals.forEach((signal) => {
    if (signal.aborted) abort();
    else signal.addEventListener('abort', abort, { once: true });
  });
  return controller.signal;
}

export function isAbortError(error) {
  return error?.name === 'AbortError' || error?.code === 'ABORTED';
}

export function isAuthError(error) {
  return error?.code === 'AUTH_REQUIRED' || error?.code === 'AUTH_FORBIDDEN';
}

export async function api(path, options = {}) {
  const { timeoutMs = DEFAULT_TIMEOUT_MS, signal, headers, ...fetchOptions } = options;
  const requestApiKey = sessionApiKey;
  const requestAuthGeneration = authGeneration;
  const isCurrentCredential = () => requestAuthGeneration === authGeneration && requestApiKey === sessionApiKey;
  const timeoutController = new AbortController();
  const timeout = setTimeout(() => timeoutController.abort('timeout'), timeoutMs);
  const requestSignal = combineSignals([signal, timeoutController.signal]);
  const requestHeaders = new Headers(headers || {});
  if (requestApiKey) requestHeaders.set(API_KEY_HEADER, requestApiKey);
  if (fetchOptions.body != null && !requestHeaders.has('content-type')) {
    requestHeaders.set('content-type', 'application/json');
  }

  let response;
  let text;
  try {
    response = await fetch(path, { ...fetchOptions, headers: requestHeaders, signal: requestSignal });
    text = await response.text();
  } catch (error) {
    if (signal?.aborted) throw new ApiError('Request cancelled.', { code: 'ABORTED' });
    if (timeoutController.signal.aborted) {
      throw new ApiError('The request timed out. Check the backend and try again.', { code: 'TIMEOUT' });
    }
    if (error?.name === 'AbortError') throw new ApiError('Request cancelled.', { code: 'ABORTED' });
    throw new ApiError('Backend is not reachable. Start Spring Boot on port 8080 or run `npm run dev`.', {
      code: 'NETWORK_ERROR',
    });
  } finally {
    clearTimeout(timeout);
  }

  let body = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = { message: text };
    }
  }

  if (!response.ok) {
    const details = Array.isArray(body?.details) ? body.details.filter(Boolean) : [];
    const baseMessage = [502, 503, 504].includes(response.status)
      ? 'Backend is not reachable. Start Spring Boot on port 8080 or run `npm run dev`.'
      : body?.message || `Request failed with ${response.status}`;
    const message = details.length ? `${baseMessage}: ${details.join('; ')}` : baseMessage;
    if (response.status === 401) {
      const authMessage = requestApiKey ? 'The API key was not accepted. Check it and try again.' : 'An operational API key is required.';
      if (isCurrentCredential()) publishAuth(requestApiKey ? 'rejected' : 'required', authMessage);
      throw new ApiError(authMessage, { status: response.status, code: 'AUTH_REQUIRED', details });
    }
    if (response.status === 403) {
      const authMessage = 'This API key does not have permission for that operation.';
      if (isCurrentCredential()) publishAuth('forbidden', authMessage);
      throw new ApiError(authMessage, { status: response.status, code: 'AUTH_FORBIDDEN', details });
    }
    throw new ApiError(message, { status: response.status, details });
  }
  if (isCurrentCredential()) publishAuth(requestApiKey ? 'authenticated' : 'open');
  return body;
}
