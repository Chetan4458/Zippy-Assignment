CREATE UNIQUE INDEX IF NOT EXISTS uq_shipments_carrier_shipment_id
  ON shipments(carrier_code, carrier_shipment_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_shipments_carrier_tracking_number
  ON shipments(carrier_code, tracking_number);

CREATE INDEX IF NOT EXISTS idx_shipment_events_shipment_time
  ON shipment_events(shipment_id, event_time, id);

CREATE INDEX IF NOT EXISTS idx_idempotency_keys_created_at
  ON idempotency_keys(created_at);
