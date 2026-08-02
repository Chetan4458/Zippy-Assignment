ALTER TABLE payments ADD COLUMN IF NOT EXISTS payment_method VARCHAR(24) NOT NULL DEFAULT 'PREPAID';
ALTER TABLE payments ADD COLUMN IF NOT EXISTS collection_stage VARCHAR(24) NOT NULL DEFAULT 'CHECKOUT';

CREATE INDEX IF NOT EXISTS idx_payments_method_status ON payments(payment_method, status);

INSERT INTO payments (
  payment_id, order_id, zippy_order_id, amount, currency, status,
  payment_method, collection_stage, created_at, updated_at
)
SELECT
  CONCAT('COD-', o.zippy_order_id), o.id, o.zippy_order_id, o.cod_amount, 'INR',
  'AWAITING_COLLECTION', 'COD', 'DELIVERY', o.created_at, o.updated_at
FROM orders o
WHERE o.payment_type = 'COD'
  AND NOT EXISTS (
    SELECT 1 FROM payments p WHERE p.order_id = o.id AND p.payment_method = 'COD'
  );
