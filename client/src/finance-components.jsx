import { useEffect, useRef } from 'react';
import { formatDateTime, money, readableStatus, statusTone } from './app-utils.js';
import { PAYMENT_METHODS, PAYMENT_STATUSES, normalizeDailyTrend, paymentTimeline } from './finance-utils.js';

const ACTION_COPY = {
  confirm: {
    eyebrow: 'Capture funds',
    title: 'Confirm prepaid payment',
    submit: 'Confirm payment',
    warning: 'This records the payment as collected and may unlock shipment creation.',
    auditFields: true,
  },
  collect: {
    eyebrow: 'Cash reconciliation',
    title: 'Record COD collection',
    submit: 'Record collection',
    warning: 'Only continue after delivery and physical cash reconciliation are verified.',
    auditFields: true,
  },
  fail: {
    eyebrow: 'Payment exception',
    title: 'Record payment failure',
    submit: 'Mark as failed',
    warning: 'This closes the current payment attempt. The order can start a new attempt later.',
    failureFields: true,
  },
  cancel: {
    eyebrow: 'Close checkout',
    title: 'Cancel pending payment',
    submit: 'Cancel payment',
    warning: 'This closes the pending checkout and cannot be reversed.',
  },
  refund: {
    eyebrow: 'Return funds',
    title: 'Issue full refund',
    submit: 'Issue full refund',
    warning: 'This is a full refund. Verify the order state and reconciliation references before continuing.',
    auditFields: true,
  },
};

function referenceFor(payment) {
  return payment.refundReconciliationReference || payment.reconciliationReference || payment.providerReference || payment.refundReference || '';
}

function dateLabel(value) {
  const date = new Date(`${value}T00:00:00.000Z`);
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', timeZone: 'UTC' });
}

export function FinanceMetric({ label, value, detail, tone = 'neutral' }) {
  return (
    <article className={`finance-metric ${tone}`}>
      <span>{label}</span>
      <strong>{value}</strong>
      {detail ? <small>{detail}</small> : null}
    </article>
  );
}

export function PaymentFilterBar({ filters, onChange, onClear }) {
  function update(event) {
    onChange({ ...filters, [event.target.name]: event.target.value });
  }

  return (
    <form className="finance-filters" role="search" onSubmit={(event) => event.preventDefault()}>
      <label className="finance-search" htmlFor="payment-search">
        <span>Search transactions</span>
        <input
          id="payment-search"
          name="search"
          type="search"
          value={filters.search}
          onChange={update}
          maxLength={64}
          autoComplete="off"
          placeholder="Payment, order, or reference"
        />
      </label>
      <label htmlFor="payment-status-filter">
        <span>Status</span>
        <select id="payment-status-filter" name="status" value={filters.status} onChange={update}>
          <option value="all">All statuses</option>
          {PAYMENT_STATUSES.map((status) => <option key={status} value={status}>{readableStatus(status)}</option>)}
        </select>
      </label>
      <label htmlFor="payment-method-filter">
        <span>Method</span>
        <select id="payment-method-filter" name="method" value={filters.method} onChange={update}>
          <option value="all">All methods</option>
          {PAYMENT_METHODS.map((method) => <option key={method} value={method}>{readableStatus(method)}</option>)}
        </select>
      </label>
      <button type="button" className="ghost finance-clear" onClick={onClear}>Clear filters</button>
    </form>
  );
}

