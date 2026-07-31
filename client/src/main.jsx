import { useEffect, useRef, useState, useTransition } from 'react';
import { createRoot } from 'react-dom/client';
import './styles.css';

const sampleOrder = {
  merchantOrderId: 'MERCHANT-10001',
  customer: {
    name: 'Rahul Sharma',
    phone: '9876543210',
    email: 'rahul@example.com',
  },
  pickupAddress: {
    addressLine1: '15 MG Road',
    city: 'Bengaluru',
    state: 'Karnataka',
    pincode: '560001',
  },
  deliveryAddress: {
    addressLine1: '22 Connaught Place',
    city: 'New Delhi',
    state: 'Delhi',
    pincode: '110001',
  },
  package: {
    weightGrams: 1500,
    lengthCm: 20,
    widthCm: 15,
    heightCm: 10,
  },
  paymentType: 'COD',
  codAmount: 2500,
};

const formFields = [
  ['merchantOrderId', 'Merchant order number'],
  ['customerName', 'Customer name'],
  ['customerPhone', 'Customer phone'],
  ['customerEmail', 'Customer email'],
  ['pickupAddressLine1', 'Pickup address'],
  ['pickupCity', 'Pickup city'],
  ['pickupState', 'Pickup state'],
  ['pickupPincode', 'Pickup pincode'],
  ['deliveryAddressLine1', 'Delivery address'],
  ['deliveryCity', 'Delivery city'],
  ['deliveryState', 'Delivery state'],
  ['deliveryPincode', 'Delivery pincode'],
  ['weightGrams', 'Weight (grams)', 'number'],
  ['lengthCm', 'Length (cm)', 'number'],
  ['widthCm', 'Width (cm)', 'number'],
  ['heightCm', 'Height (cm)', 'number'],
];

const workflowSteps = ['Order created', 'Carrier chosen', 'Shipment created', 'In transit', 'Delivered'];

const moneyFormatter = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
});

function money(value) {
  return moneyFormatter.format(value || 0);
}

function estimateLabel(option) {
  return option.estimatedMinDays === option.estimatedMaxDays
    ? `${option.estimatedMinDays} days`
    : `${option.estimatedMinDays}-${option.estimatedMaxDays} days`;
}

async function api(path, options = {}) {
  let response;

  try {
    response = await fetch(path, {
      headers: {
        'content-type': 'application/json',
        ...(options.headers || {}),
      },
      ...options,
    });
  } catch {
    throw new Error('Backend is not reachable. Start Spring Boot on port 8080 or run `npm run dev`.');
  }

  const text = await response.text();
  let body = null;

  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = { message: text };
    }
  }

  if (!response.ok) {
    if ([502, 503, 504].includes(response.status)) {
      throw new Error('Backend is not reachable. Start Spring Boot on port 8080 or run `npm run dev`.');
    }
    throw new Error(body?.message || `Request failed with ${response.status}`);
  }
  return body;
}

