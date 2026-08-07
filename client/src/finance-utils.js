import { safeCsvCell } from './app-utils.js';

export const PAYMENT_STATUSES = [
  'PENDING',
  'AWAITING_COLLECTION',
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'REFUND_PENDING',
  'REFUNDED',
  'VOIDED',
];

export const PAYMENT_METHODS = ['PREPAID', 'COD'];

const COLLECTED_STATUSES = new Set(['SUCCEEDED', 'REFUND_PENDING', 'REFUNDED', 'PARTIALLY_REFUNDED']);
const OUTSTANDING_STATUSES = new Set(['PENDING', 'AWAITING_COLLECTION']);

function amount(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
}

function decimalToMinorUnits(value) {
  const source = typeof value === 'number'
    ? (Number.isFinite(value) ? value.toFixed(2) : '0')
    : String(value ?? '0').trim();
  const match = source.match(/^([+-]?)(\d+)(?:\.(\d*))?$/);
  if (!match) return 0n;
  const fraction = (match[3] || '').padEnd(3, '0');
  let units = BigInt(match[2]) * 100n + BigInt(fraction.slice(0, 2));
  if (Number(fraction[2]) >= 5) units += 1n;
  return match[1] === '-' ? -units : units;
}

function fixedMinorUnits(value) {
  const negative = value < 0n;
  const absolute = negative ? -value : value;
  return `${negative ? '-' : ''}${absolute / 100n}.${String(absolute % 100n).padStart(2, '0')}`;
}

export function fixedDecimal(value) {
  return fixedMinorUnits(decimalToMinorUnits(value));
}

export function paymentNetCash(payment = {}) {
  const status = String(payment.status || '').toUpperCase();
  if (!COLLECTED_STATUSES.has(status)) return '0.00';
  return fixedMinorUnits(decimalToMinorUnits(payment.amount) - decimalToMinorUnits(payment.refundedAmount));
}

function utcDateValue(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const year = date.getUTCFullYear();
  const month = String(date.getUTCMonth() + 1).padStart(2, '0');
  const day = String(date.getUTCDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function validCalendarDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(String(value || ''))) return false;
  return utcDateValue(`${value}T00:00:00.000Z`) === value;
}

export function dateRangeForPreset(preset, now = new Date()) {
  if (preset === 'all') return { from: '', to: '' };
  if (preset === 'custom') return null;
  const days = { today: 1, '7d': 7, '30d': 30, '90d': 90 }[preset] || 30;
  const end = new Date(now);
  const start = new Date(end);
  start.setUTCDate(start.getUTCDate() - (days - 1));
  return { from: utcDateValue(start), to: utcDateValue(end) };
}

export function validateDateRange(from, to) {
  if (!from && !to) return { valid: true, error: '' };
  if (!validCalendarDate(from) || !validCalendarDate(to)) {
    return { valid: false, error: 'Choose both a valid start and end date.' };
  }
  if (from > to) return { valid: false, error: 'Start date must be on or before end date.' };
  return { valid: true, error: '' };
}

export function financeQuery(filters = {}, { limit, offset, includeSearch = false } = {}) {
  const params = new URLSearchParams();
  ['from', 'to', 'status', 'method', 'carrier'].forEach((key) => {
    const value = String(filters[key] || '').trim();
    if (value && value.toLowerCase() !== 'all') params.set(key, value);
  });
  if (includeSearch) {
    const search = String(filters.search || '').trim();
    if (search) params.set('search', search);
  }
  if (Number.isInteger(limit) && limit > 0) params.set('limit', String(limit));
  if (Number.isInteger(offset) && offset >= 0) params.set('offset', String(offset));
  return params.toString();
}

export function paymentAccounting(payment = {}) {
  const status = String(payment.status || '').toUpperCase();
  const original = amount(payment.amount);
  const refunds = amount(payment.refundedAmount);
  const grossCollected = COLLECTED_STATUSES.has(status) ? original : 0;
  const outstanding = OUTSTANDING_STATUSES.has(status) ? original : 0;
  return {
    grossCollected,
    refunds,
    netCollected: grossCollected - refunds,
    outstanding,
  };
}

export function summarizePayments(payments = []) {
  return payments.reduce((totals, payment) => {
    const row = paymentAccounting(payment);
    totals.grossCollected += row.grossCollected;
    totals.refunds += row.refunds;
    totals.netCollected += row.netCollected;
    totals.outstanding += row.outstanding;
    totals.transactions += 1;
    if (payment.status === 'FAILED') totals.failed += 1;
    return totals;
  }, {
    grossCollected: 0,
    refunds: 0,
    netCollected: 0,
    outstanding: 0,
    transactions: 0,
    failed: 0,
  });
}

export function financeSnapshot(report, payments = []) {
  const fallback = summarizePayments(payments);
  const finance = report?.finance;
  if (!finance) return { ...fallback, source: 'loaded rows' };
  const pending = amount(finance.pendingIntent?.amount);
  const codOutstanding = amount(finance.codOutstanding?.amount);
  return {
    grossCollected: amount(finance.grossCollected),
    refunds: amount(finance.refunds),
    netCollected: amount(finance.netCollected),
    outstanding: pending + codOutstanding,
    pendingIntent: pending,
    codOutstanding,
    transactions: Number(report.totalPayments ?? fallback.transactions),
    failed: Number(finance.failed?.count ?? fallback.failed),
    source: 'selected period',
  };
}

