CREATE TABLE IF NOT EXISTS idempotency_locks (
  idempotency_key VARCHAR(128) PRIMARY KEY,
  created_at VARCHAR(40) NOT NULL
);
