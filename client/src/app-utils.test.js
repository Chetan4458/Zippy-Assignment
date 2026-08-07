import assert from 'node:assert/strict';
import test from 'node:test';
import {
  API_KEY_HEADER,
  api,
  clearSessionApiKey,
  getAuthSnapshot,
  getSessionApiKey,
  setSessionApiKey,
} from './api.js';
import {
  createMerchantOrderId,
  currentStatusFor,
  idempotencyEntry,
  money,
  parseWorkspaceHash,
  paymentsCsv,
  readableStatus,
  safeCsvCell,
  workflowState,
  workspaceHash,
} from './app-utils.js';
import {
  dateRangeForPreset,
  filterPayments,
  financePaymentsCsv,
  financeQuery,
  financeSnapshot,
  normalizeDailyTrend,
  paymentNetCash,
  paymentTimeline,
  summarizePayments,
  validateDateRange,
} from './finance-utils.js';

test('workflow maps shipping states to the correct visible stage', () => {
  assert.equal(workflowState({ order_status: 'CARRIER_SELECTED' }).stage, 1);
  assert.equal(workflowState({ order_status: 'SHIPMENT_CREATED' }).stage, 2);
  assert.equal(workflowState({ order_status: 'IN_TRANSIT' }).stage, 3);
  assert.equal(workflowState({ order_status: 'DELIVERED' }).stage, 4);
});

test('workflow exposes terminal states without treating retryable failures as final', () => {
  const failed = workflowState({ order_status: 'DELIVERY_FAILED' });
  assert.equal(failed.stage, 3);
  assert.equal(failed.terminal, false);
  const cancelled = workflowState({ order_status: 'CANCELLED' });
  assert.equal(cancelled.stage, 0);
  assert.equal(cancelled.terminal, true);
});

test('missing active order has no fabricated current status', () => {
  assert.equal(currentStatusFor(null), null);
  assert.equal(readableStatus(null), 'Not available');
  assert.equal(money(undefined), '-');
});

test('CSV cells neutralize spreadsheet formulas and escape quotes', () => {
  assert.equal(safeCsvCell('=2+2'), '"\'=2+2"');
  assert.equal(safeCsvCell(' @SUM(A1)'), '"\' @SUM(A1)"');
  assert.equal(safeCsvCell('\n=HYPERLINK("bad")'), '"\'\n=HYPERLINK(""bad"")"');
  assert.equal(safeCsvCell('safe "value"'), '"safe ""value"""');
  assert.match(paymentsCsv([{ paymentId: '=cmd', orderId: 'ZPY-1', amount: 10 }]), /"'=cmd"/);
});

test('sample merchant IDs are unique for different entropy', () => {
  assert.notEqual(createMerchantOrderId(100, 0.1), createMerchantOrderId(100, 0.2));
  assert.match(createMerchantOrderId(100, 0.1), /^MERCHANT-[A-Z0-9]+-[A-Z0-9]{4}$/);
});

test('workspace hashes validate screens and preserve order IDs', () => {
  const hash = workspaceHash('rates', 'ZPY 123');
  assert.deepEqual(parseWorkspaceHash(hash), { screen: 'rates', orderId: 'ZPY 123' });
  assert.deepEqual(parseWorkspaceHash('#/unknown?order=ZPY-1'), { screen: null, orderId: 'ZPY-1' });
});

test('idempotency keys are stable for retries and rotate when payload changes', () => {
  let index = 0;
  const createKey = () => `key-${++index}`;
  const first = idempotencyEntry(null, { a: 1 }, createKey);
  const retry = idempotencyEntry(first, { a: 1 }, createKey);
  const changed = idempotencyEntry(retry, { a: 2 }, createKey);
  assert.equal(retry.key, first.key);
  assert.notEqual(changed.key, first.key);
});

test('API client surfaces server validation details', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response(JSON.stringify({ message: 'Validation failed', details: ['email is invalid'] }), {
    status: 400,
    headers: { 'content-type': 'application/json' },
  });
  try {
    await assert.rejects(api('/api/orders'), /Validation failed: email is invalid/);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('API client aborts a request when its timeout elapses', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (_path, { signal }) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true });
  });
  try {
    await assert.rejects(api('/slow', { timeoutMs: 5 }), /timed out/);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('API key stays session-only and is attached as the raw X-API-Key header', async () => {
  const originalFetch = globalThis.fetch;
  let capturedHeader = null;
  globalThis.fetch = async (_path, { headers }) => {
    capturedHeader = headers.get(API_KEY_HEADER);
    return new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } });
  };
  try {
    setSessionApiKey('  secret-for-test  ');
    await api('/api/system/overview');
    assert.equal(getSessionApiKey(), 'secret-for-test');
    assert.equal(capturedHeader, 'secret-for-test');
    assert.equal(getAuthSnapshot().status, 'authenticated');

    clearSessionApiKey();
    await api('/api/system/overview');
    assert.equal(capturedHeader, null);
    assert.equal(getAuthSnapshot().status, 'open');
  } finally {
    clearSessionApiKey();
    globalThis.fetch = originalFetch;
  }
});

