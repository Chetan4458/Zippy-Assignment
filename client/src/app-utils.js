export const WORKFLOW_STEPS = [
  'Order created',
  'Carrier chosen',
  'Shipment created',
  'In transit',
  'Delivered',
];

export const TERMINAL_STATUSES = new Set(['CANCELLED', 'RTO']);

const moneyFormatter = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
});

export function money(value) {
  const numericValue = Number(value);
  return Number.isFinite(numericValue) ? moneyFormatter.format(numericValue) : '-';
}

export function estimateLabel(option) {
  const minimum = Number(option?.estimatedMinDays);
  const maximum = Number(option?.estimatedMaxDays);
  if (!Number.isFinite(minimum) || !Number.isFinite(maximum)) return 'Estimate unavailable';
  return minimum === maximum ? `${minimum} day${minimum === 1 ? '' : 's'}` : `${minimum}-${maximum} days`;
}

export function readableStatus(status, fallback = 'Not available') {
  if (!status) return fallback;
  return String(status)
    .replaceAll('_', ' ')
    .toLowerCase()
    .replace(/(^|\s)\S/g, (letter) => letter.toUpperCase());
}

export function currentStatusFor(order) {
  return order?.selectedShipment?.current_status || order?.order_status || null;
}

export function statusTone(status) {
  if (['DELIVERED', 'SUCCEEDED', 'REFUNDED'].includes(status)) return 'success';
  if (['DELIVERY_FAILED', 'RTO', 'CANCELLED', 'FAILED', 'VOIDED'].includes(status)) return 'danger';
  if (['PENDING', 'AWAITING_COLLECTION', 'REFUND_PENDING'].includes(status)) return 'warning';
  return 'neutral';
}

export function workflowState(order) {
  const status = currentStatusFor(order);
  const shipment = order?.selectedShipment;
  const stageByStatus = {
    ORDER_CREATED: 0,
    CARRIER_SELECTED: 1,
    SHIPMENT_CREATED: 2,
    PICKED_UP: 3,
    IN_TRANSIT: 3,
    OUT_FOR_DELIVERY: 3,
    DELIVERY_FAILED: 3,
    RTO: 3,
    DELIVERED: 4,
  };

  let stage = stageByStatus[status];
  if (status === 'CANCELLED') {
    stage = shipment?.tracking_number ? 2 : shipment ? 1 : 0;
  }
  if (!Number.isInteger(stage)) stage = 0;

  return {
    stage,
    status,
    terminal: TERMINAL_STATUSES.has(status),
    terminalLabel: TERMINAL_STATUSES.has(status) ? readableStatus(status) : null,
  };
}

export function formatDateTime(value, locale = 'en-IN') {
  if (!value) return 'Just now';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toLocaleString(locale, { dateStyle: 'medium', timeStyle: 'short' });
}

export function safeCsvCell(value) {
  let text = String(value ?? '');
  if (/^[\t\r\n ]*[=+\-@]/.test(text)) text = `'${text}`;
  return `"${text.replaceAll('"', '""')}"`;
}

export function paymentsCsv(payments) {
  const rows = [
    ['Payment ID', 'Order ID', 'Amount', 'Currency', 'Status', 'Created at'],
    ...payments.map((payment) => [
      payment.paymentId,
      payment.orderId,
      payment.amount,
      payment.currency,
      payment.status,
      payment.createdAt,
    ]),
  ];
  return rows.map((row) => row.map(safeCsvCell).join(',')).join('\r\n');
}

export function createMerchantOrderId(now = Date.now(), random = Math.random()) {
  const time = Number(now).toString(36).toUpperCase();
  const entropy = Math.floor(Number(random) * 36 ** 4)
    .toString(36)
    .toUpperCase()
    .padStart(4, '0');
  return `MERCHANT-${time}-${entropy}`;
}

export function createSampleOrder(now, random) {
  return {
    merchantOrderId: createMerchantOrderId(now, random),
    customer: { name: 'Rahul Sharma', phone: '9876543210', email: 'rahul@example.com' },
    pickupAddress: { addressLine1: '15 MG Road', city: 'Bengaluru', state: 'Karnataka', pincode: '560001' },
    deliveryAddress: {
      addressLine1: '22 Connaught Place',
      city: 'New Delhi',
      state: 'Delhi',
      pincode: '110001',
    },
    package: { weightGrams: 1500, lengthCm: 20, widthCm: 15, heightCm: 10 },
    paymentType: 'COD',
    codAmount: 2500,
  };
}

export const VALID_SCREENS = new Set(['create', 'rates', 'details', 'payments', 'reports', 'webhooks']);

export function parseWorkspaceHash(hash) {
  const normalized = String(hash || '').replace(/^#\/?/, '');
  const [path, query = ''] = normalized.split('?');
  const screen = VALID_SCREENS.has(path) ? path : null;
  const orderId = new URLSearchParams(query).get('order');
  return { screen, orderId: orderId || null };
}

export function workspaceHash(screen, orderId) {
  const safeScreen = VALID_SCREENS.has(screen) ? screen : 'create';
  return `#/${safeScreen}${orderId ? `?order=${encodeURIComponent(orderId)}` : ''}`;
}

export function idempotencyEntry(previous, payload, createKey) {
  const fingerprint = JSON.stringify(payload);
  if (previous?.fingerprint === fingerprint && previous.key) return previous;
  return { fingerprint, key: createKey() };
}
