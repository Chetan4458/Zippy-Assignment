import { useEffect, useRef, useState, useTransition } from 'react';
import { createRoot } from 'react-dom/client';
import {
  api,
  clearSessionApiKey,
  getAuthSnapshot,
  getSessionApiKey,
  isAbortError,
  isAuthError,
  setSessionApiKey,
  subscribeAuth,
} from './api.js';
import {
  WORKFLOW_STEPS,
  createSampleOrder,
  currentStatusFor,
  estimateLabel,
  formatDateTime,
  idempotencyEntry,
  money,
  parseWorkspaceHash,
  readableStatus,
  statusTone,
  workflowState,
  workspaceHash,
} from './app-utils.js';
import {
  BreakdownPanel,
  DailyTrend,
  FinanceMetric,
  PaymentActionPanel,
  PaymentDetail,
  PaymentFilterBar,
  PaymentTable,
  ReportFilterBar,
} from './finance-components.jsx';
import {
  dateRangeForPreset,
  financePaymentsCsv,
  financeQuery,
  financeSnapshot,
  validateDateRange,
} from './finance-utils.js';
import './styles.css';

const screenMeta = {
  create: { label: 'Create order', title: 'Order intake' },
  rates: { label: 'Rates', title: 'Courier selection' },
  details: { label: 'Order details', title: 'Shipment tracking' },
  payments: { label: 'Payments', title: 'Finance operations' },
  reports: { label: 'Reports', title: 'Finance and cost reports' },
  webhooks: { label: 'Webhooks', title: 'Webhook monitor' },
};

const PAYMENT_PAGE_SIZE = 25;

const formGroups = [
  {
    legend: 'Order and customer',
    description: 'Merchant reference and recipient contact details.',
    fields: [
      { name: 'merchantOrderId', label: 'Merchant order number', autoComplete: 'off', maxLength: 64 },
      { name: 'customerName', label: 'Customer name', autoComplete: 'name', maxLength: 120 },
      { name: 'customerPhone', label: 'Customer phone', type: 'tel', autoComplete: 'tel', inputMode: 'tel', pattern: '(?:\\+91[- ]?)?[6-9][0-9]{9}', title: 'Enter a valid Indian mobile number, optionally prefixed with +91' },
      { name: 'customerEmail', label: 'Customer email', type: 'email', autoComplete: 'email', maxLength: 120 },
    ],
  },
  {
    legend: 'Pickup address',
    description: 'Where the carrier will collect the parcel.',
    fields: [
      { name: 'pickupAddressLine1', label: 'Address line', autoComplete: 'address-line1', maxLength: 180 },
      { name: 'pickupCity', label: 'City', autoComplete: 'address-level2', maxLength: 80 },
      { name: 'pickupState', label: 'State', autoComplete: 'address-level1', maxLength: 80 },
      { name: 'pickupPincode', label: 'Pincode', autoComplete: 'postal-code', inputMode: 'numeric', pattern: '[1-9][0-9]{5}', title: 'Enter a valid 6-digit Indian pincode' },
    ],
  },
  {
    legend: 'Delivery address',
    description: 'The final delivery destination.',
    fields: [
      { name: 'deliveryAddressLine1', label: 'Address line', autoComplete: 'address-line1', maxLength: 180 },
      { name: 'deliveryCity', label: 'City', autoComplete: 'address-level2', maxLength: 80 },
      { name: 'deliveryState', label: 'State', autoComplete: 'address-level1', maxLength: 80 },
      { name: 'deliveryPincode', label: 'Pincode', autoComplete: 'postal-code', inputMode: 'numeric', pattern: '[1-9][0-9]{5}', title: 'Enter a valid 6-digit Indian pincode' },
    ],
  },
  {
    legend: 'Package',
    description: 'Measured parcel dimensions used for rating.',
    fields: [
      { name: 'weightGrams', label: 'Weight (grams)', type: 'number', min: 1, max: 100000, step: 1 },
      { name: 'lengthCm', label: 'Length (cm)', type: 'number', min: 0.1, max: 500, step: 0.1 },
      { name: 'widthCm', label: 'Width (cm)', type: 'number', min: 0.1, max: 500, step: 0.1 },
      { name: 'heightCm', label: 'Height (cm)', type: 'number', min: 0.1, max: 500, step: 0.1 },
    ],
  },
];