test('API client distinguishes missing keys from forbidden operations', async () => {
  const originalFetch = globalThis.fetch;
  try {
    clearSessionApiKey();
    globalThis.fetch = async () => new Response(JSON.stringify({ message: 'Missing or invalid API key' }), { status: 401 });
    await assert.rejects(api('/api/orders'), (error) => error.code === 'AUTH_REQUIRED' && error.status === 401);
    assert.equal(getAuthSnapshot().status, 'required');

    setSessionApiKey('valid-but-limited');
    globalThis.fetch = async () => new Response(JSON.stringify({ message: 'Access denied' }), { status: 403 });
    await assert.rejects(api('/api/mock-carriers/ZPY-1/advance'), (error) => error.code === 'AUTH_FORBIDDEN' && error.status === 403);
    assert.equal(getAuthSnapshot().status, 'forbidden');
  } finally {
    clearSessionApiKey();
    globalThis.fetch = originalFetch;
  }
});

test('a stale response cannot overwrite the state of a replacement API key', async () => {
  const originalFetch = globalThis.fetch;
  let finishRequest;
  globalThis.fetch = async () => new Promise((resolve) => {
    finishRequest = resolve;
  });
  try {
    setSessionApiKey('old-key');
    const staleRequest = api('/api/system/overview');
    setSessionApiKey('replacement-key');
    finishRequest(new Response(JSON.stringify({ message: 'Missing or invalid API key' }), { status: 401 }));

    await assert.rejects(staleRequest, (error) => error.code === 'AUTH_REQUIRED');
    assert.equal(getSessionApiKey(), 'replacement-key');
    assert.equal(getAuthSnapshot().status, 'checking');
  } finally {
    clearSessionApiKey();
    globalThis.fetch = originalFetch;
  }
});

test('finance date presets use inclusive UTC calendar boundaries', () => {
  const now = new Date('2026-08-07T23:45:00.000Z');
  assert.deepEqual(dateRangeForPreset('today', now), { from: '2026-08-07', to: '2026-08-07' });
  assert.deepEqual(dateRangeForPreset('7d', now), { from: '2026-08-01', to: '2026-08-07' });
  assert.deepEqual(dateRangeForPreset('all', now), { from: '', to: '' });
  assert.equal(validateDateRange('2026-08-08', '2026-08-07').valid, false);
  assert.equal(validateDateRange('2026-02-29', '2026-03-01').valid, false);
});

test('finance queries include only active filters and stable paging values', () => {
  const query = financeQuery({
    from: '2026-08-01',
    to: '2026-08-07',
    status: 'SUCCEEDED',
    method: 'all',
    carrier: 'FASTSHIP',
    search: 'PAY-100',
  }, { includeSearch: true, limit: 50, offset: 0 });
  assert.equal(
    query,
    'from=2026-08-01&to=2026-08-07&status=SUCCEEDED&carrier=FASTSHIP&search=PAY-100&limit=50&offset=0',
  );
});

test('payment accounting separates collected, refunded, net, and outstanding values', () => {
  const totals = summarizePayments([
    { status: 'SUCCEEDED', amount: 100, refundedAmount: 0 },
    { status: 'REFUNDED', amount: 80, refundedAmount: 80 },
    { status: 'PENDING', amount: 50 },
    { status: 'AWAITING_COLLECTION', amount: 70 },
    { status: 'FAILED', amount: 20 },
  ]);
  assert.deepEqual(totals, {
    grossCollected: 180,
    refunds: 80,
    netCollected: 100,
    outstanding: 120,
    transactions: 5,
    failed: 1,
  });
  assert.deepEqual(
    financeSnapshot({ finance: { grossCollected: 500, refunds: 75, netCollected: 425, pendingIntent: { amount: 20 }, codOutstanding: { amount: 30 }, failed: { count: 2 } }, totalPayments: 9 }),
    { grossCollected: 500, refunds: 75, netCollected: 425, outstanding: 50, pendingIntent: 20, codOutstanding: 30, transactions: 9, failed: 2, source: 'selected period' },
  );
});