export function filterPayments(payments = [], filters = {}) {
  const status = String(filters.status || 'all').toUpperCase();
  const method = String(filters.method || 'all').toUpperCase();
  const search = String(filters.search || '').trim().toLocaleLowerCase('en');
  const range = validateDateRange(filters.from, filters.to);

  return payments.filter((payment) => {
    if (status !== 'ALL' && String(payment.status || '').toUpperCase() !== status) return false;
    if (method !== 'ALL' && String(payment.paymentMethod || '').toUpperCase() !== method) return false;
    if (range.valid && filters.from && filters.to) {
      const createdDate = utcDateValue(payment.createdAt);
      if (!createdDate || createdDate < filters.from || createdDate > filters.to) return false;
    }
    if (!search) return true;
    return [
      payment.paymentId,
      payment.orderId,
      payment.status,
      payment.paymentMethod,
      payment.provider,
      payment.providerReference,
      payment.reconciliationReference,
      payment.refundReference,
      payment.refundReconciliationReference,
      payment.failureCode,
      payment.failureReason,
    ].some((value) => String(value || '').toLocaleLowerCase('en').includes(search));
  });
}

export function financePaymentsCsv(payments = []) {
  const rows = [
    [
      'Payment ID',
      'Order ID',
      'Method',
      'Provider',
      'Provider reference',
      'Reconciliation reference',
      'Refund reference',
      'Refund reconciliation reference',
      'Amount',
      'Refunded amount',
      'Net cash collected',
      'Currency',
      'Status',
      'Failure code',
      'Failure reason',
      'Created at',
      'Updated at',
      'Captured at',
      'Collected at',
      'Refunded at',
    ],
    ...payments.map((payment) => [
      payment.paymentId,
      payment.orderId,
      payment.paymentMethod,
      payment.provider,
      payment.providerReference,
      payment.reconciliationReference,
      payment.refundReference,
      payment.refundReconciliationReference,
      fixedDecimal(payment.amount),
      fixedDecimal(payment.refundedAmount),
      paymentNetCash(payment),
      payment.currency,
      payment.status,
      payment.failureCode,
      payment.failureReason,
      payment.createdAt,
      payment.updatedAt,
      payment.capturedAt,
      payment.collectedAt,
      payment.refundedAt,
    ]),
  ];
  return rows.map((row) => row.map(safeCsvCell).join(',')).join('\r\n');
}

export function normalizeDailyTrend(rows = []) {
  return rows
    .map((row) => {
      const grossCollected = amount(row.grossCollected);
      const refunds = amount(row.refunds ?? row.refundedAmount);
      return {
        date: String(row.date || row.day || ''),
        grossCollected,
        refunds,
        netCollected: row.netCollected == null ? grossCollected - refunds : amount(row.netCollected),
        transactions: Number(row.transactions ?? row.count ?? 0),
      };
    })
    .filter((row) => validCalendarDate(row.date))
    .sort((left, right) => left.date.localeCompare(right.date));
}

export function paymentTimeline(payment, transactions = []) {
  if (transactions.length) {
    return [...transactions]
      .map((transaction) => ({
        id: transaction.transactionId || `${transaction.eventType}-${transaction.createdAt}`,
        label: String(transaction.eventType || transaction.resultingStatus || 'PAYMENT_EVENT'),
        status: transaction.resultingStatus,
        previousStatus: transaction.previousStatus,
        amount: amount(transaction.amount),
        currency: transaction.currency || payment?.currency,
        provider: transaction.provider,
        providerReference: transaction.providerReference,
        reconciliationReference: transaction.reconciliationReference,
        reference: transaction.reconciliationReference || transaction.providerReference,
        actor: String(transaction.actor || '').trim() || null,
        reason: transaction.reason,
        createdAt: transaction.createdAt,
      }))
      .sort((left, right) => String(left.createdAt).localeCompare(String(right.createdAt)));
  }

  if (!payment) return [];
  const timeline = [{
    id: `${payment.paymentId}-created`,
    label: payment.paymentMethod === 'COD' ? 'COD_OBLIGATION_CREATED' : 'PAYMENT_CREATED',
    status: payment.paymentMethod === 'COD' ? 'AWAITING_COLLECTION' : 'PENDING',
    amount: amount(payment.amount),
    currency: payment.currency,
    provider: payment.provider,
    providerReference: payment.providerReference,
    reconciliationReference: payment.reconciliationReference,
    reference: payment.providerReference,
    actor: null,
    createdAt: payment.createdAt,
  }];
  if (payment.updatedAt && payment.updatedAt !== payment.createdAt) {
    timeline.push({
      id: `${payment.paymentId}-updated`,
      label: payment.status || 'PAYMENT_UPDATED',
      status: payment.status,
      amount: amount(payment.refundedAmount) || amount(payment.amount),
      currency: payment.currency,
      provider: payment.provider,
      providerReference: payment.refundReference || payment.providerReference,
      reconciliationReference: payment.refundReconciliationReference || payment.reconciliationReference,
      reference: payment.refundReference || payment.refundReconciliationReference || payment.reconciliationReference || payment.providerReference,
      actor: null,
      reason: payment.failureReason,
      createdAt: payment.refundedAt || payment.collectedAt || payment.capturedAt || payment.updatedAt,
    });
  }
  return timeline;
}