function downloadPaymentsCsv(payments) {
  const csv = financePaymentsCsv(payments);
  const blob = new Blob(['\uFEFF', csv], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `zippy-payments-${new Date().toISOString().slice(0, 10)}.csv`;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

function newIdempotencyKey(prefix = 'payment') {
  return globalThis.crypto?.randomUUID?.() || `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function shellValue(source, key) {
  return source[key] ?? '';
}

function carrierLabel(code) {
  return { FASTSHIP: 'FastShip', QUICKEXPRESS: 'QuickExpress', RELIABLE: 'Reliable Courier' }[code]
    || readableStatus(code);
}

function isCancellableStatus(status) {
  return ['ORDER_CREATED', 'CARRIER_SELECTED', 'SHIPMENT_CREATED'].includes(status);
}

function MetricCard({ label, value, tone }) {
  return (
    <article className={`stat-card ${tone}`}>
      <span>{label}</span>
      <strong>{value}</strong>
    </article>
  );
}

function StatusPill({ status }) {
  return <span className={`status-pill ${statusTone(status)}`}>{readableStatus(status)}</span>;
}

function historyActionLabel(item) {
  return 'View details';
}

function historyActionVariant(item) {
  return 'ghost';
}

function SectionHeader({ eyebrow, title, subtitle, action, headingRef, headingId }) {
  return (
    <div className="card-head">
      <div>
        <p className="section-label">{eyebrow}</p>
        <h2 ref={headingRef} id={headingId} tabIndex={headingRef ? -1 : undefined}>{title}</h2>
        {subtitle ? <p className="subtle">{subtitle}</p> : null}
      </div>
      {action}
    </div>
  );
}

function WorkflowRail({ state }) {
  return (
    <div className="workflow-card">
      <div className="workflow-header">
        <span>Journey</span>
        <small>{state.status ? 'Live workflow snapshot' : 'No active order'}</small>
      </div>
      <ol className="workflow">
        {WORKFLOW_STEPS.map((item, index) => (
          <li
            key={item}
            className={index <= state.stage && state.status ? 'complete' : ''}
            aria-current={index === state.stage && state.status && !state.terminal ? 'step' : undefined}
          >
            <span className="step-dot" aria-hidden="true" />
            <div>
              <strong>{item}</strong>
              {index === state.stage && state.status && !state.terminal ? <small>Current step</small> : null}
            </div>
          </li>
        ))}
      </ol>
      {state.terminal ? (
        <p className={`terminal-state ${statusTone(state.status)}`}>Journey ended: {state.terminalLabel}</p>
      ) : state.status === 'DELIVERY_FAILED' ? (
        <p className="terminal-state warning">Delivery exception: retry or move to return-to-origin.</p>
      ) : null}
    </div>
  );
}

function OrderField({ name, label, type = 'text', defaultValue = '', ...inputProps }) {
  return (
    <label htmlFor={name}>
      <span>{label} <span className="required-mark" aria-hidden="true">*</span></span>
      <input id={name} name={name} type={type} defaultValue={defaultValue} required {...inputProps} />
    </label>
  );
}

function ConnectionSecurityPanel({ auth, keyActive, checking, onSubmit, onClear, onRetry, onClose }) {
  const labels = {
    unknown: 'Not checked',
    checking: 'Checking connection',
    open: 'Local mode',
    authenticated: 'Secured connection',
    required: 'API key required',
    rejected: 'API key rejected',
    forbidden: 'Access limited',
  };
  const needsKey = ['required', 'rejected'].includes(auth.status);

  return (
    <section
      className="security-panel"
      id="security-panel"
      aria-busy={checking}
      aria-labelledby="security-panel-title"
    >
      <div className="security-panel-head">
        <div>
          <p className="section-label">Connection security</p>
          <h2 id="security-panel-title">Operational API access</h2>
        </div>
        <span className={`connection-badge ${auth.status}`} role="status">
          <span aria-hidden="true" />{labels[auth.status] || labels.unknown}
        </span>
      </div>
      <p className="subtle" id="api-key-privacy">
        {auth.status === 'open'
          ? 'Authentication is disabled for this local environment.'
          : `${auth.message ? `${auth.message} ` : ''}Production keys are at least 32 characters, held in memory for this tab only, and never saved to browser storage.`}
      </p>
      {needsKey ? (
        <form className="security-form" autoComplete="off" onSubmit={onSubmit}>
          <label htmlFor="operationalApiKey">
            <span>Operational API key</span>
            <input
              id="operationalApiKey"
              name="operationalApiKey"
              type="password"
              aria-describedby="api-key-privacy"
              autoComplete="off"
              autoCapitalize="none"
              spellCheck={false}
              autoFocus
              required
              minLength={32}
              maxLength={512}
              placeholder="Enter key for this tab"
            />
          </label>
          <button type="submit" className="primary" disabled={checking}>{checking ? 'Verifying...' : 'Use key and retry'}</button>
        </form>
      ) : null}
      <div className="security-actions">
        {keyActive ? <button type="button" className="ghost" onClick={onClear} disabled={checking}>Clear session key</button> : null}
        <button type="button" className="ghost" onClick={onRetry} disabled={checking}>{checking ? 'Checking...' : 'Retry connection'}</button>
        {!['required', 'rejected'].includes(auth.status) ? <button type="button" className="quiet-button" onClick={onClose}>Close</button> : null}
      </div>
    </section>
  );
}

function initialWorkspace() {
  const fromHash = parseWorkspaceHash(window.location.hash);
  let stored = {};
  try {
    stored = JSON.parse(window.localStorage.getItem('zippy.workspace') || '{}');
  } catch {
    // Ignore malformed or unavailable local storage.
  }
  const orderId = fromHash.orderId || stored.orderId || null;
  const requestedScreen = fromHash.screen || (screenMeta[stored.screen] ? stored.screen : 'create');
  return {
    screen: !orderId && ['rates', 'details', 'webhooks'].includes(requestedScreen) ? 'create' : requestedScreen,
    orderId,
  };
}

function App() {
  const restoredWorkspaceRef = useRef(null);
  if (!restoredWorkspaceRef.current) restoredWorkspaceRef.current = initialWorkspace();
  const [screen, setScreen] = useState(restoredWorkspaceRef.current.screen);
  const [isPending, startTransition] = useTransition();
  const [statusMessage, setStatusMessage] = useState('');
  const [errorMessage, setErrorMessage] = useState('');
  const [sortBy, setSortBy] = useState('lowest');
  const [order, setOrder] = useState(null);
  const [rates, setRates] = useState([]);
  const [selectedCarrier, setSelectedCarrier] = useState(null);
  const [tracking, setTracking] = useState(null);
  const [creatingOrder, setCreatingOrder] = useState(false);
  const [creatingShipment, setCreatingShipment] = useState(false);
  const [cancellingOrder, setCancellingOrder] = useState(false);
  const [sortingRates, setSortingRates] = useState(false);
  const [selectingCarrierKey, setSelectingCarrierKey] = useState('');
  const [openingHistoryId, setOpeningHistoryId] = useState('');
  const [paymentType, setPaymentType] = useState('COD');
  const [systemOverview, setSystemOverview] = useState(null);
  const [orderHistory, setOrderHistory] = useState([]);
  const [reportsSummary, setReportsSummary] = useState(null);
  const [paymentHistory, setPaymentHistory] = useState([]);
  const [paymentReport, setPaymentReport] = useState(null);
  const [reportPaymentHistory, setReportPaymentHistory] = useState([]);
  const [reportPaymentReport, setReportPaymentReport] = useState(null);
  const [orderPayments, setOrderPayments] = useState([]);
  const [processingPayment, setProcessingPayment] = useState(false);
  const [loadingHistory, setLoadingHistory] = useState(false);
  const [loadingTracking, setLoadingTracking] = useState(false);
  const [loadingReports, setLoadingReports] = useState(false);
  const [reportsError, setReportsError] = useState('');
  const [loadingFinance, setLoadingFinance] = useState(false);
  const [financeError, setFinanceError] = useState('');
  const [paymentFilters, setPaymentFilters] = useState({ search: '', status: 'all', method: 'all' });
  const [paymentOffset, setPaymentOffset] = useState(0);
  const [reportFilters, setReportFilters] = useState(() => ({
    preset: '30d',
    ...dateRangeForPreset('30d'),
    status: 'all',
    method: 'all',
    carrier: 'all',
  }));
  const [reportFilterDraft, setReportFilterDraft] = useState(reportFilters);
  const [reportFilterError, setReportFilterError] = useState('');
  const [selectedPayment, setSelectedPayment] = useState(null);
  const [paymentTransactions, setPaymentTransactions] = useState([]);
  const [paymentTransactionTotal, setPaymentTransactionTotal] = useState(0);
  const [loadingLedger, setLoadingLedger] = useState(false);
  const [ledgerError, setLedgerError] = useState('');
  const [paymentAction, setPaymentAction] = useState(null);
  const [paymentActionError, setPaymentActionError] = useState('');
  const [reportRefreshKey, setReportRefreshKey] = useState(0);
  const [historyRefreshKey, setHistoryRefreshKey] = useState(0);
  const [refreshState, setRefreshState] = useState({});
  const [isDocumentVisible, setDocumentVisible] = useState(() => document.visibilityState !== 'hidden');
  const [restoringWorkspace, setRestoringWorkspace] = useState(Boolean(restoredWorkspaceRef.current.orderId));
  const [authState, setAuthState] = useState(() => getAuthSnapshot());
  const [securityPanelOpen, setSecurityPanelOpen] = useState(false);
  const [authChecking, setAuthChecking] = useState(false);
  const [authRetryKey, setAuthRetryKey] = useState(0);
  const createFormRef = useRef(null);
  const createIdempotencyRef = useRef(null);
  const sortControllerRef = useRef(null);
  const selectionControllerRef = useRef(null);
  const historyControllerRef = useRef(null);
  const ledgerControllerRef = useRef(null);
  const paymentMutationRef = useRef(null);
  const paymentActionReturnFocusRef = useRef(null);
  const selectedPaymentRef = useRef(null);
  const screenHeadingRef = useRef(null);
  const connectionTriggerRef = useRef(null);
  const skipInitialFocusRef = useRef(true);

  function markRefresh(key, status, error = '') {
    setRefreshState((current) => ({
      ...current,
      [key]: { status, error, updatedAt: status === 'ok' ? Date.now() : current[key]?.updatedAt || null },
    }));
  }

  useEffect(() => subscribeAuth((snapshot) => {
    setAuthState(snapshot);
    if (['required', 'rejected', 'forbidden'].includes(snapshot.status)) setSecurityPanelOpen(true);
  }), []);

  useEffect(() => {
    selectedPaymentRef.current = selectedPayment;
  }, [selectedPayment]);

  useEffect(() => {
    const handleVisibility = () => setDocumentVisible(document.visibilityState !== 'hidden');
    document.addEventListener('visibilitychange', handleVisibility);
    return () => document.removeEventListener('visibilitychange', handleVisibility);
  }, []);

  useEffect(() => {
    if (restoringWorkspace || restoredWorkspaceRef.current.orderId) return;
    const orderId = order?.zippy_order_id || null;
    try {
      window.localStorage.setItem('zippy.workspace', JSON.stringify({ screen, orderId }));
    } catch {
      // Persistence is a progressive enhancement.
    }
    const nextHash = workspaceHash(screen, orderId);
    if (window.location.hash !== nextHash) window.history.replaceState(null, '', nextHash);
  }, [screen, order?.zippy_order_id, restoringWorkspace]);

  useEffect(() => {
    if (skipInitialFocusRef.current) {
      skipInitialFocusRef.current = false;
      return;
    }
    requestAnimationFrame(() => screenHeadingRef.current?.focus({ preventScroll: false }));
  }, [screen]);

  useEffect(() => {
    const orderId = restoredWorkspaceRef.current.orderId;
    if (!orderId) return undefined;
    let active = true;
    setRestoringWorkspace(true);
    const controller = new AbortController();
    api(`/api/orders/${encodeURIComponent(orderId)}`, { signal: controller.signal })
      .then((payload) => {
        if (!active) return;
        const matchedCarrier = payload.shippingOptions?.find(
          (option) => option.carrierCode === payload.selectedShipment?.carrier_code
            && option.serviceCode === payload.selectedShipment?.selected_service_code,
        );
        setOrder(payload);
        setRates(payload.shippingOptions || []);
        setSelectedCarrier(matchedCarrier || null);
        setTracking(payload);
        setPaymentType((payload.payment_type || payload.paymentType || 'COD').toUpperCase());
        setStatusMessage(`Restored ${payload.zippy_order_id}`);
        restoredWorkspaceRef.current.orderId = null;
      })
      .catch((error) => {
        if (active && !isAbortError(error) && !isAuthError(error)) {
          setErrorMessage(`Could not restore ${orderId}: ${error.message}`);
          if (['rates', 'details', 'webhooks'].includes(restoredWorkspaceRef.current.screen)) setScreen('create');
          restoredWorkspaceRef.current.orderId = null;
        }
      })
      .finally(() => {
        if (active) setRestoringWorkspace(false);
      });
    return () => {
      active = false;
      controller.abort();
    };
  }, [authRetryKey]);

  useEffect(() => {
    if (!isDocumentVisible || !order?.zippy_order_id || !['details', 'webhooks', 'payments'].includes(screen)) return undefined;
    let active = true;
    let timer;
    const controller = new AbortController();

    async function refreshTracking(initial = false) {
      if (initial && !tracking) setLoadingTracking(true);
      try {
        const payload = await api(`/api/orders/${order.zippy_order_id}/tracking`, { signal: controller.signal });
        if (active) {
          setTracking(payload);
          markRefresh('tracking', 'ok');
        }
      } catch (error) {
        if (active && !isAbortError(error) && !isAuthError(error)) markRefresh('tracking', 'error', error.message);
      } finally {
        if (active) {
          setLoadingTracking(false);
          timer = setTimeout(refreshTracking, 4000);
        }
      }
    }

    refreshTracking(true);

    return () => {
      active = false;
      controller.abort();
      clearTimeout(timer);
    };
  }, [order?.zippy_order_id, screen, isDocumentVisible, authRetryKey]);

  useEffect(() => {
    if (!isDocumentVisible) return undefined;
    let active = true;
    let timer;
    const controller = new AbortController();

    async function refreshSystemOverview() {
      try {
        const payload = await api('/api/system/overview', { signal: controller.signal });
        if (active) {
          setSystemOverview(payload);
          markRefresh('overview', 'ok');
        }
      } catch (error) {
        if (active && !isAbortError(error) && !isAuthError(error)) markRefresh('overview', 'error', error.message);
      } finally {
        if (active) timer = setTimeout(refreshSystemOverview, 10000);
      }
    }

    refreshSystemOverview();

    return () => {
      active = false;
      controller.abort();
      clearTimeout(timer);
    };
  }, [isDocumentVisible, authRetryKey]);

  useEffect(() => {
    if (!isDocumentVisible) return undefined;
    let active = true;
    let timer;
    const controller = new AbortController();

    async function refreshOrderHistory() {
      setLoadingHistory(true);
      try {
        const payload = await api('/api/orders/history?limit=6', { signal: controller.signal });
        if (active) {
          setOrderHistory(payload.orders || []);
          markRefresh('history', 'ok');
        }
      } catch (error) {
        if (active && !isAbortError(error) && !isAuthError(error)) markRefresh('history', 'error', error.message);
      } finally {
        if (active) {
          setLoadingHistory(false);
          timer = setTimeout(refreshOrderHistory, 12000);
        }
      }
    }

    refreshOrderHistory();

    return () => {
      active = false;
      controller.abort();
      clearTimeout(timer);
    };
  }, [isDocumentVisible, historyRefreshKey, authRetryKey]);

  useEffect(() => {
    if (!isDocumentVisible || screen !== 'payments') return undefined;
    let active = true;
    let pollTimer;
    let debounceTimer;
    const controller = new AbortController();

    async function refreshFinanceRegister() {
      setLoadingFinance(true);
      try {
        const query = financeQuery(paymentFilters, {
          includeSearch: true,
          limit: PAYMENT_PAGE_SIZE,
          offset: paymentOffset,
        });
        const payload = await api(`/api/reports/payments?${query}`, { signal: controller.signal });
        if (active) {
          const nextPayments = payload.payments || [];
          setPaymentReport(payload);
          setPaymentHistory(nextPayments);
          const currentSelection = selectedPaymentRef.current;
          const matchingSelection = nextPayments.find((item) => item.paymentId === currentSelection?.paymentId);
          if (matchingSelection) {
            setSelectedPayment(matchingSelection);
          } else if (currentSelection) {
            ledgerControllerRef.current?.abort();
            setSelectedPayment(null);
            setPaymentTransactions([]);
            setPaymentTransactionTotal(0);
            setLedgerError('');
            setPaymentAction((current) => current?.payment.paymentId === currentSelection.paymentId ? null : current);
            setPaymentActionError('');
          }
          setFinanceError('');
          markRefresh('finance', 'ok');
        }
      } catch (error) {
        if (active && !isAbortError(error) && !isAuthError(error)) {
          setFinanceError(error.message);
          markRefresh('finance', 'error', error.message);
        }
      } finally {
        if (active) {
          setLoadingFinance(false);
          pollTimer = setTimeout(refreshFinanceRegister, 15000);
        }
      }
    }

    debounceTimer = setTimeout(refreshFinanceRegister, 250);
    return () => {
      active = false;
      controller.abort();
      clearTimeout(debounceTimer);
      clearTimeout(pollTimer);
    };
  }, [
    screen,
    paymentFilters.search,
    paymentFilters.status,
    paymentFilters.method,
    paymentOffset,
    reportRefreshKey,
    isDocumentVisible,
    authRetryKey,
  ]);

  useEffect(() => {
    if (!isDocumentVisible || screen !== 'reports') return undefined;
    let active = true;
    let timer;
    const controller = new AbortController();

    async function refreshReports() {
      setLoadingReports(true);
      try {
        const query = financeQuery(reportFilters);
        const paymentQuery = financeQuery(reportFilters, { limit: 50, offset: 0 });
        const [summaryPayload, paymentsPayload] = await Promise.all([
          api(`/api/reports/summary${query ? `?${query}` : ''}`, { signal: controller.signal }),
          api(`/api/reports/payments?${paymentQuery}`, { signal: controller.signal }),
        ]);
        if (active) {
          setReportsSummary(summaryPayload);
          setReportPaymentReport(paymentsPayload);
          setReportPaymentHistory(paymentsPayload.payments || []);
          setReportsError('');
          markRefresh('reports', 'ok');
        }
      } catch (error) {
        if (active && !isAbortError(error) && !isAuthError(error)) {
          setReportsError(error.message);
          markRefresh('reports', 'error', error.message);
        }
      } finally {
        if (active) {
          setLoadingReports(false);
          timer = setTimeout(refreshReports, 30000);
        }
      }
    }

    refreshReports();
    return () => {
      active = false;
      controller.abort();
      clearTimeout(timer);
    };
  }, [
    screen,
    reportFilters.from,
    reportFilters.to,
    reportFilters.status,
    reportFilters.method,
    reportFilters.carrier,
    reportRefreshKey,
    isDocumentVisible,
    authRetryKey,
  ]);

  useEffect(() => {
    if (!isDocumentVisible || !order?.zippy_order_id || !['details', 'rates', 'payments'].includes(screen)) return undefined;
    let active = true;
    let timer;
    const controller = new AbortController();

    async function refreshOrderPayments() {
      try {
        const payments = await api(`/api/orders/${order.zippy_order_id}/payments`, { signal: controller.signal });
        if (active) {
          setOrderPayments(payments || []);
          markRefresh('payments', 'ok');
        }
      } catch (error) {
        if (active && !isAbortError(error) && !isAuthError(error)) markRefresh('payments', 'error', error.message);
      } finally {
        if (active && screen === 'payments') timer = setTimeout(refreshOrderPayments, 4000);
      }
    }

    refreshOrderPayments();
    return () => {
      active = false;
      controller.abort();
      clearTimeout(timer);
    };
  }, [order?.zippy_order_id, screen, isDocumentVisible, authRetryKey]);

  useEffect(() => {
    if (!order?.zippy_order_id || screen !== 'rates') return undefined;
    let active = true;
    const controller = new AbortController();

    async function refreshRatesView() {
      try {
        const payload = await api(`/api/orders/${order.zippy_order_id}`, { signal: controller.signal });
        if (!active) return;
        if (payload?.shippingOptions?.length) {
          setRates(payload.shippingOptions);
        }
        if (payload?.selectedShipment) {
          const matchingOption = payload.shippingOptions?.find(
            (option) =>
              option.carrierCode === payload.selectedShipment.carrier_code &&
              option.serviceCode === payload.selectedShipment.selected_service_code,
          );
          if (matchingOption) {
            setSelectedCarrier(matchingOption);
          }
        }
        markRefresh('rates', 'ok');
      } catch (error) {
        if (active && !isAbortError(error) && !isAuthError(error)) markRefresh('rates', 'error', error.message);
      }
    }

    refreshRatesView();
    return () => {
      active = false;
      controller.abort();
    };
  }, [order?.zippy_order_id, screen, authRetryKey]);

  async function verifyApiConnection() {
    if (authChecking) return;
    setAuthChecking(true);
    setErrorMessage('');
    try {
      const overview = await api('/api/system/overview');
      setSystemOverview(overview);
      setRefreshState({});
      setAuthRetryKey((value) => value + 1);
      setStatusMessage(getSessionApiKey() ? 'Secure API connection verified for this tab.' : 'Local API connection verified.');
      setSecurityPanelOpen(false);
      requestAnimationFrame(() => connectionTriggerRef.current?.focus({ preventScroll: true }));
    } catch (error) {
      if (!isAuthError(error) && !isAbortError(error)) {
        setAuthState({ status: 'unknown', message: error.message });
        setSecurityPanelOpen(true);
      }
    } finally {
      setAuthChecking(false);
    }
  }

  async function handleApiKeySubmit(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const key = new FormData(form).get('operationalApiKey');
    if (!String(key || '').trim()) return;
    setSessionApiKey(key);
    form.reset();
    await verifyApiConnection();
  }

  async function handleClearApiKey() {
    clearSessionApiKey();
    setRefreshState({});
    setStatusMessage('Session API key cleared.');
    await verifyApiConnection();
  }

  async function loadRates(orderId, nextSortBy = sortBy, signal) {
    return api(`/api/orders/${orderId}/rates?sortBy=${encodeURIComponent(nextSortBy)}`, { signal });
  }

  async function handleCreateOrder(event) {
    event.preventDefault();
    if (creatingOrder) return;
    if (!event.currentTarget.reportValidity()) return;
    setCreatingOrder(true);
    setErrorMessage('');
    setStatusMessage('');

    try {
      const form = new FormData(event.currentTarget);
      const payload = {
        merchantOrderId: form.get('merchantOrderId'),
        customer: {
          name: form.get('customerName'),
          phone: form.get('customerPhone'),
          email: form.get('customerEmail'),
        },
        pickupAddress: {
          addressLine1: form.get('pickupAddressLine1'),
          city: form.get('pickupCity'),
          state: form.get('pickupState'),
          pincode: form.get('pickupPincode'),
        },
        deliveryAddress: {
          addressLine1: form.get('deliveryAddressLine1'),
          city: form.get('deliveryCity'),
          state: form.get('deliveryState'),
          pincode: form.get('deliveryPincode'),
        },
        package: {
          weightGrams: Number(form.get('weightGrams')),
          lengthCm: Number(form.get('lengthCm')),
          widthCm: Number(form.get('widthCm')),
          heightCm: Number(form.get('heightCm')),
        },
        paymentType,
        codAmount: paymentType === 'COD' ? Number(form.get('codAmount')) : null,
      };

      createIdempotencyRef.current = idempotencyEntry(
        createIdempotencyRef.current,
        payload,
        () => globalThis.crypto?.randomUUID?.() || `order-${Date.now()}-${Math.random().toString(36).slice(2)}`,
      );

      const payloadBody = await api('/api/orders', {
        method: 'POST',
        headers: { 'Idempotency-Key': createIdempotencyRef.current.key },
        body: JSON.stringify(payload),
      });

      setOrder(payloadBody);
      setRates(payloadBody.shippingOptions || []);
      setSelectedCarrier(null);
      setTracking(null);
      setOrderPayments([]);
      createIdempotencyRef.current = null;
      setStatusMessage(`Created ${payloadBody.zippy_order_id}`);
      startTransition(() => setScreen('rates'));
    } catch (error) {
      setErrorMessage(error.message);
    } finally {
      setCreatingOrder(false);
    }
  }

  async function handleSort(nextSortBy) {
    const previousSortBy = sortBy;
    setSortBy(nextSortBy);
    if (!order?.zippy_order_id) {
      return;
    }
    sortControllerRef.current?.abort();
    const controller = new AbortController();
    sortControllerRef.current = controller;
    setSortingRates(true);
    setErrorMessage('');
    try {
      const payload = await loadRates(order.zippy_order_id, nextSortBy, controller.signal);
      if (sortControllerRef.current === controller) {
        setRates(payload.shippingOptions || []);
        markRefresh('rates', 'ok');
      }
    } catch (error) {
      if (!isAbortError(error) && sortControllerRef.current === controller) {
        setSortBy(previousSortBy);
        setErrorMessage(error.message);
        markRefresh('rates', 'error', error.message);
      }
    } finally {
      if (sortControllerRef.current === controller) setSortingRates(false);
    }
  }

  async function handleCreatePaymentIntent() {
    if (processingPayment || !order?.zippy_order_id || !selectedCarrier) {
      return;
    }
    setProcessingPayment(true);
    setErrorMessage('');
    try {
      const payment = await mutatePayment('/api/payments', {
        orderId: order.zippy_order_id,
        amount: selectedCarrier.totalCharge,
        currency: 'INR',
      });
      upsertPayment(payment);
      setStatusMessage(`Payment intent ${payment.paymentId} created`);
      setReportRefreshKey((value) => value + 1);
    } catch (error) {
      setErrorMessage(error.message);
    } finally {
      setProcessingPayment(false);
    }
  }

  async function mutatePayment(path, body) {
    const fingerprintPayload = { path, body: body || null };
    paymentMutationRef.current = idempotencyEntry(
      paymentMutationRef.current,
      fingerprintPayload,
      () => newIdempotencyKey('payment'),
    );
    const response = await api(path, {
      method: 'POST',
      headers: { 'Idempotency-Key': paymentMutationRef.current.key },
      body: body == null ? undefined : JSON.stringify(body),
    });
    paymentMutationRef.current = null;
    return response;
  }

  function upsertPayment(payment) {
    const upsert = (current) => [payment, ...current.filter((item) => item.paymentId !== payment.paymentId)];
    setOrderPayments((current) => payment.orderId === order?.zippy_order_id ? upsert(current) : current);
    setPaymentHistory(upsert);
    setReportPaymentHistory(upsert);
    setSelectedPayment((current) => current?.paymentId === payment.paymentId ? payment : current);
  }

  function resolvePayment(paymentOrId) {
    if (typeof paymentOrId === 'object') return paymentOrId;
    return [...orderPayments, ...paymentHistory, ...reportPaymentHistory]
      .find((payment) => payment.paymentId === paymentOrId);
  }

  function beginPaymentAction(type, paymentOrId) {
    const payment = resolvePayment(paymentOrId);
    if (!payment || processingPayment) return;
    paymentActionReturnFocusRef.current = document.activeElement;
    setPaymentAction({ type, payment });
    setPaymentActionError('');
    setSelectedPayment(payment);
  }

  function closePaymentAction(focusHeading = false) {
    const returnTarget = paymentActionReturnFocusRef.current;
    setPaymentAction(null);
    setPaymentActionError('');
    requestAnimationFrame(() => {
      if (focusHeading) screenHeadingRef.current?.focus({ preventScroll: false });
      else if (returnTarget instanceof HTMLElement && returnTarget.isConnected) returnTarget.focus({ preventScroll: false });
    });
  }

  function handleConfirmPayment(paymentId) {
    beginPaymentAction('confirm', paymentId);
  }

  function handleCollectPayment(paymentId) {
    beginPaymentAction('collect', paymentId);
  }

  function handleFailPayment(paymentId) {
    beginPaymentAction('fail', paymentId);
  }

  function handleCancelPayment(paymentId) {
    beginPaymentAction('cancel', paymentId);
  }

  function handleRefundPayment(paymentId) {
    beginPaymentAction('refund', paymentId);
  }

  async function loadPaymentLedger(payment) {
    if (!payment?.paymentId) return;
    ledgerControllerRef.current?.abort();
    const controller = new AbortController();
    ledgerControllerRef.current = controller;
    setSelectedPayment(payment);
    setPaymentTransactions([]);
    setPaymentTransactionTotal(0);
    setLoadingLedger(true);
    setLedgerError('');
    try {
      const payload = await api(`/api/payments/${encodeURIComponent(payment.paymentId)}/transactions?limit=50&offset=0`, {
        signal: controller.signal,
      });
      if (ledgerControllerRef.current === controller) {
        setPaymentTransactions(payload.transactions || []);
        setPaymentTransactionTotal(Number(payload.totalTransactions ?? payload.transactions?.length ?? 0));
      }
    } catch (error) {
      if (!isAbortError(error) && ledgerControllerRef.current === controller) setLedgerError(error.message);
    } finally {
      if (ledgerControllerRef.current === controller) setLoadingLedger(false);
    }
  }

  async function handlePaymentActionSubmit(event) {
    event.preventDefault();
    if (!paymentAction || processingPayment || !event.currentTarget.reportValidity()) return;
    const form = new FormData(event.currentTarget);
    const reason = String(form.get('reason') || '').trim();
    const type = paymentAction.type;
    const payment = paymentAction.payment;
    let body = { reason };
    if (['confirm', 'collect', 'refund'].includes(type)) {
      body = {
        ...body,
        provider: String(form.get('provider') || '').trim(),
        reference: String(form.get('reference') || '').trim(),
        reconciliationReference: String(form.get('reconciliationReference') || '').trim(),
      };
    } else if (type === 'fail') {
      body = {
        code: String(form.get('code') || '').trim(),
        reason,
        reference: String(form.get('reference') || '').trim() || null,
      };
    }

    setProcessingPayment(true);
    setPaymentActionError('');
    try {
      const updated = await mutatePayment(`/api/payments/${encodeURIComponent(payment.paymentId)}/${type}`, body);
      upsertPayment(updated);
      closePaymentAction(true);
      setStatusMessage(`${updated.paymentId} updated to ${readableStatus(updated.status)}`);
      setReportRefreshKey((value) => value + 1);
      await loadPaymentLedger(updated);
    } catch (error) {
      setPaymentActionError(error.message);
    } finally {
      setProcessingPayment(false);
    }
  }

  async function handleCancelOrder() {
    if (cancellingOrder || !order?.zippy_order_id) {
      return;
    }
    if (!window.confirm(`Cancel order ${order.zippy_order_id}? This stops its current fulfilment workflow.`)) return;
    setCancellingOrder(true);
    setErrorMessage('');
    try {
      const payload = await api(`/api/orders/${order.zippy_order_id}/cancel`, { method: 'POST' });
      setOrder(payload);
      setTracking(payload);
      setStatusMessage(`Order ${payload.zippy_order_id} cancelled`);
      startTransition(() => setScreen('details'));
    } catch (error) {
      setErrorMessage(error.message);
    } finally {
      setCancellingOrder(false);
    }
  }

  async function handleSelectCarrier(option) {
    if (!order?.zippy_order_id) {
      return;
    }
    selectionControllerRef.current?.abort();
    const controller = new AbortController();
    selectionControllerRef.current = controller;
    const optionKey = `${option.carrierCode}:${option.serviceCode}`;
    setSelectingCarrierKey(optionKey);
    setErrorMessage('');
    try {
      const payload = await api(`/api/orders/${order.zippy_order_id}/select-carrier`, {
        method: 'POST',
        signal: controller.signal,
        body: JSON.stringify({
          carrierCode: option.carrierCode,
          serviceCode: option.serviceCode,
          quotedAmount: option.totalCharge,
        }),
      });
      if (selectionControllerRef.current !== controller) return;
      if (payload?.selectedShipment) {
        setOrder((current) =>
          current
            ? {
                ...current,
                order_status: 'CARRIER_SELECTED',
                selectedShipment: {
                  ...current.selectedShipment,
                  ...payload.selectedShipment,
                },
              }
            : current,
        );
      }
      setSelectedCarrier(option);
      setStatusMessage(`Selected ${option.carrierName}`);
    } catch (error) {
      if (!isAbortError(error) && selectionControllerRef.current === controller) setErrorMessage(error.message);
    } finally {
      if (selectionControllerRef.current === controller) setSelectingCarrierKey('');
    }
  }

  async function handleCreateShipment() {
    if (!order?.zippy_order_id) {
      return;
    }
    setCreatingShipment(true);
    setErrorMessage('');
    try {
      await api(`/api/orders/${order.zippy_order_id}/create-shipment`, {
        method: 'POST',
      });
      const payload = await api(`/api/orders/${order.zippy_order_id}/tracking`);
      setTracking(payload);
      setStatusMessage('Shipment created successfully');
      startTransition(() => setScreen('details'));
    } catch (error) {
      setErrorMessage(error.message);
    } finally {
      setCreatingShipment(false);
    }
  }

  async function handleAdvanceStatus() {
    if (!order?.zippy_order_id) {
      return;
    }
    setLoadingTracking(true);
    setErrorMessage('');
    try {
      await api(`/api/mock-carriers/${order.zippy_order_id}/advance`, { method: 'POST' });
      const payload = await api(`/api/orders/${order.zippy_order_id}/tracking`);
      setTracking(payload);
    } catch (error) {
      setErrorMessage(error.message);
    } finally {
      setLoadingTracking(false);
    }
  }

  async function handleRefreshTracking() {
    if (!order?.zippy_order_id) {
      return;
    }
    setLoadingTracking(true);
    setErrorMessage('');
    try {
      const payload = await api(`/api/orders/${order.zippy_order_id}/tracking`);
      setTracking(payload);
    } catch (error) {
      setErrorMessage(error.message);
    } finally {
      setLoadingTracking(false);
    }
  }

  async function handleOpenHistoryOrder(item) {
    historyControllerRef.current?.abort();
    const controller = new AbortController();
    historyControllerRef.current = controller;
    setOpeningHistoryId(item.zippyOrderId);
    setErrorMessage('');
    try {
      const payload = await api(`/api/orders/${item.zippyOrderId}`, { signal: controller.signal });
      if (historyControllerRef.current !== controller) return;
      const selectedShipment = payload.selectedShipment;
      const matchedCarrier = payload.shippingOptions?.find(
        (option) =>
          option.carrierCode === selectedShipment?.carrier_code &&
          option.serviceCode === selectedShipment?.selected_service_code,
      );

      setOrder(payload);
      setRates(payload.shippingOptions || []);
      setSelectedCarrier(matchedCarrier || null);
      setTracking(payload);
      setPaymentType((payload.payment_type || payload.paymentType || 'COD').toUpperCase());
      setStatusMessage(`Loaded ${payload.zippy_order_id} from history`);
      setErrorMessage('');

      const status = currentStatusFor(payload);
      startTransition(() => setScreen(status === 'ORDER_CREATED' || status === 'CARRIER_SELECTED' ? 'rates' : 'details'));
    } catch (error) {
      if (!isAbortError(error) && historyControllerRef.current === controller) setErrorMessage(error.message);
    } finally {
      if (historyControllerRef.current === controller) setOpeningHistoryId('');
    }
  }

  function handleStartNewOrder() {
    createFormRef.current?.reset();
    restoredWorkspaceRef.current.orderId = null;
    setOrder(null);
    setTracking(null);
    setRates([]);
    setSelectedCarrier(null);
    setOrderPayments([]);
    setCreatingShipment(false);
    setPaymentType('COD');
    setRefreshState({});
    createIdempotencyRef.current = null;
    setStatusMessage('Ready to create a new order');
    setErrorMessage('');
    startTransition(() => setScreen('create'));
  }

  function useSampleData() {
    const form = createFormRef.current;
    if (!form) {
      return;
    }

    const sampleOrder = createSampleOrder();
    Object.entries({
      merchantOrderId: sampleOrder.merchantOrderId,
      customerName: shellValue(sampleOrder.customer, 'name'),
      customerPhone: shellValue(sampleOrder.customer, 'phone'),
      customerEmail: shellValue(sampleOrder.customer, 'email'),
      pickupAddressLine1: shellValue(sampleOrder.pickupAddress, 'addressLine1'),
      pickupCity: shellValue(sampleOrder.pickupAddress, 'city'),
      pickupState: shellValue(sampleOrder.pickupAddress, 'state'),
      pickupPincode: shellValue(sampleOrder.pickupAddress, 'pincode'),
      deliveryAddressLine1: shellValue(sampleOrder.deliveryAddress, 'addressLine1'),
      deliveryCity: shellValue(sampleOrder.deliveryAddress, 'city'),
      deliveryState: shellValue(sampleOrder.deliveryAddress, 'state'),
      deliveryPincode: shellValue(sampleOrder.deliveryAddress, 'pincode'),
      weightGrams: shellValue(sampleOrder.package, 'weightGrams'),
      lengthCm: shellValue(sampleOrder.package, 'lengthCm'),
      widthCm: shellValue(sampleOrder.package, 'widthCm'),
      heightCm: shellValue(sampleOrder.package, 'heightCm'),
      codAmount: sampleOrder.codAmount,
    }).forEach(([key, value]) => {
      const element = form.elements.namedItem(key);
      if (element) element.value = String(value);
    });
    setPaymentType(sampleOrder.paymentType);
    requestAnimationFrame(() => {
      const codAmount = createFormRef.current?.elements.namedItem('codAmount');
      if (codAmount) codAmount.value = String(sampleOrder.codAmount);
    });
    setStatusMessage(`Loaded sample ${sampleOrder.merchantOrderId}`);
    createIdempotencyRef.current = null;
  }

  function handlePaymentFiltersChange(nextFilters) {
    setPaymentFilters(nextFilters);
    setPaymentOffset(0);
  }

  function handleClearPaymentFilters() {
    setPaymentFilters({ search: '', status: 'all', method: 'all' });
    setPaymentOffset(0);
  }

  function handleReportPreset(preset) {
    if (preset === 'custom') {
      setReportFilterDraft((current) => ({ ...current, preset }));
      return;
    }
    const range = dateRangeForPreset(preset);
    const nextFilters = { ...reportFilterDraft, ...range, preset };
    setReportFilterDraft(nextFilters);
    setReportFilters(nextFilters);
    setReportFilterError('');
  }

  function handleApplyReportFilters(event) {
    event.preventDefault();
    const validation = validateDateRange(reportFilterDraft.from, reportFilterDraft.to);
    if (!validation.valid) {
      setReportFilterError(validation.error);
      return;
    }
    setReportFilterError('');
    setReportFilters(reportFilterDraft);
  }

  const history = tracking?.shipmentEvents || [];
  const liveOrder = tracking || order;
  const currentWorkflow = workflowState(liveOrder);
  const currentStatus = currentStatusFor(liveOrder);
  const isPrepaidOrder = (liveOrder?.payment_type || liveOrder?.paymentType || '').toUpperCase() === 'PREPAID';
  const pendingPayment = orderPayments.find((payment) => payment.status === 'PENDING');
  const succeededPayment = orderPayments.find((payment) => payment.status === 'SUCCEEDED');
  const failedPayment = orderPayments.find((payment) => payment.status === 'FAILED');
  const cancelledPayment = orderPayments.find((payment) => payment.status === 'CANCELLED');
  const refundPendingPayment = orderPayments.find((payment) => payment.status === 'REFUND_PENDING');
  const refundedPayment = orderPayments.find((payment) => payment.status === 'REFUNDED');
  const codPayment = orderPayments.find((payment) => payment.paymentMethod === 'COD');
  const latestOrderPayment = orderPayments[0] || null;
  const isDelivered = currentStatus === 'DELIVERED';
  const canRefundPrepaid = ['CANCELLED', 'RTO', 'DELIVERED'].includes(currentStatus);
  // The server searches both snapshots and immutable ledger references; re-filtering here would hide valid matches.
  const visiblePaymentHistory = paymentHistory;
  const paymentFinance = financeSnapshot(paymentReport, visiblePaymentHistory);
  const reportFinance = financeSnapshot(reportsSummary, reportPaymentHistory);
  const reportTrendRange = reportsSummary?.filters?.fromInclusive && reportsSummary?.filters?.toExclusive
    ? `UTC ${String(reportsSummary.filters.fromInclusive).slice(0, 10)} to before ${String(reportsSummary.filters.toExclusive).slice(0, 10)}`
    : reportFilters.from && reportFilters.to
      ? `Requested UTC period: ${reportFilters.from} through ${reportFilters.to}`
      : 'All available UTC dates';
  const metrics = [
    { label: 'Quotes', value: String(rates.length || order?.shippingOptions?.length || 0), tone: 'accent' },
    { label: 'Current status', value: currentStatus ? readableStatus(currentStatus) : 'No active order', tone: statusTone(currentStatus) },
    { label: 'Events', value: String(history.length), tone: 'neutral' },
  ];
  const activeRefreshKeys = new Set(['overview', 'history']);
  if (order?.zippy_order_id && ['details', 'webhooks', 'payments'].includes(screen)) activeRefreshKeys.add('tracking');
  if (order?.zippy_order_id && ['details', 'rates', 'payments'].includes(screen)) activeRefreshKeys.add('payments');
  if (order?.zippy_order_id && screen === 'rates') activeRefreshKeys.add('rates');
  if (screen === 'payments') activeRefreshKeys.add('finance');
  if (screen === 'reports') activeRefreshKeys.add('reports');
  const degradedRefreshes = Object.entries(refreshState)
    .filter(([key, value]) => activeRefreshKeys.has(key) && value.status === 'error');
  const apiKeyActive = Boolean(getSessionApiKey());
  const connectionLabels = {
    unknown: 'Connection',
    checking: 'Checking',
    open: 'Local API',
    authenticated: 'Secure API',
    required: 'Key required',
    rejected: 'Key rejected',
    forbidden: 'Access limited',
  };

  return (
    <>
    <a className="skip-link" href="#main-content">Skip to active workspace</a>
    <main className="shell" aria-busy={restoringWorkspace || isPending}>
      <aside className="hero">
        <div>
          <div className="brand-row">
            <div className="brand-mark">Z</div>
            <div>
              <p className="eyebrow">Zippy Logistics</p>
              <h1>Operations console for merchant shipping.</h1>
            </div>
          </div>

          <div className="rail-spacer large" aria-hidden="true" />

          <p className="hero-copy">
            Create orders, compare carrier quotes, and monitor normalized shipment events in a workspace designed to feel
            ready for clients, not just demos.
          </p>

          <nav className="sidebar-nav" aria-label="Sidebar navigation">
            <p className="sidebar-nav-label">Workspace</p>
            {Object.entries(screenMeta).map(([value, meta]) => (
              <button key={value} type="button" className={screen === value ? 'active' : ''} aria-current={screen === value ? 'page' : undefined} disabled={['rates', 'details'].includes(value) && !order} onClick={value === 'create' ? handleStartNewOrder : () => setScreen(value)}>
                <span aria-hidden="true">{value === 'create' ? '+' : meta.label.slice(0, 1)}</span> {meta.label}
              </button>
            ))}
          </nav>

          <div className="rail-spacer large" aria-hidden="true" />

          <section className="sidebar-overview" aria-label="Operational overview">
            <div className="sidebar-overview-head">
              <p className="section-label">Overview</p>
              <span>{order ? 'Live workspace' : 'Awaiting order'}</span>
            </div>
            <div className="stat-grid">
              {metrics.map((metric) => (
                <MetricCard key={metric.label} {...metric} />
              ))}
            </div>
          </section>

          <div className="rail-spacer" aria-hidden="true" />

          {systemOverview ? (
            <section className="system-card">
              <div className="system-head">
                <div>
                  <p className="section-label">System design</p>
                  <h3>Operational snapshot</h3>
                </div>
                <span className={`system-badge ${systemOverview.automationEnabled ? 'on' : 'off'}`}>
                  {systemOverview.automationEnabled ? 'Automation on' : 'Automation off'}
                </span>
              </div>

              <div className="system-stats">
                <div>
                  <strong>{systemOverview.orders ?? 0}</strong>
                  <span>Orders</span>
                </div>
                <div>
                  <strong>{systemOverview.shipments ?? 0}</strong>
                  <span>Shipments</span>
                </div>
                <div>
                  <strong>{systemOverview.events ?? 0}</strong>
                  <span>Events</span>
                </div>
                <div>
                  <strong>{systemOverview.payments ?? 0}</strong>
                  <span>Payments</span>
                </div>
                <div>
                  <strong>{systemOverview.carrierTimeoutMs ?? 0}ms</strong>
                  <span>Carrier timeout</span>
                </div>
              </div>

              <div className="system-foot">
                <span>{systemOverview.supportedCarriers?.join(' | ')}</span>
              </div>
            </section>
          ) : null}

          <div className="rail-spacer" aria-hidden="true" />

          <WorkflowRail state={currentWorkflow} />
        </div>

        <p className="hero-footnote">
          {isPending ? 'Updating view...' : 'Use npm run dev for instant UI updates without restarting.'}
        </p>
      </aside>

      <section className="panel" id="main-content">
        <header className="topbar">
          <div className="topbar-title">
            <span>Operations workspace</span>
            <strong>{screenMeta[screen]?.title || 'Operations workspace'}</strong>
          </div>
          <nav className="topbar-nav" aria-label="Primary navigation">
            {Object.entries(screenMeta).map(([value, meta]) => (
              <button key={value} type="button" className={screen === value ? 'active' : ''} aria-current={screen === value ? 'page' : undefined} onClick={value === 'create' ? handleStartNewOrder : () => setScreen(value)} disabled={['rates', 'details'].includes(value) && !order}>{meta.label}</button>
            ))}
          </nav>
          <div className="topbar-actions">
            <button
              ref={connectionTriggerRef}
              type="button"
              className={`connection-trigger ${authState.status}`}
              aria-expanded={securityPanelOpen}
              aria-controls="security-panel"
              onClick={() => setSecurityPanelOpen((open) => !open)}
            >
              <span aria-hidden="true" />{connectionLabels[authState.status] || connectionLabels.unknown}
            </button>
            {order ? <span className="topbar-order">{order.zippy_order_id}</span> : <span className="topbar-order">No active order</span>}
            <button type="button" className="primary topbar-new-order" onClick={handleStartNewOrder}>New order</button>
          </div>
        </header>

        <div className="live-region" aria-live="polite" aria-atomic="true">
          {statusMessage ? <div className="notice success" role="status">{statusMessage}</div> : null}
        </div>
        {errorMessage ? <div className="notice error" role="alert">{errorMessage}</div> : null}
        {!isDocumentVisible ? <div className="notice neutral" role="status">Live refresh is paused while this tab is hidden.</div> : null}
        {degradedRefreshes.length ? <div className="notice warning" role="status">Live refresh degraded for {degradedRefreshes.map(([key]) => key).join(', ')}. Existing data may be stale.</div> : null}

        {securityPanelOpen ? (
          <ConnectionSecurityPanel
            auth={authState}
            keyActive={apiKeyActive}
            checking={authChecking}
            onSubmit={handleApiKeySubmit}
            onClear={handleClearApiKey}
            onRetry={verifyApiConnection}
            onClose={() => {
              setSecurityPanelOpen(false);
              requestAnimationFrame(() => connectionTriggerRef.current?.focus({ preventScroll: true }));
            }}
          />
        ) : null}

        <PaymentActionPanel
          key={paymentAction ? `${paymentAction.type}:${paymentAction.payment.paymentId}` : 'payment-action-closed'}
          action={paymentAction}
          processing={processingPayment}
          error={paymentActionError}
          onSubmit={handlePaymentActionSubmit}
          onClose={() => closePaymentAction(false)}
        />

        <section className="workspace-banner">
          <div className="workspace-copy">
            <p className="section-label">Operations workspace</p>
            <h2>Order intake, carrier selection, and tracking in one calm view.</h2>
            <p className="subtle">
              The left rail keeps the journey visible while this panel handles the active order, rate comparison, and
              webhook history.
            </p>
          </div>

          <div className="workspace-tags">
            <span>Single scroll</span>
            <span>Live history</span>
            <span>H2 + Flyway</span>
          </div>
        </section>

        <section className="history-card">
          <div className="history-card-head">
            <div>
              <p className="section-label">Order history</p>
              <h3>Recent orders</h3>
            </div>
            <span>{orderHistory.length} shown</span>
          </div>

          {orderHistory.length ? (
            <div className="order-history-list">
              {orderHistory.map((item) => (
                <article key={item.zippyOrderId} className="order-history-item">
                  <div className="order-history-main">
                    <strong>{item.zippyOrderId}</strong>
                    <span>{item.merchantOrderId}</span>
                  </div>
                  <div className="order-history-meta">
                    <span>{item.orderStatus?.replaceAll('_', ' ')}</span>
                    <span>{item.selectedShipment?.carrierCode || 'No carrier yet'}</span>
                    <small>{formatDateTime(item.updatedAt)}</small>
                    <button
                      type="button"
                      className={historyActionVariant(item)}
                      disabled={Boolean(openingHistoryId)}
                      onClick={() => handleOpenHistoryOrder(item)}
                    >
                      {openingHistoryId === item.zippyOrderId ? 'Opening...' : historyActionLabel(item)}
                    </button>
                  </div>
                </article>
              ))}
            </div>
          ) : refreshState.history?.status === 'error' ? (
            <div className="empty error-state"><span>Recent orders are unavailable.</span><button type="button" className="ghost" onClick={() => setHistoryRefreshKey((value) => value + 1)}>Retry</button></div>
          ) : loadingHistory ? (
            <div className="empty">Loading recent orders...</div>
          ) : (
            <div className="empty">No orders created yet.</div>
          )}
        </section>

        {screen === 'create' ? (
          <section className="card">
            <SectionHeader
              eyebrow="Step 1"
              title="Create Order"
              headingRef={screenHeadingRef}
              headingId="screen-heading"
              subtitle="Enter merchant, customer, and package details to generate live rate options."
              action={
                <button type="button" className="ghost" onClick={useSampleData} disabled={creatingOrder}>
                  Load sample data
                </button>
              }
            />

            <form ref={createFormRef} className="create-form" onSubmit={handleCreateOrder} aria-busy={creatingOrder}>
              {formGroups.map((group) => (
                <fieldset className="form-group" key={group.legend}>
                  <legend>{group.legend}</legend>
                  <p>{group.description}</p>
                  <div className="field-grid">
                    {group.fields.map((field) => <OrderField key={field.name} {...field} />)}
                  </div>
                </fieldset>
              ))}
              <fieldset className="form-group">
                <legend>Payment</legend>
                <p>Choose how the order will be paid.</p>
                <div className="field-grid">
                  <label htmlFor="paymentType"><span>Payment type <span className="required-mark" aria-hidden="true">*</span></span>
                    <select id="paymentType" name="paymentType" value={paymentType} onChange={(event) => setPaymentType(event.target.value)} required>
                      <option value="COD">Cash on delivery</option><option value="PREPAID">Prepaid</option>
                    </select>
                  </label>
                  {paymentType === 'COD' ? <OrderField name="codAmount" label="COD amount (INR)" type="number" min="0.01" max="10000000" step="0.01" inputMode="decimal" /> : <div className="payment-note">Prepaid payment is collected after carrier selection.</div>}
                </div>
              </fieldset>
              <div className="actions">
                <span className="helper">The backend will fetch all rates immediately after order creation.</span>
                <button type="submit" className="primary" disabled={creatingOrder}>
                  {creatingOrder ? 'Creating order...' : 'Create Order'}
                </button>
              </div>
            </form>
          </section>
        ) : null}

        {screen === 'rates' ? (
          <section className="card">
            <SectionHeader
              eyebrow="Step 2"
              title="Courier Selection"
              headingRef={screenHeadingRef}
              headingId="screen-heading"
              subtitle={`Order ID: ${order?.zippy_order_id || '-'}`}
              action={
                <label className="inline">
                  Sort by
                  <select value={sortBy} onChange={(event) => handleSort(event.target.value)} disabled={sortingRates} aria-label="Sort carrier rates">
                    <option value="lowest">Lowest price</option>
                    <option value="fastest">Fastest delivery</option>
                    <option value="carrier">Carrier name</option>
                  </select>
                </label>
              }
            />

            {rates.length ? (
              <div className="rates-grid" aria-busy={sortingRates}>
                {rates.map((rate) => {
                  const isSelected =
                    selectedCarrier?.carrierCode === rate.carrierCode &&
                    selectedCarrier?.serviceCode === rate.serviceCode;

                  return (
                    <article key={`${rate.carrierCode}:${rate.serviceCode}`} className={`rate-card ${isSelected ? 'selected' : ''}`}>
                      <div className="rate-card-head">
                        <div>
                          <p className="section-label">Carrier quote</p>
                          <h3>{rate.carrierName}</h3>
                          <p className="subtle">
                            {rate.serviceName} / {rate.serviceCode}
                          </p>
                        </div>
                        <button
                          type="button"
                          className={isSelected ? 'selected' : 'primary'}
                          disabled={Boolean(selectingCarrierKey)}
                          onClick={() => handleSelectCarrier(rate)}
                        >
                          {selectingCarrierKey === `${rate.carrierCode}:${rate.serviceCode}` ? 'Selecting...' : isSelected ? 'Selected' : 'Select carrier'}
                        </button>
                      </div>

                      <div className="rate-grid">
                        <div>
                          <span>Base</span>
                          <strong>{money(rate.baseCharge)}</strong>
                        </div>
                        <div>
                          <span>COD</span>
                          <strong>{money(rate.codCharge)}</strong>
                        </div>
                        <div>
                          <span>Other</span>
                          <strong>{money(rate.additionalCharges)}</strong>
                        </div>
                        <div>
                          <span>Tax</span>
                          <strong>{money(rate.tax)}</strong>
                        </div>
                      </div>

                      <div className="rate-card-foot">
                        <strong>{money(rate.totalCharge)}</strong>
                        <span>{estimateLabel(rate)}</span>
                        <small>{rate.carrierCode}</small>
                      </div>
                    </article>
                  );
                })}
              </div>
            ) : (
              <div className="empty">No shipping options yet.</div>
            )}

            {selectedCarrier ? (
              <div className="selection-bar">
                <div>
                  <p className="section-label">Selected option</p>
                  <strong>
                    {selectedCarrier.carrierName} - {selectedCarrier.serviceName}
                  </strong>
                  <p className="subtle">
                    {money(selectedCarrier.totalCharge)} - {estimateLabel(selectedCarrier)}
                  </p>
                  {isPrepaidOrder && !succeededPayment ? (
                    <p className="subtle">
                      Prepaid order: {pendingPayment ? `Confirm payment ${pendingPayment.paymentId}` : 'Create a payment intent before shipment'}
                    </p>
                  ) : null}
                </div>
                <div className="selection-actions">
                  {isPrepaidOrder && !succeededPayment ? (
                    pendingPayment ? (
                      <button
                        type="button"
                        className="primary"
                        disabled={processingPayment}
                        onClick={() => handleConfirmPayment(pendingPayment.paymentId)}
                      >
                        {processingPayment ? 'Processing...' : 'Confirm Payment'}
                      </button>
                    ) : (
                      <button
                        type="button"
                        className="primary"
                        disabled={processingPayment}
                        onClick={handleCreatePaymentIntent}
                      >
                        {processingPayment ? 'Processing...' : 'Create Payment'}
                      </button>
                    )
                  ) : null}
                  <button
                    type="button"
                    className="primary"
                    disabled={creatingShipment || (isPrepaidOrder && !succeededPayment)}
                    title={isPrepaidOrder && !succeededPayment ? 'Confirm payment before creating the shipment' : undefined}
                    onClick={handleCreateShipment}
                  >
                    {creatingShipment
                      ? 'Creating...'
                      : isPrepaidOrder && !succeededPayment
                        ? 'Confirm payment to continue'
                        : 'Create Shipment'}
                  </button>
                </div>
              </div>
            ) : (
              <div className="empty subtle-note">Pick a carrier to continue to shipment creation.</div>
            )}
          </section>
        ) : null}

        {screen === 'details' ? (
          <section className="card">
            <SectionHeader
              eyebrow="Step 3"
              title="Order Details and Tracking"
              headingRef={screenHeadingRef}
              headingId="screen-heading"
              subtitle="Shipment metadata, carrier selection, and webhook-driven history."
              action={
                <div className="selection-actions">
                  <StatusPill status={currentStatus} />
                  {isCancellableStatus(currentStatus) ? (
                    <button type="button" className="ghost" onClick={handleCancelOrder} disabled={cancellingOrder}>
                      {cancellingOrder ? 'Cancelling...' : 'Cancel order'}
                    </button>
                  ) : null}
                </div>
              }
            />

            {tracking ? (
              <>
                <div className="detail-grid">
                  <div>
                    <span>Zippy order ID</span>
                    <strong>{tracking.zippy_order_id}</strong>
                  </div>
                  <div>
                    <span>Merchant order ID</span>
                    <strong>{tracking.merchant_order_id}</strong>
                  </div>
                  <div>
                    <span>Customer</span>
                    <strong>{tracking.customer_name}</strong>
                  </div>
                  <div>
                    <span>Selected carrier</span>
                    <strong>{tracking.selectedShipment?.carrier_code || '-'}</strong>
                  </div>
                  <div>
                    <span>Selected service</span>
                    <strong>{tracking.selectedShipment?.selected_service_code || '-'}</strong>
                  </div>
                  <div>
                    <span>Shipping charge</span>
                    <strong>{tracking.selectedShipment ? money(tracking.selectedShipment.quoted_amount) : '-'}</strong>
                  </div>
                  <div>
                    <span>Tracking number</span>
                    <strong>{tracking.selectedShipment?.tracking_number || '-'}</strong>
                  </div>
                  <div>
                    <span>Current status</span>
                    <strong>{currentStatusFor(tracking).replaceAll('_', ' ')}</strong>
                  </div>
                  <div>
                    <span>Payment type</span>
                    <strong>{tracking.payment_type || tracking.paymentType || '-'}</strong>
                  </div>
                </div>

                {orderPayments.length ? (
                  <div className="history-wrap">
                    <div className="history-head">
                      <h3>Payments</h3>
                      <span>{orderPayments.length} records</span>
                    </div>
                    <ul className="history">
                      {orderPayments.map((payment) => (
                        <li key={payment.paymentId}>
                          <div>
                            <strong>{payment.paymentId}</strong>
                            <span>
                              {money(payment.amount)} {payment.currency} / {readableStatus(payment.status)}
                            </span>
                          </div>
                          <div className="history-actions">
                            <small>{formatDateTime(payment.createdAt)}</small>
                            {payment.paymentMethod === 'COD' && payment.status === 'AWAITING_COLLECTION' ? (
                              <button
                                type="button"
                                className="ghost compact-button"
                                disabled={currentStatusFor(tracking) !== 'DELIVERED' || processingPayment}
                                onClick={() => handleCollectPayment(payment.paymentId)}
                              >
                                {currentStatusFor(tracking) === 'DELIVERED' ? 'Record collection' : 'Awaiting delivery'}
                              </button>
                            ) : null}
                          </div>
                        </li>
                      ))}
                    </ul>
                  </div>
                ) : null}

                <div className="history-wrap">
                  <div className="history-head">
                    <h3>Status history</h3>
                    <span>{history.length} events</span>
                  </div>

                  {history.length ? (
                    <ul className="history">
                      {history.map((event) => (
                        <li key={event.carrierEventId}>
                          <div>
                            <strong>{event.status}</strong>
                            <span>{event.description}</span>
                          </div>
                          <small>{new Date(event.eventTime).toLocaleString()}</small>
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <div className="empty">No shipment history yet.</div>
                  )}
                </div>

                <div className="actions">
                  <button type="button" onClick={handleRefreshTracking} disabled={loadingTracking}>
                    {loadingTracking ? 'Refreshing...' : 'Refresh'}
                  </button>
                  <button type="button" onClick={handleAdvanceStatus} disabled={loadingTracking}>
                    {loadingTracking ? 'Updating...' : 'Trigger Next Status'}
                  </button>
                </div>
              </>
            ) : (
              <div className="empty">Create a shipment to see tracking details.</div>
            )}
          </section>
        ) : null}

        {screen === 'payments' ? (
          <section className="card finance-workspace" aria-busy={loadingFinance && !paymentReport}>
            <SectionHeader
              eyebrow="Finance operations"
              title="Payments"
              headingRef={screenHeadingRef}
              headingId="screen-heading"
              subtitle="Reconcile collections, exceptions, refunds, and immutable ledger events across the filtered payment register."
              action={
                <div className="selection-actions">
                  <button type="button" className="ghost" disabled={loadingFinance} onClick={() => setReportRefreshKey((value) => value + 1)}>
                    {loadingFinance ? 'Refreshing...' : 'Refresh register'}
                  </button>
                  <button type="button" className="ghost" disabled={!visiblePaymentHistory.length} onClick={() => downloadPaymentsCsv(visiblePaymentHistory)}>
                    Export shown rows
                  </button>
                </div>
              }
            />

            {financeError && paymentReport ? (
              <div className="notice warning" role="status">Showing the last successful payment snapshot. Refresh failed: {financeError}</div>
            ) : null}
            {paymentReport && !paymentReport.finance ? <div className="notice warning" role="status">Legacy finance response detected. Summary values are calculated only from the loaded rows.</div> : null}
            {financeError && !paymentReport ? (
              <div className="empty error-state" role="alert">
                <span>{financeError}</span>
                <button type="button" className="ghost" onClick={() => setReportRefreshKey((value) => value + 1)}>Retry register</button>
              </div>
            ) : null}

            <div className="finance-summary-grid" aria-label="Payment register summary">
              <FinanceMetric label="Gross collected" value={money(paymentFinance.grossCollected)} detail={paymentReport?.finance ? 'All matching captured and collected payments' : 'Fallback from loaded rows'} tone="success" />
              <FinanceMetric label="Refunds issued" value={money(paymentFinance.refunds)} detail="Completed refund value" tone="danger" />
              <FinanceMetric label="Net collected" value={money(paymentFinance.netCollected)} detail="Gross collected less completed refunds" tone="accent" />
              <FinanceMetric
                label="Outstanding"
                value={money(paymentFinance.outstanding)}
                detail={paymentReport?.finance ? `${money(paymentFinance.pendingIntent || 0)} pending intent / ${money(paymentFinance.codOutstanding || 0)} COD` : 'Pending intents and COD obligations in loaded rows'}
                tone="warning"
              />
              <FinanceMetric label="Matching payments" value={String(paymentReport?.totalPayments ?? visiblePaymentHistory.length)} detail="Full filtered register count" />
              <FinanceMetric label="Failed attempts" value={String(paymentFinance.failed)} detail="Operational exceptions in this scope" tone={paymentFinance.failed ? 'danger' : 'neutral'} />
            </div>

            <section className="finance-insight-link" aria-labelledby="payments-trend-title">
              <div className="insight-monogram" aria-hidden="true">D</div>
              <div>
                <p className="section-label">Finance intelligence</p>
                <h3 id="payments-trend-title">Daily Trends</h3>
                <p>Review gross collections, completed refunds, net movement, and transaction volume by UTC day.</p>
              </div>
              <button type="button" className="ghost" onClick={() => startTransition(() => setScreen('reports'))}>Open Daily Trends</button>
            </section>

            {order ? (
              <section className="active-finance-card" aria-labelledby="active-finance-title">
                <div>
                  <p className="section-label">Active order</p>
                  <h3 id="active-finance-title">{order.zippy_order_id}</h3>
                  <p className="subtle">
                    {isPrepaidOrder ? 'Prepaid checkout' : 'Cash on delivery'} / {selectedCarrier ? `${selectedCarrier.carrierName} / ${money(selectedCarrier.totalCharge)}` : 'Carrier not selected'}
                  </p>
                </div>
                <div className="active-finance-actions">
                  {latestOrderPayment ? (
                    <>
                      <StatusPill status={latestOrderPayment.status} />
                      <button type="button" className="ghost" onClick={() => loadPaymentLedger(latestOrderPayment)}>Open ledger</button>
                      {latestOrderPayment.status === 'AWAITING_COLLECTION' ? (
                        <button type="button" className="primary" disabled={!isDelivered || processingPayment} title={!isDelivered ? 'Delivery must be confirmed before COD collection' : undefined} onClick={() => handleCollectPayment(latestOrderPayment.paymentId)}>
                          {isDelivered ? 'Record COD collection' : 'Awaiting delivery'}
                        </button>
                      ) : null}
                    </>
                  ) : <span className="subtle">No payment record exists for this order yet.</span>}
                  {isPrepaidOrder && selectedCarrier && (!latestOrderPayment || ['FAILED', 'CANCELLED'].includes(latestOrderPayment.status)) ? (
                    <button type="button" className="primary" disabled={processingPayment} onClick={handleCreatePaymentIntent}>
                      {processingPayment ? 'Creating...' : latestOrderPayment ? 'Start new attempt' : 'Create payment intent'}
                    </button>
                  ) : null}
                  {isPrepaidOrder && !selectedCarrier ? <button type="button" className="ghost" onClick={() => setScreen('rates')}>Select carrier</button> : null}
                </div>
              </section>
            ) : null}

            <PaymentFilterBar filters={paymentFilters} onChange={handlePaymentFiltersChange} onClear={handleClearPaymentFilters} />

            <div className="finance-register-layout">
              <PaymentTable
                payments={visiblePaymentHistory}
                total={Number(paymentReport?.totalPayments ?? visiblePaymentHistory.length)}
                offset={paymentOffset}
                limit={PAYMENT_PAGE_SIZE}
                loading={loadingFinance}
                selectedId={selectedPayment?.paymentId}
                onSelect={loadPaymentLedger}
                onPrevious={() => setPaymentOffset((value) => Math.max(0, value - PAYMENT_PAGE_SIZE))}
                onNext={() => setPaymentOffset((value) => value + PAYMENT_PAGE_SIZE)}
              />
              <PaymentDetail
                payment={selectedPayment}
                transactions={paymentTransactions}
                totalTransactions={paymentTransactionTotal}
                loading={loadingLedger}
                error={ledgerError}
                activeOrderId={order?.zippy_order_id}
                canCollect={isDelivered}
                canRefund={canRefundPrepaid}
                onRetry={() => loadPaymentLedger(selectedPayment)}
                onAction={beginPaymentAction}
              />
            </div>
          </section>
        ) : null}

        {screen === 'webhooks' ? (
          <section className="card">
            <SectionHeader
              eyebrow="Carrier events"
              title="Webhooks"
              headingRef={screenHeadingRef}
              headingId="screen-heading"
              subtitle="Normalized carrier callbacks, duplicate protection, and shipment status history."
              action={
                <button
                  type="button"
                  className="ghost"
                  disabled={!order || loadingTracking}
                  onClick={handleRefreshTracking}
                >
                  {loadingTracking ? 'Refreshing...' : 'Refresh events'}
                </button>
              }
            />

            <div className="report-panels">
              {[
                ['FastShip', '/api/webhooks/fastship', 'shipment_id + event_code'],
                ['QuickExpress', '/api/webhooks/quickexpress', 'awb + event.type'],
                ['Reliable Courier', '/api/webhooks/reliable', 'trackingCode + statusId'],
              ].map(([carrier, endpoint, payload]) => (
                <article className="report-panel" key={carrier}>
                  <h3>{carrier}</h3>
                  <p className="subtle">POST {endpoint}</p>
                  <span className="webhook-schema">{payload}</span>
                </article>
              ))}
            </div>

            <div className="history-wrap">
              <div className="history-head">
                <h3>Active order events</h3>
                <span>{history.length} events</span>
              </div>
              {order && history.length ? (
                <ul className="history">
                  {history.map((event) => (
                    <li key={event.carrierEventId}>
                      <div>
                        <strong>{event.status}</strong>
                        <span>{event.description}</span>
                      </div>
                      <small>{formatDateTime(event.eventTime)}</small>
                    </li>
                  ))}
                </ul>
              ) : (
                <div className="empty">Create a shipment to see webhook events here.</div>
              )}
              {order ? (
                <div className="actions webhook-actions">
                  <button type="button" className="primary" onClick={handleAdvanceStatus} disabled={loadingTracking}>
                    {loadingTracking ? 'Receiving callback...' : 'Simulate next carrier callback'}
                  </button>
                  {currentStatus === 'OUT_FOR_DELIVERY' ? (
                    <button type="button" className="ghost" onClick={async () => {
                      if (!window.confirm('Simulate a delivery failure for this order?')) return;
                      try {
                        await api(`/api/mock-carriers/${order.zippy_order_id}/delivery-failed`, { method: 'POST' });
                        await handleRefreshTracking();
                      } catch (error) {
                        setErrorMessage(error.message);
                      }
                    }} disabled={loadingTracking}>
                      Simulate delivery failure
                    </button>
                  ) : null}
                  {currentStatus === 'DELIVERY_FAILED' ? (
                    <button type="button" className="ghost" onClick={async () => {
                      if (!window.confirm('Move this failed delivery to return-to-origin? This ends the delivery attempt.')) return;
                      try {
                        await api(`/api/mock-carriers/${order.zippy_order_id}/rto`, { method: 'POST' });
                        await handleRefreshTracking();
                      } catch (error) {
                        setErrorMessage(error.message);
                      }
                    }} disabled={loadingTracking}>
                      Simulate RTO
                    </button>
                  ) : null}
                  <span className="helper">Useful for testing normalized statuses and duplicate-safe event handling.</span>
                </div>
              ) : null}
            </div>
          </section>
        ) : null}

        {screen === 'reports' ? (
          <section className="card finance-workspace" aria-busy={loadingReports && !reportsSummary}>
            <SectionHeader
              eyebrow="Finance intelligence"
              title="Payment and Shipping Cost Reports"
              headingRef={screenHeadingRef}
              headingId="screen-heading"
              subtitle="Filtered cash movement, payment balances, and carrier charges with explicit UTC accounting boundaries."
              action={
                <div className="selection-actions">
                  <button type="button" className="ghost" disabled={loadingReports} onClick={() => setReportRefreshKey((value) => value + 1)}>
                    {loadingReports ? 'Refreshing...' : 'Refresh report'}
                  </button>
                  <button type="button" className="ghost" disabled={!reportPaymentHistory.length} onClick={() => downloadPaymentsCsv(reportPaymentHistory)}>
                    Export shown rows
                  </button>
                </div>
              }
            />

            <ReportFilterBar
              filters={reportFilterDraft}
              error={reportFilterError}
              onPreset={handleReportPreset}
              onChange={setReportFilterDraft}
              onApply={handleApplyReportFilters}
            />

            {reportsError && reportsSummary ? <div className="notice warning" role="status">Showing the last successful report snapshot. Refresh failed: {reportsError}</div> : null}
            {reportsSummary && !reportsSummary.finance ? <div className="notice warning" role="status">Legacy finance response detected. Cash metrics are calculated only from the loaded payment page.</div> : null}

            <DailyTrend
              rows={reportsSummary?.dailyTrend || []}
              loading={loadingReports || (!reportsSummary && !reportsError)}
              error={!reportsSummary ? reportsError : ''}
              rangeLabel={reportTrendRange}
              onRetry={() => setReportRefreshKey((value) => value + 1)}
            />

            {reportsSummary ? (
              <>
                <section className="report-scope" aria-labelledby="report-scope-title">
                  <div>
                    <p className="section-label">Metric scope</p>
                    <h3 id="report-scope-title">Filtered finance, explicit accounting dates</h3>
                  </div>
                  <ul>
                    <li><strong>Finance:</strong> {reportsSummary.scope?.finance === 'FILTERED' ? 'selected filters' : 'reported scope'}</li>
                    <li><strong>Cash flow:</strong> payment transaction creation date in UTC</li>
                    <li><strong>Balances:</strong> payment creation date in UTC</li>
                    <li><strong>Shipment cost:</strong> shipment creation date in UTC</li>
                    <li><strong>Operational totals:</strong> {reportsSummary.scope?.operationalTotals === 'ALL_TIME' ? 'all time' : 'reported scope'}</li>
                  </ul>
                  {reportsSummary.filters?.fromInclusive ? (
                    <p>Server boundary: {reportsSummary.filters.fromInclusive} through, but excluding, {reportsSummary.filters.toExclusive}.</p>
                  ) : <p>Server boundary: all available dates.</p>}
                </section>

                <div className="finance-summary-grid" aria-label="Filtered finance key performance indicators">
                  <FinanceMetric label="Gross collected" value={money(reportFinance.grossCollected)} detail="Captured prepaid and collected COD before refunds" tone="success" />
                  <FinanceMetric label="Refunds issued" value={money(reportFinance.refunds)} detail="Completed refund value" tone="danger" />
                  <FinanceMetric label="Net collected" value={money(reportFinance.netCollected)} detail="Gross collected less completed refunds" tone="accent" />
                  <FinanceMetric
                    label="COD outstanding"
                    value={money(reportsSummary.finance?.codOutstanding?.amount ?? 0)}
                    detail={`${reportsSummary.finance?.codOutstanding?.count ?? 0} uncollected obligations`}
                    tone="warning"
                  />
                  <FinanceMetric
                    label="Pending intents"
                    value={money(reportsSummary.finance?.pendingIntent?.amount ?? 0)}
                    detail={`${reportsSummary.finance?.pendingIntent?.count ?? 0} open prepaid attempts`}
                    tone="warning"
                  />
                  <FinanceMetric
                    label="Shipment cost"
                    value={money(reportsSummary.finance?.shipmentCost ?? reportsSummary.totalShippingCost ?? 0)}
                    detail={`${reportsSummary.finance?.costedShipmentCount ?? 0} costed shipments`}
                  />
                  <FinanceMetric
                    label="Active shipments"
                    value={String(reportsSummary.finance?.activeShipmentCount ?? 0)}
                    detail="Current work, excluding delivered shipments"
                  />
                  <FinanceMetric
                    label="Matching payments"
                    value={String(reportPaymentReport?.totalPayments ?? reportPaymentHistory.length)}
                    detail="Payment records in the selected finance scope"
                  />
                </div>

                <div className="breakdown-grid">
                  <BreakdownPanel
                    title="Carrier shipping cost"
                    rows={reportsSummary.carrierBreakdown || []}
                    renderLabel={(row) => carrierLabel(row.carrier)}
                    renderValue={(row) => `${row.shipments} shipments / ${money(row.shippingCost)}`}
                  />
                  <BreakdownPanel
                    title="Payments by status"
                    rows={reportsSummary.paymentsByStatus || []}
                    renderLabel={(row) => readableStatus(row.label)}
                    renderValue={(row) => `${row.count} / ${money(row.totalAmount)} face value`}
                  />
                  <BreakdownPanel
                    title="Orders by payment type (all time)"
                    rows={reportsSummary.ordersByPaymentType || []}
                    renderLabel={(row) => readableStatus(row.label)}
                    renderValue={(row) => `${row.count} orders`}
                  />
                </div>

                <section className="report-export-note" aria-labelledby="report-export-title">
                  <div>
                    <p className="section-label">Audit export</p>
                    <h3 id="report-export-title">Loaded payment page</h3>
                    <p className="subtle">CSV exports exactly the {reportPaymentHistory.length} shown rows out of {reportPaymentReport?.totalPayments ?? reportPaymentHistory.length} matching payments. It does not imply an all-record export.</p>
                  </div>
                  <button type="button" className="ghost" disabled={!reportPaymentHistory.length} onClick={() => downloadPaymentsCsv(reportPaymentHistory)}>Export shown rows</button>
                </section>
              </>
            ) : null}
          </section>
        ) : null}
      </section>
    </main>
    </>
  );
}

createRoot(document.getElementById('root')).render(<App />);
