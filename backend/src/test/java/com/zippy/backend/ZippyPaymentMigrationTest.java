package com.zippy.backend;

import java.math.BigDecimal;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZippyPaymentMigrationTest {

  @Test
  void reconstructsLegacyCashEventsWithoutDoubleCounting() {
    String databaseName = "payment_migration_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + databaseName + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
        "sa",
        ""
    );
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .target("7")
        .load()
        .migrate();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update("""
        INSERT INTO orders (
          zippy_order_id, merchant_order_id, customer_name, customer_phone, customer_email,
          pickup_address_json, delivery_address_json, pickup_pincode, delivery_pincode,
          weight_grams, length_cm, width_cm, height_cm, payment_type, cod_amount,
          order_status, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        "ZPY-LEGACY-1", "LEGACY-1", "Legacy Finance", "9876543210", "legacy@example.com",
        "{}", "{}", "400001", "110001", 1000, new BigDecimal("20.00"),
        new BigDecimal("15.00"), new BigDecimal("10.00"), "PREPAID", null,
        "ORDER_CREATED", "2026-07-01T00:00:00Z", "2026-08-01T00:00:00Z");
    Long orderId = jdbc.queryForObject(
        "SELECT id FROM orders WHERE zippy_order_id = 'ZPY-LEGACY-1'", Long.class);

    insertLegacyPayment(
        jdbc, orderId, "PAY-LEGACY-CAPTURED", new BigDecimal("100.00"), "SUCCEEDED",
        "PREPAID", BigDecimal.ZERO, "2026-07-01T10:00:00Z", "2026-07-02T10:00:00Z");
    insertLegacyPayment(
        jdbc, orderId, "PAY-LEGACY-REFUNDED", new BigDecimal("200.00"), "REFUNDED",
        "PREPAID", new BigDecimal("200.00"), "2026-07-03T10:00:00Z", "2026-08-01T10:00:00Z");
    insertLegacyPayment(
        jdbc, orderId, "COD-LEGACY-COLLECTED", new BigDecimal("300.00"), "SUCCEEDED",
        "COD", BigDecimal.ZERO, "2026-07-05T10:00:00Z", "2026-07-06T10:00:00Z");

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate();

    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM payment_transactions WHERE event_type = 'CAPTURED'", Long.class))
        .isEqualTo(2L);
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM payment_transactions WHERE event_type = 'COD_COLLECTED'", Long.class))
        .isEqualTo(1L);
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM payment_transactions WHERE event_type = 'REFUNDED'", Long.class))
        .isEqualTo(1L);
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM payment_transactions WHERE event_type = 'STATE_SNAPSHOT'", Long.class))
        .isEqualTo(3L);

    BigDecimal gross = jdbc.queryForObject("""
        SELECT COALESCE(SUM(amount), 0) FROM payment_transactions
        WHERE event_type IN ('CAPTURED', 'COD_COLLECTED')
        """, BigDecimal.class);
    BigDecimal refunds = jdbc.queryForObject(
        "SELECT COALESCE(SUM(amount), 0) FROM payment_transactions WHERE event_type = 'REFUNDED'",
        BigDecimal.class);
    assertThat(gross).isEqualByComparingTo("600.00");
    assertThat(refunds).isEqualByComparingTo("200.00");
    assertThat(gross.subtract(refunds)).isEqualByComparingTo("400.00");
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM payment_transactions WHERE actor = 'MIGRATION'", Long.class))
        .isEqualTo(7L);
    assertThat(jdbc.queryForObject("""
        SELECT created_at FROM payment_transactions
        WHERE event_type = 'REFUNDED' AND zippy_order_id = 'ZPY-LEGACY-1'
        """, String.class)).isEqualTo("2026-08-01T10:00:00Z");
  }

  @Test
  void upgradesAnAppliedHistoricalV8WithoutRepairingHistoryOrLosingLegacyKeys() {
    String databaseName = "payment_v8_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + databaseName + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
        "sa",
        ""
    );
    Flyway v8 = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .target("8")
        .load();
    v8.migrate();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertThat(jdbc.queryForObject(
        "SELECT checksum FROM flyway_schema_history WHERE version = '8'", Integer.class))
        .isEqualTo(-115305915);
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = 'payment_transactions'
          AND COLUMN_NAME IN ('actor', 'request_hash', 'response_json')
        """, Long.class)).isZero();

    jdbc.update("""
        INSERT INTO orders (
          zippy_order_id, merchant_order_id, customer_name, customer_phone, customer_email,
          pickup_address_json, delivery_address_json, pickup_pincode, delivery_pincode,
          weight_grams, length_cm, width_cm, height_cm, payment_type, cod_amount,
          order_status, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        "ZPY-V8-UPGRADE", "V8-UPGRADE", "V8 Upgrade", "9876543210", "v8@example.com",
        "{}", "{}", "400001", "110001", 1000, new BigDecimal("20.00"),
        new BigDecimal("15.00"), new BigDecimal("10.00"), "PREPAID", null,
        "CARRIER_SELECTED", "2026-08-01T00:00:00Z", "2026-08-01T00:00:00Z");
    Long orderId = jdbc.queryForObject(
        "SELECT id FROM orders WHERE zippy_order_id = 'ZPY-V8-UPGRADE'", Long.class);
    insertV8Payment(jdbc, orderId, "PAY-V8-A", new BigDecimal("10.00"));
    insertV8Payment(jdbc, orderId, "PAY-V8-B", new BigDecimal("20.00"));
    insertV8Payment(jdbc, orderId, "PAY-V8-C", new BigDecimal("30.00"));
    jdbc.update(
        "UPDATE payments SET status = 'SUCCEEDED' WHERE payment_id = 'PAY-V8-C'");
    insertV8Transaction(jdbc, "PTX-V8-A", "PAY-V8-A", "legacy-shared-key");
    insertV8Transaction(jdbc, "PTX-V8-B", "PAY-V8-B", "legacy-shared-key");
    insertV8CapturedTransaction(jdbc, "PTX-V8-C-CAPTURE", "PAY-V8-C");

    Flyway latest = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load();
    latest.migrate();
    latest.validate();

    assertThat(jdbc.queryForObject(
        "SELECT checksum FROM flyway_schema_history WHERE version = '8'", Integer.class))
        .isEqualTo(-115305915);
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM payment_transactions WHERE transaction_id IN ('PTX-V8-A', 'PTX-V8-B')",
        Long.class)).isEqualTo(2L);
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM payment_transactions
        WHERE payment_id = (SELECT id FROM payments WHERE payment_id = 'PAY-V8-C')
          AND event_type = 'CAPTURED'
        """, Long.class)).isEqualTo(1L);
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM payment_transactions
        WHERE legacy_idempotency_key = 'legacy-shared-key' AND idempotency_key IS NULL
        """, Long.class)).isEqualTo(2L);
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM payment_transactions
        WHERE transaction_id IN ('PTX-V8-A', 'PTX-V8-B') AND actor = 'LEGACY_UNKNOWN'
        """, Long.class)).isEqualTo(2L);
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = 'payment_transactions'
          AND COLUMN_NAME IN ('actor', 'request_hash', 'response_json', 'legacy_idempotency_key')
        """, Long.class)).isEqualTo(4L);

    jdbc.update(
        "UPDATE payment_transactions SET idempotency_key = 'new-global-key' WHERE transaction_id = 'PTX-V8-A'");
    assertThatThrownBy(() -> jdbc.update(
        "UPDATE payment_transactions SET idempotency_key = 'new-global-key' WHERE transaction_id = 'PTX-V8-B'"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private void insertLegacyPayment(
      JdbcTemplate jdbc,
      long orderId,
      String paymentId,
      BigDecimal amount,
      String status,
      String method,
      BigDecimal refundedAmount,
      String createdAt,
      String updatedAt
  ) {
    jdbc.update("""
        INSERT INTO payments (
          payment_id, order_id, zippy_order_id, amount, currency, status,
          payment_method, collection_stage, refunded_amount, created_at, updated_at
        ) VALUES (?, ?, ?, ?, 'INR', ?, ?, ?, ?, ?, ?)
        """,
        paymentId, orderId, "ZPY-LEGACY-1", amount, status, method,
        "COD".equals(method) ? "DELIVERY" : "CHECKOUT", refundedAmount, createdAt, updatedAt);
  }

  private void insertV8Payment(JdbcTemplate jdbc, long orderId, String paymentId, BigDecimal amount) {
    jdbc.update("""
        INSERT INTO payments (
          payment_id, order_id, zippy_order_id, amount, currency, status,
          payment_method, collection_stage, refunded_amount, provider,
          provider_reference, created_at, updated_at
        ) VALUES (?, ?, 'ZPY-V8-UPGRADE', ?, 'INR', 'PENDING',
                  'PREPAID', 'CHECKOUT', 0, 'ZIPPY_CHECKOUT', ?, ?, ?)
        """,
        paymentId, orderId, amount, paymentId,
        "2026-08-01T00:00:00Z", "2026-08-01T00:00:00Z");
  }

  private void insertV8Transaction(
      JdbcTemplate jdbc,
      String transactionId,
      String paymentId,
      String idempotencyKey
  ) {
    jdbc.update("""
        INSERT INTO payment_transactions (
          transaction_id, payment_id, order_id, zippy_order_id, event_type,
          previous_status, resulting_status, amount, currency, provider,
          provider_reference, reconciliation_reference, reason, idempotency_key, created_at
        )
        SELECT ?, p.id, p.order_id, p.zippy_order_id, 'INTENT_CREATED',
               NULL, 'PENDING', p.amount, p.currency, p.provider,
               p.provider_reference, NULL, 'Legacy keyed operation', ?, p.created_at
        FROM payments p WHERE p.payment_id = ?
        """, transactionId, idempotencyKey, paymentId);
  }

  private void insertV8CapturedTransaction(
      JdbcTemplate jdbc,
      String transactionId,
      String paymentId
  ) {
    jdbc.update("""
        INSERT INTO payment_transactions (
          transaction_id, payment_id, order_id, zippy_order_id, event_type,
          previous_status, resulting_status, amount, currency, provider,
          provider_reference, reconciliation_reference, reason, idempotency_key, created_at
        )
        SELECT ?, p.id, p.order_id, p.zippy_order_id, 'CAPTURED',
               'PENDING', 'SUCCEEDED', p.amount, p.currency, p.provider,
               p.provider_reference, NULL, 'Legacy capture already recorded', NULL, p.updated_at
        FROM payments p WHERE p.payment_id = ?
        """, transactionId, paymentId);
  }
}