function formatDateTime(value) {
  if (!value) {
    return 'Just now';
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return date.toLocaleString('en-IN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  });
}

function shellValue(source, key) {
  return source[key] ?? '';
}

function statusTone(status) {
  if (status === 'DELIVERED') return 'success';
  if (status === 'DELIVERY_FAILED' || status === 'RTO') return 'danger';
  return 'neutral';
}

function currentStatusFor(order) {
  return order?.selectedShipment?.current_status || order?.order_status || 'ORDER_CREATED';
}

function workflowStage(order) {
  const status = currentStatusFor(order);
  if (status === 'DELIVERED') return 4;
  if (status === 'OUT_FOR_DELIVERY') return 3;
  if (status === 'IN_TRANSIT') return 2;
  if (status === 'PICKED_UP') return 1;
  if (status === 'SHIPMENT_CREATED' || status === 'CARRIER_SELECTED') return 1;
  return 0;
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
  return <span className={`status-pill ${statusTone(status)}`}>{status.replaceAll('_', ' ')}</span>;
}

function historyActionLabel(item) {
  const status = item.selectedShipment?.currentStatus || item.orderStatus || 'ORDER_CREATED';
  if (status === 'ORDER_CREATED' || status === 'CARRIER_SELECTED') {
    return item.selectedShipment ? 'Update carrier' : 'Select carrier';
  }
  return 'View details';
}

function historyActionVariant(item) {
  const status = item.selectedShipment?.currentStatus || item.orderStatus || 'ORDER_CREATED';
  return status === 'ORDER_CREATED' || status === 'CARRIER_SELECTED' ? 'primary' : 'ghost';
}

function SectionHeader({ eyebrow, title, subtitle, action }) {
  return (
    <div className="card-head">
      <div>
        <p className="section-label">{eyebrow}</p>
        <h2>{title}</h2>
        {subtitle ? <p className="subtle">{subtitle}</p> : null}
      </div>
      {action}
    </div>
  );
}

function WorkflowRail({ step }) {
  return (
    <div className="workflow-card">
      <div className="workflow-header">
        <span>Journey</span>
        <small>Live workflow snapshot</small>
      </div>
      <ol className="workflow">
        {workflowSteps.map((item, index) => (
          <li key={item} className={index <= step ? 'complete' : ''}>
            <span className="step-dot" />
            <div>
              <strong>{item}</strong>
              {index === step ? <small>Current step</small> : null}
            </div>
          </li>
        ))}
      </ol>
    </div>
  );
}

function OrderField({ name, label, type = 'text', defaultValue }) {
  return (
    <label>
      {label}
      <input name={name} type={type} defaultValue={defaultValue} />
    </label>
  );
}

function App() {
  const [screen, setScreen] = useState('create');
  const [isPending, startTransition] = useTransition();
  const [statusMessage, setStatusMessage] = useState('');
  const [errorMessage, setErrorMessage] = useState('');
  const [sortBy, setSortBy] = useState('lowest');
  const [order, setOrder] = useState(null);
  const [rates, setRates] = useState([]);
  const [selectedCarrier, setSelectedCarrier] = useState(null);
  const [tracking, setTracking] = useState(null);
  const [creatingShipment, setCreatingShipment] = useState(false);
  const [systemOverview, setSystemOverview] = useState(null);
  const [orderHistory, setOrderHistory] = useState([]);
  const createFormRef = useRef(null);

  useEffect(() => {
    let cancelled = false;
    let timer = null;

    async function refreshTracking() {
      if (!order?.zippy_order_id || screen !== 'details') {
        return;
      }
      try {
        const payload = await api(`/api/orders/${order.zippy_order_id}/tracking`);
        if (!cancelled) {
          setTracking(payload);
        }
      } catch {
        // Best effort polling.
      }
    }

    refreshTracking();
    timer = setInterval(refreshTracking, 4000);

    return () => {
      cancelled = true;
      if (timer) {
        clearInterval(timer);
      }
    };
  }, [order?.zippy_order_id, screen]);

  useEffect(() => {
    let cancelled = false;

    async function refreshSystemOverview() {
      try {
        const payload = await api('/api/system/overview');
        if (!cancelled) {
          setSystemOverview(payload);
        }
      } catch {
        // Best effort observability panel.
      }
    }

    refreshSystemOverview();
    const interval = setInterval(refreshSystemOverview, 10000);

    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, []);

  useEffect(() => {
    let cancelled = false;

    async function refreshOrderHistory() {
      try {
        const payload = await api('/api/orders/history?limit=6');
        if (!cancelled) {
          setOrderHistory(payload.orders || []);
        }
      } catch {
        // Best effort history panel.
      }
    }

    refreshOrderHistory();
    const interval = setInterval(refreshOrderHistory, 12000);

    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, []);

  useEffect(() => {
    let cancelled = false;

    async function refreshRatesView() {
      if (!order?.zippy_order_id || screen !== 'rates') {
        return;
      }
      try {
        const payload = await api(`/api/orders/${order.zippy_order_id}`);
        if (cancelled) {
          return;
        }
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
      } catch {
        // Best effort refresh.
      }
    }

    refreshRatesView();
    return () => {
      cancelled = true;
    };
  }, [order?.zippy_order_id, screen]);

  async function loadRates(orderId, nextSortBy = sortBy) {
    const payload = await api(`/api/orders/${orderId}/rates?sortBy=${encodeURIComponent(nextSortBy)}`);
    setRates(payload.shippingOptions || []);
  }

  async function handleCreateOrder(event) {
    event.preventDefault();
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
        paymentType: form.get('paymentType'),
        codAmount: Number(form.get('codAmount')),
      };

      const payloadBody = await api('/api/orders', {
        method: 'POST',
        body: JSON.stringify(payload),
      });

      setOrder(payloadBody);
      setRates(payloadBody.shippingOptions || []);
      setSelectedCarrier(null);
      setTracking(null);
      setStatusMessage(`Created ${payloadBody.zippy_order_id}`);
      startTransition(() => setScreen('rates'));
    } catch (error) {
      setErrorMessage(error.message);
    }
  }

  async function handleSort(nextSortBy) {
    setSortBy(nextSortBy);
    if (!order?.zippy_order_id) {
      return;
    }
    try {
      await loadRates(order.zippy_order_id, nextSortBy);
    } catch (error) {
      setErrorMessage(error.message);
    }
  }

  async function handleSelectCarrier(option) {
    if (!order?.zippy_order_id) {
      return;
    }
    try {
      const payload = await api(`/api/orders/${order.zippy_order_id}/select-carrier`, {
        method: 'POST',
        body: JSON.stringify({
          carrierCode: option.carrierCode,
          serviceCode: option.serviceCode,
          quotedAmount: option.totalCharge,
        }),
      });
      if (payload?.selectedShipment) {
        setOrder((current) =>
          current
            ? {
                ...current,
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
      setErrorMessage(error.message);
    }
  }

  async function handleCreateShipment() {
    if (!order?.zippy_order_id) {
      return;
    }
    setCreatingShipment(true);
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
    try {
      await api(`/api/mock-carriers/${order.zippy_order_id}/advance`, { method: 'POST' });
      const payload = await api(`/api/orders/${order.zippy_order_id}/tracking`);
      setTracking(payload);
    } catch (error) {
      setErrorMessage(error.message);
    }
  }

  async function handleRefreshTracking() {
    if (!order?.zippy_order_id) {
      return;
    }
    try {
      const payload = await api(`/api/orders/${order.zippy_order_id}/tracking`);
      setTracking(payload);
    } catch (error) {
      setErrorMessage(error.message);
    }
  }

  async function handleOpenHistoryOrder(item) {
    try {
      const payload = await api(`/api/orders/${item.zippyOrderId}`);
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
      setStatusMessage(`Loaded ${payload.zippy_order_id} from history`);
      setErrorMessage('');

      const status = currentStatusFor(payload);
      startTransition(() => setScreen(status === 'ORDER_CREATED' || status === 'CARRIER_SELECTED' ? 'rates' : 'details'));
    } catch (error) {
      setErrorMessage(error.message);
    }
  }

  function handleStartNewOrder() {
    setOrder(null);
    setTracking(null);
    setRates([]);
    setSelectedCarrier(null);
    setCreatingShipment(false);
    setStatusMessage('Ready to create a new order');
    setErrorMessage('');
    startTransition(() => setScreen('create'));
  }

  function useSampleData() {
    const form = createFormRef.current;
    if (!form) {
      return;
    }

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
      paymentType: sampleOrder.paymentType,
      codAmount: sampleOrder.codAmount,
    }).forEach(([key, value]) => {
      form.elements.namedItem(key).value = String(value);
    });
  }

  const history = tracking?.shipmentEvents || [];
  const liveOrder = tracking || order;
  const currentStage = workflowStage(liveOrder);
  const currentStatus = currentStatusFor(liveOrder);
  const metrics = [
    { label: 'Quotes', value: String(rates.length || order?.shippingOptions?.length || 0), tone: 'accent' },
    { label: 'Current status', value: currentStatus.replaceAll('_', ' '), tone: statusTone(currentStatus) },
    { label: 'Events', value: String(history.length), tone: 'neutral' },
  ];

  return (
    <main className="shell">
      <aside className="hero">
        <div>
          <div className="brand-row">
            <div className="brand-mark">Z</div>
            <div>
              <p className="eyebrow">Zippy Logistics</p>
              <h1>Operations console for merchant shipping.</h1>
            </div>
          </div>

          <div style={{ height: '1.25rem' }} />

          <p className="hero-copy">
            Create orders, compare carrier quotes, and monitor normalized shipment events in a workspace designed to feel
            ready for clients, not just demos.
          </p>

          <div style={{ height: '1.25rem' }} />

          <div className="stat-grid">
            {metrics.map((metric) => (
              <MetricCard key={metric.label} {...metric} />
            ))}
          </div>

          <div style={{ height: '1rem' }} />

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
                  <strong>{systemOverview.carrierTimeoutMs ?? 0}ms</strong>
                  <span>Carrier timeout</span>
                </div>
              </div>

              <div className="system-foot">
                <span>{systemOverview.supportedCarriers?.join(' | ')}</span>
              </div>
            </section>
          ) : null}

          <div style={{ height: '1rem' }} />

          <WorkflowRail step={currentStage} />
        </div>

        <p className="hero-footnote">
          {isPending ? 'Updating view...' : 'Use npm run dev for instant UI updates without restarting.'}
        </p>
      </aside>

      <section className="panel">
        <header className="topbar">
          <div className="segmented">
            <button
              type="button"
              className={screen === 'create' ? 'active' : ''}
              onClick={handleStartNewOrder}
            >
              Create Order
            </button>
            <button
              type="button"
              className={screen === 'rates' ? 'active' : ''}
              onClick={() => setScreen('rates')}
              disabled={!order}
            >
              Courier Selection
            </button>
            <button
              type="button"
              className={screen === 'details' ? 'active' : ''}
              onClick={() => setScreen('details')}
              disabled={!order}
            >
              Order Details
            </button>
          </div>
        </header>

        {statusMessage ? <div className="notice success">{statusMessage}</div> : null}
        {errorMessage ? <div className="notice error">{errorMessage}</div> : null}

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
                      onClick={() => handleOpenHistoryOrder(item)}
                    >
                      {historyActionLabel(item)}
                    </button>
                  </div>
                </article>
              ))}
            </div>
          ) : (
            <div className="empty">No orders created yet.</div>
          )}
        </section>

        {screen === 'create' ? (
          <section className="card">
            <SectionHeader
              eyebrow="Step 1"
              title="Create Order"
              subtitle="Enter merchant, customer, and package details to generate live rate options."
              action={
                <button type="button" className="ghost" onClick={useSampleData}>
                  Load sample data
                </button>
              }
            />

            <form ref={createFormRef} className="grid" onSubmit={handleCreateOrder}>
              {formFields.slice(0, 12).map(([name, label]) => (
                <OrderField
                  key={name}
                  name={name}
                  label={label}
                  defaultValue={shellValue(
                    {
                      merchantOrderId: sampleOrder.merchantOrderId,
                      customerName: sampleOrder.customer.name,
                      customerPhone: sampleOrder.customer.phone,
                      customerEmail: sampleOrder.customer.email,
                      pickupAddressLine1: sampleOrder.pickupAddress.addressLine1,
                      pickupCity: sampleOrder.pickupAddress.city,
                      pickupState: sampleOrder.pickupAddress.state,
                      pickupPincode: sampleOrder.pickupAddress.pincode,
                      deliveryAddressLine1: sampleOrder.deliveryAddress.addressLine1,
                      deliveryCity: sampleOrder.deliveryAddress.city,
                      deliveryState: sampleOrder.deliveryAddress.state,
                      deliveryPincode: sampleOrder.deliveryAddress.pincode,
                    },
                    name,
                  )}
                />
              ))}

              {formFields.slice(12).map(([name, label, type]) => (
                <OrderField
                  key={name}
                  name={name}
                  label={label}
                  type={type}
                  defaultValue={shellValue(
                    {
                      weightGrams: sampleOrder.package.weightGrams,
                      lengthCm: sampleOrder.package.lengthCm,
                      widthCm: sampleOrder.package.widthCm,
                      heightCm: sampleOrder.package.heightCm,
                    },
                    name,
                  )}
                />
              ))}

              <label>
                Payment type
                <select name="paymentType" defaultValue="COD">
                  <option value="COD">COD</option>
                  <option value="PREPAID">Prepaid</option>
                </select>
              </label>
              <label>
                COD amount
                <input name="codAmount" type="number" defaultValue={sampleOrder.codAmount} />
              </label>

              <div className="actions">
                <span className="helper">The backend will fetch all rates immediately after order creation.</span>
                <button type="submit" className="primary">
                  Create Order
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
              subtitle={`Order ID: ${order?.zippy_order_id || '-'}`}
              action={
                <label className="inline">
                  Sort by
                  <select value={sortBy} onChange={(event) => handleSort(event.target.value)}>
                    <option value="lowest">Lowest price</option>
                    <option value="fastest">Fastest delivery</option>
                    <option value="carrier">Carrier name</option>
                  </select>
                </label>
              }
            />

            {rates.length ? (
              <div className="rates-grid">
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
                            {rate.serviceName} · {rate.serviceCode}
                          </p>
                        </div>
                        <button
                          type="button"
                          className={isSelected ? 'selected' : 'primary'}
                          onClick={() => handleSelectCarrier(rate)}
                        >
                          {isSelected ? 'Selected' : 'Select carrier'}
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
                </div>
                <button type="button" className="primary" disabled={creatingShipment} onClick={handleCreateShipment}>
                  {creatingShipment ? 'Creating...' : 'Create Shipment'}
                </button>
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
              subtitle="Shipment metadata, carrier selection, and webhook-driven history."
              action={<StatusPill status={currentStatus} />}
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
                </div>

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
                  <button type="button" onClick={handleRefreshTracking}>
                    Refresh
                  </button>
                  <button type="button" onClick={handleAdvanceStatus}>
                    Trigger Next Status
                  </button>
                </div>
              </>
            ) : (
              <div className="empty">Create a shipment to see tracking details.</div>
            )}
          </section>
        ) : null}
      </section>
    </main>
  );
}

createRoot(document.getElementById('root')).render(<App />);