test('payment filters search operational references and honor status, method, and UTC dates', () => {
  const payments = [
    { paymentId: 'PAY-1', orderId: 'ZPY-1', status: 'SUCCEEDED', paymentMethod: 'PREPAID', providerReference: 'gateway-alpha', refundReconciliationReference: 'refund-ledger-9', createdAt: '2026-08-04T23:59:00Z' },
    { paymentId: 'COD-2', orderId: 'ZPY-2', status: 'AWAITING_COLLECTION', paymentMethod: 'COD', reconciliationReference: 'bag-22', createdAt: '2026-08-05T00:01:00Z' },
  ];
  assert.deepEqual(filterPayments(payments, { search: 'ALPHA', status: 'SUCCEEDED', method: 'PREPAID', from: '2026-08-04', to: '2026-08-04' }), [payments[0]]);
  assert.deepEqual(filterPayments(payments, { search: 'refund-ledger-9', status: 'all', method: 'all' }), [payments[0]]);
  assert.deepEqual(filterPayments(payments, { search: 'bag-22', status: 'all', method: 'COD', from: '2026-08-05', to: '2026-08-05' }), [payments[1]]);
});

test('finance CSV neutralizes formulas in identifiers and reconciliation fields', () => {
  const csv = financePaymentsCsv([{
    paymentId: '=cmd',
    orderId: 'ZPY-1',
    paymentMethod: 'PREPAID',
    providerReference: '@gateway',
    reconciliationReference: '+ledger',
    refundReference: '-refund',
    refundReconciliationReference: '=refund-ledger',
    amount: 100,
    refundedAmount: 25,
    currency: 'INR',
    status: 'REFUNDED',
  }]);
  assert.match(csv, /"'=cmd"/);
  assert.match(csv, /"'@gateway"/);
  assert.match(csv, /"'\+ledger"/);
  assert.match(csv, /"'-refund"/);
  assert.match(csv, /"'=refund-ledger"/);
  assert.match(csv, /"75\.00"/);
});

test('CSV net cash is exact and excludes uncollected payment face value', () => {
  assert.equal(paymentNetCash({ status: 'SUCCEEDED', amount: '0.30', refundedAmount: '0.20' }), '0.10');
  assert.equal(paymentNetCash({ status: 'REFUNDED', amount: 0.3, refundedAmount: 0.2 }), '0.10');
  for (const status of ['PENDING', 'FAILED', 'CANCELLED', 'VOIDED']) {
    assert.equal(paymentNetCash({ status, amount: '99.99', refundedAmount: 0 }), '0.00');
  }
  const csv = financePaymentsCsv([
    { paymentId: 'PAY-CASH', status: 'SUCCEEDED', amount: '0.30', refundedAmount: '0.20' },
    { paymentId: 'PAY-PENDING', status: 'PENDING', amount: '50.00', refundedAmount: 0 },
  ]);
  assert.match(csv, /"Net cash collected"/);
  assert.match(csv, /"PAY-CASH"[^\r\n]*"0\.10"/);
  assert.match(csv, /"PAY-PENDING"[^\r\n]*"0\.00"/);
  assert.doesNotMatch(csv, /0\.099999/);
});

test('daily trends normalize aliases and payment ledger entries remain chronological', () => {
  assert.deepEqual(normalizeDailyTrend([
    { day: '2026-08-07', grossCollected: 100, refundedAmount: 25, count: 2 },
    { date: 'invalid', grossCollected: 10 },
  ]), [{ date: '2026-08-07', grossCollected: 100, refunds: 25, netCollected: 75, transactions: 2 }]);

  const timeline = paymentTimeline({ currency: 'INR' }, [
    { transactionId: 'T2', eventType: 'CAPTURED', amount: 100, createdAt: '2026-08-07T11:00:00Z' },
    { transactionId: 'T1', eventType: 'CREATED', amount: 100, createdAt: '2026-08-07T10:00:00Z' },
  ]);
  assert.deepEqual(timeline.map((entry) => entry.id), ['T1', 'T2']);
});

test('payment ledger timeline preserves backend actor attribution', () => {
  const timeline = paymentTimeline({ currency: 'INR' }, [
    { transactionId: 'T1', eventType: 'CREATED', actor: '  LOCAL_OPERATOR  ', amount: 100, createdAt: '2026-08-07T10:00:00Z' },
    { transactionId: 'T2', eventType: 'CAPTURED', amount: 100, createdAt: '2026-08-07T11:00:00Z' },
  ]);

  assert.deepEqual(timeline.map((entry) => entry.actor), ['LOCAL_OPERATOR', null]);
});
