ALTER TABLE payments ADD COLUMN IF NOT EXISTS refund_reconciliation_reference VARCHAR(128);

UPDATE payments
SET captured_at = CASE
  WHEN status = 'SUCCEEDED' THEN updated_at
  ELSE created_at
END
WHERE payment_method = 'PREPAID'
  AND status IN ('SUCCEEDED', 'REFUND_PENDING', 'REFUNDED')
  AND captured_at IS NULL;

UPDATE payments
SET collected_at = updated_at
WHERE payment_method = 'COD'
  AND status = 'SUCCEEDED'
  AND collected_at IS NULL;

UPDATE payments
SET refunded_at = updated_at,
    refund_reference = COALESCE(refund_reference, CONCAT('MIGRATED-REFUND-', payment_id))
WHERE status = 'REFUNDED'
  AND refunded_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_payments_refund_reconciliation_reference
  ON payments(refund_reconciliation_reference);

ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS actor VARCHAR(128);
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS request_hash VARCHAR(64);
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS response_json CLOB;
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS legacy_idempotency_key VARCHAR(128);

UPDATE payment_transactions
SET actor = CASE
  WHEN event_type = 'STATE_SNAPSHOT' THEN 'MIGRATION'
  WHEN event_type IN ('COD_AWAITING_COLLECTION', 'AUTO_CANCELLED', 'AUTO_VOIDED', 'REFUND_PENDING')
    THEN 'ZIPPY_SYSTEM'
  ELSE 'LEGACY_UNKNOWN'
END
WHERE actor IS NULL;

ALTER TABLE payment_transactions ALTER COLUMN actor SET NOT NULL;

-- V8 scoped idempotency keys to one payment and did not persist request/response
-- replay data. Preserve those keys for audit, but keep them out of the new global
-- operation-key namespace so a duplicate legacy key cannot replay the wrong payment.
UPDATE payment_transactions
SET legacy_idempotency_key = idempotency_key,
    idempotency_key = NULL
WHERE idempotency_key IS NOT NULL;

ALTER TABLE payment_transactions DROP CONSTRAINT uq_payment_transaction_idempotency;
ALTER TABLE payment_transactions ADD CONSTRAINT uq_payment_transaction_idempotency
  UNIQUE (idempotency_key);

ALTER TABLE payment_transactions DROP CONSTRAINT fk_payment_transaction_payment;
ALTER TABLE payments ADD CONSTRAINT uq_payments_id_order UNIQUE (id, order_id);
ALTER TABLE payment_transactions ADD CONSTRAINT fk_payment_transaction_payment
  FOREIGN KEY (payment_id, order_id) REFERENCES payments(id, order_id);
ALTER TABLE payment_transactions ADD CONSTRAINT fk_payment_transaction_order
  FOREIGN KEY (order_id) REFERENCES orders(id);

ALTER TABLE payment_transactions ADD CONSTRAINT chk_payment_transaction_currency
  CHECK (currency = 'INR');
ALTER TABLE payment_transactions ADD CONSTRAINT chk_payment_transaction_resulting_status
  CHECK (resulting_status IN (
    'PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'AWAITING_COLLECTION',
    'VOIDED', 'REFUND_PENDING', 'REFUNDED'
  ));
ALTER TABLE payment_transactions ADD CONSTRAINT chk_payment_transaction_previous_status
  CHECK (
    previous_status IS NULL OR previous_status IN (
      'PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'AWAITING_COLLECTION',
      'VOIDED', 'REFUND_PENDING', 'REFUNDED'
    )
  );

INSERT INTO payment_transactions (
  transaction_id, payment_id, order_id, zippy_order_id, event_type,
  previous_status, resulting_status, amount, currency, provider,
  provider_reference, reconciliation_reference, reason, actor,
  idempotency_key, request_hash, response_json, created_at
)
SELECT
  CONCAT('PTX-MIGRATED-CAPTURE-', p.id), p.id, p.order_id, p.zippy_order_id, 'CAPTURED',
  'PENDING', 'SUCCEEDED', p.amount, p.currency, p.provider,
  p.provider_reference, p.reconciliation_reference,
  'Legacy prepaid capture inferred during payment ledger migration', 'MIGRATION',
  NULL, NULL, NULL, p.captured_at
FROM payments p
WHERE p.payment_method = 'PREPAID'
  AND p.status IN ('SUCCEEDED', 'REFUND_PENDING', 'REFUNDED')
  AND NOT EXISTS (
    SELECT 1 FROM payment_transactions t
    WHERE t.payment_id = p.id AND t.event_type = 'CAPTURED'
  );

INSERT INTO payment_transactions (
  transaction_id, payment_id, order_id, zippy_order_id, event_type,
  previous_status, resulting_status, amount, currency, provider,
  provider_reference, reconciliation_reference, reason, actor,
  idempotency_key, request_hash, response_json, created_at
)
SELECT
  CONCAT('PTX-MIGRATED-COLLECT-', p.id), p.id, p.order_id, p.zippy_order_id, 'COD_COLLECTED',
  'AWAITING_COLLECTION', 'SUCCEEDED', p.amount, p.currency, p.provider,
  p.provider_reference, p.reconciliation_reference,
  'Legacy COD collection inferred during payment ledger migration', 'MIGRATION',
  NULL, NULL, NULL, p.collected_at
FROM payments p
WHERE p.payment_method = 'COD'
  AND p.status = 'SUCCEEDED'
  AND NOT EXISTS (
    SELECT 1 FROM payment_transactions t
    WHERE t.payment_id = p.id AND t.event_type = 'COD_COLLECTED'
  );

INSERT INTO payment_transactions (
  transaction_id, payment_id, order_id, zippy_order_id, event_type,
  previous_status, resulting_status, amount, currency, provider,
  provider_reference, reconciliation_reference, reason, actor,
  idempotency_key, request_hash, response_json, created_at
)
SELECT
  CONCAT('PTX-MIGRATED-REFUND-', p.id), p.id, p.order_id, p.zippy_order_id, 'REFUNDED',
  'SUCCEEDED', 'REFUNDED',
  CASE WHEN p.refunded_amount > 0 THEN p.refunded_amount ELSE p.amount END,
  p.currency, p.provider, p.refund_reference, p.refund_reconciliation_reference,
  'Legacy full refund inferred during payment ledger migration', 'MIGRATION',
  NULL, NULL, NULL, p.refunded_at
FROM payments p
WHERE p.status = 'REFUNDED'
  AND NOT EXISTS (
    SELECT 1 FROM payment_transactions t
    WHERE t.payment_id = p.id AND t.event_type = 'REFUNDED'
  );