export function PaymentTable({
  payments,
  total,
  offset,
  limit,
  loading,
  selectedId,
  onSelect,
  onPrevious,
  onNext,
}) {
  const first = payments.length ? offset + 1 : 0;
  const last = payments.length ? offset + payments.length : 0;
  const hasNext = offset + limit < total;

  return (
    <section className="transaction-list" aria-labelledby="transaction-list-title" aria-busy={loading}>
      <div className="finance-section-head">
        <div className="finance-section-copy">
          <p className="section-label">Filtered register</p>
          <h3 id="transaction-list-title">Payment register</h3>
          <p>Choose a payment to inspect its immutable ledger, references, and available controls.</p>
        </div>
        <span className="result-count">{loading ? 'Refreshing...' : `${first}-${last} of ${total}`}</span>
      </div>
      {payments.length ? (
        <div className="table-scroll" role="region" tabIndex={0} aria-label="Payment register; scroll horizontally when needed">
          <table className="finance-table">
            <caption className="sr-only">Filtered payment snapshots. Open a row to view its immutable transaction ledger.</caption>
            <thead>
              <tr>
                <th scope="col">Transaction</th>
                <th scope="col">Order</th>
                <th scope="col">Method</th>
                <th scope="col" className="numeric">Value</th>
                <th scope="col">Status</th>
                <th scope="col"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {payments.map((payment) => (
                <tr key={payment.paymentId} className={selectedId === payment.paymentId ? 'selected-row' : ''}>
                  <td data-label="Transaction">
                    <strong className="identifier-value" title={payment.paymentId}>{payment.paymentId}</strong>
                    <small className="secondary-identifier" title={`${payment.provider || 'Internal'}${payment.providerReference ? ` / ${payment.providerReference}` : ''}`}>
                      {payment.provider || 'Internal'}{payment.providerReference ? ` / ${payment.providerReference}` : ''}
                    </small>
                  </td>
                  <td data-label="Order">
                    <strong className="identifier-value" title={payment.orderId}>{payment.orderId}</strong>
                    <small>{formatDateTime(payment.createdAt)}</small>
                  </td>
                  <td data-label="Method"><span>{readableStatus(payment.paymentMethod)}</span><small>{payment.collectionStage ? readableStatus(payment.collectionStage) : 'Standard'}</small></td>
                  <td data-label="Value" className="numeric">
                    <strong>{money(payment.amount)}</strong>
                    {Number(payment.refundedAmount) > 0
                      ? <small>{money(payment.refundedAmount)} refunded / {money(Number(payment.amount || 0) - Number(payment.refundedAmount || 0))} after refunds</small>
                      : <small>{payment.currency || 'INR'} / no refund</small>}
                  </td>
                  <td data-label="Status"><span className={`status-pill ${statusTone(payment.status)}`}>{readableStatus(payment.status)}</span><small>Updated {formatDateTime(payment.updatedAt)}</small></td>
                  <td data-label="Ledger">
                    <button
                      type="button"
                      className="ghost compact-button"
                      aria-label={`View ledger for payment ${payment.paymentId}`}
                      aria-pressed={selectedId === payment.paymentId}
                      onClick={() => onSelect(payment)}
                    >
                      View ledger
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="empty">{loading ? 'Loading payment transactions...' : 'No transactions match these filters.'}</div>
      )}
      <div className="pagination-actions" aria-label="Payment page navigation">
        <button type="button" className="ghost" disabled={loading || offset === 0} onClick={onPrevious}>Previous</button>
        <span>Rows {first}-{last}</span>
        <button type="button" className="ghost" disabled={loading || !hasNext} onClick={onNext}>Next</button>
      </div>
    </section>
  );
}

function actionsFor(payment, { activeOrderId, canCollect, canRefund }) {
  if (!payment) return [];
  if (payment.status === 'PENDING' && payment.paymentMethod === 'PREPAID') return ['confirm', 'fail', 'cancel'];
  const hasActiveOrderContext = payment.orderId === activeOrderId;
  if (payment.status === 'AWAITING_COLLECTION' && payment.paymentMethod === 'COD' && hasActiveOrderContext && canCollect) return ['collect'];
  if (payment.status === 'SUCCEEDED' && payment.paymentMethod === 'PREPAID' && hasActiveOrderContext && canRefund) return ['refund'];
  if (payment.status === 'REFUND_PENDING' && hasActiveOrderContext && canRefund) return ['refund'];
  return [];
}

export function PaymentDetail({
  payment,
  transactions,
  totalTransactions,
  loading,
  error,
  activeOrderId,
  canCollect,
  canRefund,
  onRetry,
  onAction,
}) {
  if (!payment) {
    return (
      <aside className="payment-detail empty-detail" aria-label="Payment ledger detail">
        <div className="empty-detail-mark" aria-hidden="true">L</div>
        <p className="section-label">Ledger inspector</p>
        <h3>Select a payment</h3>
        <p>Open a register row to inspect references, timestamps, attribution, and immutable transaction history.</p>
      </aside>
    );
  }
  const timeline = paymentTimeline(payment, transactions);
  const availableActions = actionsFor(payment, { activeOrderId, canCollect, canRefund });
  const needsShipmentEligibility = (payment.status === 'AWAITING_COLLECTION' && payment.paymentMethod === 'COD')
    || (['SUCCEEDED', 'REFUND_PENDING'].includes(payment.status) && payment.paymentMethod === 'PREPAID');

  return (
    <aside className="payment-detail" aria-labelledby="payment-detail-title" aria-busy={loading}>
      <div className="finance-section-head">
        <div className="finance-section-copy">
          <p className="section-label">Ledger inspector</p>
          <h3 id="payment-detail-title" className="detail-identifier" title={payment.paymentId}>{payment.paymentId}</h3>
          <p>Order <span className="inline-identifier" title={payment.orderId}>{payment.orderId}</span></p>
        </div>
        <span className={`status-pill ${statusTone(payment.status)}`}>{readableStatus(payment.status)}</span>
      </div>
      <dl className="payment-metadata">
        <div><dt>Face value</dt><dd>{money(payment.amount)}</dd></div>
        <div><dt>Refunded</dt><dd>{money(payment.refundedAmount || 0)}</dd></div>
        <div><dt>Amount less refunds</dt><dd>{money(Number(payment.amount || 0) - Number(payment.refundedAmount || 0))}</dd></div>
        <div><dt>Order</dt><dd title={payment.orderId}>{payment.orderId}</dd></div>
        <div><dt>Method</dt><dd>{readableStatus(payment.paymentMethod)}</dd></div>
        <div><dt>Provider</dt><dd>{payment.provider || 'Internal'}</dd></div>
        <div><dt>Provider reference</dt><dd title={payment.providerReference || undefined}>{payment.providerReference || 'Not assigned'}</dd></div>
        <div><dt>Reconciliation</dt><dd title={payment.reconciliationReference || undefined}>{payment.reconciliationReference || 'Not assigned'}</dd></div>
        <div><dt>Refund reference</dt><dd title={payment.refundReference || undefined}>{payment.refundReference || 'Not assigned'}</dd></div>
        <div><dt>Refund reconciliation</dt><dd title={payment.refundReconciliationReference || undefined}>{payment.refundReconciliationReference || 'Not assigned'}</dd></div>
        <div><dt>Created</dt><dd>{formatDateTime(payment.createdAt)}</dd></div>
        <div><dt>Last updated</dt><dd>{formatDateTime(payment.updatedAt)}</dd></div>
        {payment.capturedAt ? <div><dt>Captured</dt><dd>{formatDateTime(payment.capturedAt)}</dd></div> : null}
        {payment.collectedAt ? <div><dt>COD collected</dt><dd>{formatDateTime(payment.collectedAt)}</dd></div> : null}
        {payment.refundedAt ? <div><dt>Refunded</dt><dd>{formatDateTime(payment.refundedAt)}</dd></div> : null}
      </dl>
      {payment.failureReason ? <div className="notice error" role="status">{payment.failureCode ? `${payment.failureCode}: ` : ''}{payment.failureReason}</div> : null}
      {availableActions.length ? (
        <div className="ledger-actions" aria-label="Available payment actions">
          {availableActions.map((action) => (
            <button key={action} type="button" className={['confirm', 'collect'].includes(action) ? 'primary' : 'ghost'} onClick={() => onAction(action, payment)}>
              {ACTION_COPY[action].submit}
            </button>
          ))}
        </div>
      ) : (
        <p className="subtle">
          {needsShipmentEligibility
            ? 'Collection and refund controls appear only when this payment belongs to the active order and its shipment status proves eligibility.'
            : 'No financial action is available for this payment state.'}
        </p>
      )}
      <div className="ledger-head">
        <h4>Transaction timeline</h4>
        <span>{transactions.length ? `Showing ${transactions.length} of ${totalTransactions ?? transactions.length} ledger entries` : 'Lifecycle summary'}</span>
      </div>
      {error ? (
        <div className="empty error-state" role="alert"><span>{error}</span><button type="button" className="ghost" onClick={onRetry}>Retry</button></div>
      ) : timeline.length ? (
        <ol className="ledger-timeline">
          {timeline.map((entry) => (
            <li key={entry.id}>
              <span className="ledger-dot" aria-hidden="true" />
              <div>
                <div className="ledger-event-head">
                  <strong>{readableStatus(entry.label)}</strong>
                  <time dateTime={entry.createdAt}>{formatDateTime(entry.createdAt)}</time>
                </div>
                <p>{entry.previousStatus ? `${readableStatus(entry.previousStatus)} to ` : ''}{entry.status ? readableStatus(entry.status) : 'Recorded'} / {money(entry.amount)}</p>
                {entry.reference || entry.provider ? (
                  <small>
                    {entry.provider || 'Internal'}
                    {entry.providerReference ? ` / provider ${entry.providerReference}` : ''}
                    {entry.reconciliationReference ? ` / reconciliation ${entry.reconciliationReference}` : ''}
                  </small>
                ) : null}
                <small className="ledger-actor">
                  <span>Actor</span>
                  <strong>{entry.actor || 'Not reported'}</strong>
                </small>
                {entry.reason ? <small>{entry.reason}</small> : null}
              </div>
            </li>
          ))}
        </ol>
      ) : <div className="empty">{loading ? 'Loading ledger...' : 'No ledger entries are available.'}</div>}
    </aside>
  );
}

export function PaymentActionPanel({ action, processing, error, onSubmit, onClose }) {
  const firstFieldRef = useRef(null);
  useEffect(() => {
    firstFieldRef.current?.focus({ preventScroll: false });
  }, [action?.type]);
  if (!action) return null;
  const copy = ACTION_COPY[action.type];
  const payment = action.payment;

  return (
    <section className="payment-action-panel" aria-labelledby="payment-action-title">
      <div className="finance-section-head">
        <div>
          <p className="section-label">{copy.eyebrow}</p>
          <h2 id="payment-action-title">{copy.title}</h2>
          <p className="subtle">{payment.paymentId} / {money(payment.amount)} / {readableStatus(payment.status)}</p>
        </div>
        <button type="button" className="quiet-button" disabled={processing} onClick={onClose}>Close</button>
      </div>
      <p className="notice warning">{copy.warning}</p>
      {error ? <div className="notice error" role="alert">{error}</div> : null}
      <form className="payment-action-form" onSubmit={onSubmit}>
        {copy.failureFields ? (
          <label htmlFor="payment-action-code">
            <span>Failure code</span>
            <input ref={firstFieldRef} id="payment-action-code" name="code" defaultValue="PAYMENT_DECLINED" required maxLength={64} pattern="[A-Za-z0-9_.-]+" />
          </label>
        ) : null}
        {copy.auditFields ? (
          <>
            <label htmlFor="payment-action-provider">
              <span>Provider</span>
              <input ref={firstFieldRef} id="payment-action-provider" name="provider" defaultValue={payment.provider || (payment.paymentMethod === 'COD' ? 'OFFLINE_COD' : '')} required maxLength={64} pattern="[A-Za-z0-9_.-]+" title="Use letters, numbers, dot, underscore, or hyphen" autoComplete="off" />
            </label>
            <label htmlFor="payment-action-reference">
              <span>{action.type === 'refund' ? 'Refund reference' : 'Provider reference'}</span>
              <input id="payment-action-reference" name="reference" required maxLength={128} pattern="[A-Za-z0-9_./:-]+" title="Use letters, numbers, underscore, dot, slash, colon, or hyphen" autoComplete="off" placeholder="Provider-issued immutable reference" />
            </label>
            <label htmlFor="payment-action-reconciliation">
              <span>{action.type === 'refund' ? 'Refund reconciliation reference' : 'Reconciliation reference'}</span>
              <input id="payment-action-reconciliation" name="reconciliationReference" required maxLength={128} pattern="[A-Za-z0-9_./:-]+" title="Use letters, numbers, underscore, dot, slash, colon, or hyphen" autoComplete="off" placeholder="Internal settlement or receipt reference" />
            </label>
          </>
        ) : copy.failureFields ? (
          <label htmlFor="payment-action-reference">
            <span>Provider reference (optional)</span>
            <input id="payment-action-reference" name="reference" maxLength={128} pattern="[A-Za-z0-9_./:-]*" title="Use letters, numbers, underscore, dot, slash, colon, or hyphen" autoComplete="off" defaultValue={referenceFor(payment)} />
          </label>
        ) : null}
        <label className="action-reason" htmlFor="payment-action-reason">
          <span>Operational reason</span>
          <textarea
            ref={!copy.failureFields && !copy.auditFields ? firstFieldRef : undefined}
            id="payment-action-reason"
            name="reason"
            required
            minLength={4}
            maxLength={255}
            rows={3}
            placeholder="Explain the verified business reason for this action"
          />
        </label>
        <fieldset className="confirmation-fieldset">
          <legend>Required confirmation</legend>
          <label className="confirmation-check" htmlFor="payment-action-confirmed">
            <input id="payment-action-confirmed" name="confirmed" type="checkbox" required />
            <span>I reviewed the amount, payment, references, and irreversible effect of this action.</span>
          </label>
        </fieldset>
        <div className="actions action-submit-row">
          <span className="helper">A stable idempotency key protects retries from duplicate mutations.</span>
          <button type="submit" className="primary" disabled={processing}>{processing ? 'Submitting...' : copy.submit}</button>
        </div>
      </form>
    </section>
  );
}

export function ReportFilterBar({ filters, error, onPreset, onChange, onApply }) {
  const presets = [['today', 'Today'], ['7d', '7 days'], ['30d', '30 days'], ['90d', '90 days'], ['all', 'All time'], ['custom', 'Custom']];
  function update(event) {
    onChange({ ...filters, preset: event.target.type === 'date' ? 'custom' : filters.preset, [event.target.name]: event.target.value });
  }
  return (
    <section className="report-filter-panel" aria-labelledby="report-filter-title">
      <div className="finance-section-head">
        <div><p className="section-label">Reporting period</p><h3 id="report-filter-title">Filters</h3></div>
        <span>UTC calendar dates</span>
      </div>
      <div className="preset-row" aria-label="Date range presets">
        {presets.map(([value, label]) => (
          <button key={value} type="button" className={filters.preset === value ? 'selected' : 'ghost'} aria-pressed={filters.preset === value} onClick={() => onPreset(value)}>{label}</button>
        ))}
      </div>
      <form className="report-filter-form" onSubmit={onApply}>
        <label htmlFor="report-from"><span>From (inclusive)</span><input id="report-from" name="from" type="date" value={filters.from} onChange={update} required={filters.preset !== 'all'} /></label>
        <label htmlFor="report-to"><span>To (inclusive)</span><input id="report-to" name="to" type="date" value={filters.to} onChange={update} required={filters.preset !== 'all'} /></label>
        <label htmlFor="report-status"><span>Payment status</span><select id="report-status" name="status" value={filters.status} onChange={update}><option value="all">All statuses</option>{PAYMENT_STATUSES.map((status) => <option key={status} value={status}>{readableStatus(status)}</option>)}</select></label>
        <label htmlFor="report-method"><span>Payment method</span><select id="report-method" name="method" value={filters.method} onChange={update}><option value="all">All methods</option>{PAYMENT_METHODS.map((method) => <option key={method} value={method}>{readableStatus(method)}</option>)}</select></label>
        <label htmlFor="report-carrier"><span>Carrier</span><select id="report-carrier" name="carrier" value={filters.carrier} onChange={update}><option value="all">All carriers</option><option value="FASTSHIP">FastShip</option><option value="QUICKEXPRESS">QuickExpress</option><option value="RELIABLE">Reliable Courier</option></select></label>
        <button type="submit" className="primary">Apply report filters</button>
      </form>
      {error ? <p className="field-error" role="alert">{error}</p> : null}
    </section>
  );
}

export function DailyTrend({ rows, loading = false, error = '', onRetry, rangeLabel = 'All available UTC dates' }) {
  const trend = normalizeDailyTrend(rows);
  const maximum = Math.max(1, ...trend.flatMap((row) => [row.grossCollected, row.refunds, Math.abs(row.netCollected)]));
  return (
    <figure id="daily-trends" className="trend-card" aria-labelledby="trend-title" aria-busy={loading}>
      <figcaption>
        <div>
          <p className="section-label">Cash-flow visualization</p>
          <h3 id="trend-title">Daily Trends</h3>
          <p className="trend-description">Gross collections, completed refunds, and net cash movement by transaction-event day.</p>
        </div>
        <div className="trend-context">
          <span className="trend-period">{rangeLabel}</span>
          <div className="trend-legend" aria-label="Chart legend"><span className="gross">Gross</span><span className="refunds">Refunds</span><span className="net">Net</span></div>
        </div>
      </figcaption>
      {loading && !trend.length ? (
        <div className="trend-state loading-state" role="status">
          <span className="loading-dot" aria-hidden="true" />
          <div><strong>Loading daily trends</strong><p>Calculating UTC cash movement for the selected reporting scope.</p></div>
        </div>
      ) : error && !trend.length ? (
        <div className="trend-state error-state" role="alert">
          <div><strong>Daily trends are unavailable</strong><p>{error}</p></div>
          {onRetry ? <button type="button" className="ghost" onClick={onRetry}>Retry report</button> : null}
        </div>
      ) : trend.length ? (
        <>
          {loading ? <p className="trend-refresh-note" role="status">Refreshing daily trends; the previous snapshot remains visible.</p> : null}
        <ol className="trend-list">
          {trend.map((row) => (
            <li key={row.date}>
              <time dateTime={row.date}>{dateLabel(row.date)}</time>
              <div className="trend-bars" aria-hidden="true">
                <span className="gross" style={{ width: `${(row.grossCollected / maximum) * 100}%` }} />
                <span className="refunds" style={{ width: `${(row.refunds / maximum) * 100}%` }} />
                <span className={row.netCollected < 0 ? 'net negative' : 'net'} style={{ width: `${(Math.abs(row.netCollected) / maximum) * 100}%` }} />
              </div>
              <div className="trend-values"><strong>{money(row.netCollected)} net</strong><small>{row.transactions} transactions / {money(row.grossCollected)} gross / {money(row.refunds)} refunded</small></div>
            </li>
          ))}
        </ol>
        </>
      ) : (
        <div className="trend-state empty-trend">
          <div><strong>No daily cash movement</strong><p>No collections or completed refunds match this reporting period. Try a wider date range or clear a payment filter.</p></div>
        </div>
      )}
    </figure>
  );
}

export function BreakdownPanel({ title, rows, empty = 'No matching data.', renderLabel, renderValue }) {
  return (
    <article className="breakdown-panel">
      <h3>{title}</h3>
      {rows?.length ? (
        <ul>
          {rows.map((row, index) => (
            <li key={row.label || row.carrier || index}>
              <span>{renderLabel(row)}</span>
              <strong>{renderValue(row)}</strong>
            </li>
          ))}
        </ul>
      ) : <p className="subtle">{empty}</p>}
    </article>
  );
}
