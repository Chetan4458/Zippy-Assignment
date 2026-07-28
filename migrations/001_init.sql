CREATE TABLE IF NOT EXISTS orders (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  zippy_order_id TEXT NOT NULL UNIQUE,
  merchant_order_id TEXT NOT NULL UNIQUE,
  customer_name TEXT NOT NULL,
  customer_phone TEXT NOT NULL,
  customer_email TEXT NOT NULL,
  pickup_address_json TEXT NOT NULL,
  delivery_address_json TEXT NOT NULL,
  pickup_pincode TEXT NOT NULL,
  delivery_pincode TEXT NOT NULL,
  weight_grams INTEGER NOT NULL,
  length_cm REAL NOT NULL,
  width_cm REAL NOT NULL,
  height_cm REAL NOT NULL,
  payment_type TEXT NOT NULL,
  cod_amount REAL,
  order_status TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS shipping_quotes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  order_id INTEGER NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  carrier_code TEXT NOT NULL,
  carrier_name TEXT NOT NULL,
  service_code TEXT NOT NULL,
  service_name TEXT NOT NULL,
  base_charge REAL NOT NULL,
  cod_charge REAL NOT NULL,
  additional_charges REAL NOT NULL,
  tax REAL NOT NULL,
  total_charge REAL NOT NULL,
  estimated_min_days INTEGER NOT NULL,
  estimated_max_days INTEGER NOT NULL,
  raw_carrier_response TEXT NOT NULL,
  created_at TEXT NOT NULL,
  UNIQUE(order_id, carrier_code, service_code)
);

CREATE TABLE IF NOT EXISTS shipments (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  order_id INTEGER NOT NULL UNIQUE REFERENCES orders(id) ON DELETE CASCADE,
  carrier_code TEXT NOT NULL,
  carrier_shipment_id TEXT,
  tracking_number TEXT,
  selected_service_code TEXT NOT NULL,
  quoted_amount REAL NOT NULL,
  selected_quote_json TEXT NOT NULL,
  current_status TEXT NOT NULL,
  selection_timestamp TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS shipment_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  shipment_id INTEGER NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
  carrier_event_id TEXT NOT NULL,
  carrier_status TEXT NOT NULL,
  normalized_status TEXT NOT NULL,
  description TEXT NOT NULL,
  location TEXT,
  event_time TEXT NOT NULL,
  raw_event_payload TEXT NOT NULL,
  received_at TEXT NOT NULL,
  UNIQUE(shipment_id, carrier_event_id)
);

CREATE TABLE IF NOT EXISTS app_meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
