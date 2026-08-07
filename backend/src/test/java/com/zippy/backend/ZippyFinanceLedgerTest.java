package com.zippy.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "zippy.automation-enabled=false")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ZippyFinanceLedgerTest {
  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void resetDatabase() {
    jdbcTemplate.execute("DELETE FROM payment_transactions");
    jdbcTemplate.execute("DELETE FROM shipment_events");
    jdbcTemplate.execute("DELETE FROM shipments");
    jdbcTemplate.execute("DELETE FROM shipping_quotes");
    jdbcTemplate.execute("DELETE FROM payments");
    jdbcTemplate.execute("DELETE FROM orders");
    jdbcTemplate.execute("DELETE FROM idempotency_keys");
    jdbcTemplate.execute("DELETE FROM idempotency_locks");
    jdbcTemplate.execute("MERGE INTO app_meta (meta_key, meta_value) KEY(meta_key) VALUES ('order_sequence', '10000')");
  }

  @Test
  void appendsOrderedLedgerEntriesAndDeduplicatesTheSameTransition() throws Exception {
    OrderSetup setup = createOrderAndSelect("LEDGER-CAPTURE", "PREPAID", null, "FASTSHIP");
    JsonNode payment = createPrepaidPayment(setup, "intent-ledger-001");
    String paymentId = payment.path("paymentId").asText();
    Map<String, Object> capture = action(
        "Captured after provider callback",
        "PAYMENT_GATEWAY",
        "capture-ledger-001",
        "settlement-ledger-001"
    );

    for (int attempt = 0; attempt < 2; attempt++) {
      mockMvc.perform(post("/api/payments/{paymentId}/confirm", paymentId)
              .header("Idempotency-Key", "capture-operation-001")
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(capture)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("SUCCEEDED"))
          .andExpect(jsonPath("$.providerReference").value("capture-ledger-001"))
          .andExpect(jsonPath("$.capturedAt").isNotEmpty());
    }

    mockMvc.perform(get("/api/payments/{paymentId}/transactions", paymentId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalTransactions").value(2))
        .andExpect(jsonPath("$.transactions[0].paymentId").value(paymentId))
        .andExpect(jsonPath("$.transactions[0].eventType").value("INTENT_CREATED"))
        .andExpect(jsonPath("$.transactions[1].eventType").value("CAPTURED"))
        .andExpect(jsonPath("$.transactions[1].previousStatus").value("PENDING"))
        .andExpect(jsonPath("$.transactions[1].resultingStatus").value("SUCCEEDED"))
        .andExpect(jsonPath("$.transactions[1].actor").value("LOCAL_OPERATOR"))
        .andExpect(jsonPath("$.transactions[1].providerReference").value("capture-ledger-001"));

    mockMvc.perform(get("/api/orders/{orderId}/payment-transactions", setup.orderId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentId").doesNotExist())
        .andExpect(jsonPath("$.orderId").value(setup.orderId()))
        .andExpect(jsonPath("$.totalTransactions").value(2))
        .andExpect(jsonPath("$.transactions[1].paymentId").value(paymentId));

    mockMvc.perform(post("/api/payments/{paymentId}/fail", paymentId)
            .header("Idempotency-Key", "capture-operation-001")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"DECLINED\",\"reason\":\"Different operation\"}"))
        .andExpect(status().isConflict());

    assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM payments WHERE payment_id = ?", paymentId))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payment_transactions WHERE event_type = 'CAPTURED'",
        Long.class
    )).isEqualTo(1L);
  }

  @Test
  void accountsForFullRefundsUsingTransactionTimeAcrossReportingPeriods() throws Exception {
    OrderSetup setup = createOrderAndSelect("LEDGER-REFUND", "PREPAID", null, "QUICKEXPRESS");
    JsonNode payment = createPrepaidPayment(setup, null);
    String paymentId = payment.path("paymentId").asText();
    BigDecimal amount = payment.path("amount").decimalValue();

    mockMvc.perform(post("/api/payments/{paymentId}/confirm", paymentId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(action(
                "Provider capture", "PAYMENT_GATEWAY", "capture-refund-001", "settlement-refund-001"))))
        .andExpect(status().isOk());
    createShipment(setup.orderId());
    mockMvc.perform(post("/api/orders/{orderId}/cancel", setup.orderId()))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/payments/{paymentId}", paymentId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REFUND_PENDING"));

    Map<String, Object> refund = action(
        "Customer cancellation refund",
        "PAYMENT_GATEWAY",
        "refund-provider-001",
        "refund-reconciliation-001"
    );
    for (int attempt = 0; attempt < 2; attempt++) {
      mockMvc.perform(post("/api/payments/{paymentId}/refund", paymentId)
              .header("Idempotency-Key", "refund-operation-001")
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(refund)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("REFUNDED"))
          .andExpect(jsonPath("$.refundReference").value("refund-provider-001"))
          .andExpect(jsonPath("$.reconciliationReference").value("settlement-refund-001"))
          .andExpect(jsonPath("$.refundReconciliationReference").value("refund-reconciliation-001"))
          .andExpect(jsonPath("$.refundedAmount").value(amount.doubleValue()));
    }

    mockMvc.perform(get("/api/payments/{paymentId}/transactions", paymentId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalTransactions").value(4))
        .andExpect(jsonPath("$.transactions[2].eventType").value("REFUND_PENDING"))
        .andExpect(jsonPath("$.transactions[3].eventType").value("REFUNDED"))
        .andExpect(jsonPath("$.transactions[3].providerReference").value("refund-provider-001"));

    JsonNode allTime = reportSummary(null, null);
    assertThat(allTime.path("finance").path("grossCollected").decimalValue()).isEqualByComparingTo(amount);
    assertThat(allTime.path("finance").path("refunds").decimalValue()).isEqualByComparingTo(amount);
    assertThat(allTime.path("finance").path("netCollected").decimalValue()).isEqualByComparingTo("0.00");

    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    String oldCaptureTime = today.minusMonths(1).atStartOfDay(ZoneOffset.UTC).plusHours(12).toInstant().toString();
    jdbcTemplate.update(
        "UPDATE payment_transactions SET created_at = ? WHERE payment_id = (SELECT id FROM payments WHERE payment_id = ?) AND event_type = 'CAPTURED'",
        oldCaptureTime,
        paymentId
    );
    JsonNode todayOnly = reportSummary(today, today);
    assertThat(todayOnly.path("finance").path("grossCollected").decimalValue()).isEqualByComparingTo("0.00");
    assertThat(todayOnly.path("finance").path("refunds").decimalValue()).isEqualByComparingTo(amount);
    assertThat(todayOnly.path("finance").path("netCollected").decimalValue()).isEqualByComparingTo(amount.negate());
    assertThat(todayOnly.path("dailyTrend").get(0).path("grossCollected").decimalValue()).isEqualByComparingTo("0.00");
    assertThat(todayOnly.path("dailyTrend").get(0).path("refunds").decimalValue()).isEqualByComparingTo(amount);
  }

  @Test
  void filtersCollectedCodAndSearchesAnyLedgerReference() throws Exception {
    OrderSetup cod = createOrderAndSelect("LEDGER-COD", "COD", new BigDecimal("725.00"), "FASTSHIP");
    createShipment(cod.orderId());
    for (int step = 0; step < 4; step++) {
      mockMvc.perform(post("/api/mock-carriers/{orderId}/advance", cod.orderId()))
          .andExpect(status().isOk());
    }
    String codPaymentId = firstOrderPaymentId(cod.orderId());
    mockMvc.perform(post("/api/payments/{paymentId}/collect", codPaymentId)
            .header("Idempotency-Key", "collect-operation-001")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(action(
                "Cash deposited", "COD_PARTNER", "collection-ledger-001", "deposit-ledger-001"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.collectedAt").isNotEmpty());

    mockMvc.perform(get("/api/reports/payments")
            .param("status", "SUCCEEDED")
            .param("method", "COD")
            .param("carrier", "FASTSHIP")
            .param("search", codPaymentId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalPayments").value(1))
        .andExpect(jsonPath("$.payments[0].paymentId").value(codPaymentId))
        .andExpect(jsonPath("$.finance.grossCollected").value(725.00));

    OrderSetup failed = createOrderAndSelect("LEDGER-FAILED", "PREPAID", null, "RELIABLE");
    String failedPaymentId = createPrepaidPayment(failed, null).path("paymentId").asText();
    mockMvc.perform(post("/api/payments/{paymentId}/fail", failedPaymentId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"BANK_DECLINED\",\"reason\":\"Issuer declined\",\"reference\":\"failure-audit-only-001\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"));
    mockMvc.perform(get("/api/reports/payments")
            .param("status", "FAILED")
            .param("method", "PREPAID")
            .param("carrier", "RELIABLE")
            .param("search", "failure-audit-only-001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalPayments").value(1))
        .andExpect(jsonPath("$.payments[0].paymentId").value(failedPaymentId));
  }

  @Test
  void recordsManualCancellationFailureAndAutomaticCodVoid() throws Exception {
    OrderSetup cancelled = createOrderAndSelect("LEDGER-MANUAL-CANCEL", "PREPAID", null, "FASTSHIP");
    String cancelledPaymentId = createPrepaidPayment(cancelled, null).path("paymentId").asText();
    mockMvc.perform(post("/api/payments/{paymentId}/cancel", cancelledPaymentId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(action(
                "Duplicate checkout", "ZIPPY_OPERATIONS", "cancel-ledger-001", null))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
    mockMvc.perform(get("/api/payments/{paymentId}/transactions", cancelledPaymentId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.transactions[1].eventType").value("CANCELLED"));

    JsonNode codOrder = createOrder("LEDGER-AUTO-VOID", "COD", new BigDecimal("300.00"));
    String codOrderId = codOrder.path("zippy_order_id").asText();
    String codPaymentId = firstOrderPaymentId(codOrderId);
    mockMvc.perform(post("/api/orders/{orderId}/cancel", codOrderId))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/payments/{paymentId}/transactions", codPaymentId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.transactions[0].eventType").value("COD_AWAITING_COLLECTION"))
        .andExpect(jsonPath("$.transactions[1].eventType").value("AUTO_VOIDED"))
        .andExpect(jsonPath("$.transactions[1].actor").value("ZIPPY_SYSTEM"))
        .andExpect(jsonPath("$.transactions[1].resultingStatus").value("VOIDED"));
  }

  @Test
  void freezesMaterialCarrierChangesAndRevalidatesThePaidQuote() throws Exception {
    OrderSetup setup = createOrderAndSelect("PAYMENT-QUOTE-INVARIANT", "PREPAID", null, "FASTSHIP");
    JsonNode payment = createPrepaidPayment(setup, "quote-intent-key-001");
    String paymentId = payment.path("paymentId").asText();

    mockMvc.perform(post("/api/orders/{orderId}/select-carrier", setup.orderId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "carrierCode", setup.carrier(),
                "serviceCode", "FAST-AIR",
                "quotedAmount", setup.quotedAmount()
            ))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.selectedShipment.carrierCode").value("FASTSHIP"));

    JsonNode rates = objectMapper.readTree(mockMvc.perform(
            get("/api/orders/{orderId}/rates", setup.orderId()))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString());
    JsonNode alternative = rates.path("shippingOptions").get(0);
    if (setup.carrier().equals(alternative.path("carrierCode").asText())) {
      alternative = rates.path("shippingOptions").get(1);
    }
    mockMvc.perform(post("/api/orders/{orderId}/select-carrier", setup.orderId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "carrierCode", alternative.path("carrierCode").asText(),
                "serviceCode", alternative.path("serviceCode").asText(),
                "quotedAmount", alternative.path("totalCharge").decimalValue()
            ))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("Carrier cannot be changed after a prepaid payment intent exists"));

    jdbcTemplate.update(
        "UPDATE shipments SET quoted_amount = quoted_amount + 1 WHERE order_id = (SELECT id FROM orders WHERE zippy_order_id = ?)",
        setup.orderId());
    mockMvc.perform(post("/api/payments/{paymentId}/confirm", paymentId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("Payment amount no longer matches the selected shipment charge"));

    jdbcTemplate.update(
        "UPDATE shipments SET quoted_amount = ? WHERE order_id = (SELECT id FROM orders WHERE zippy_order_id = ?)",
        setup.quotedAmount(), setup.orderId());
    mockMvc.perform(post("/api/payments/{paymentId}/confirm", paymentId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    jdbcTemplate.update(
        "UPDATE shipments SET quoted_amount = quoted_amount + 1 WHERE order_id = (SELECT id FROM orders WHERE zippy_order_id = ?)",
        setup.orderId());
    mockMvc.perform(post("/api/orders/{orderId}/create-shipment", setup.orderId()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message")
            .value("A successful prepaid payment matching the selected shipment charge is required"));
  }

  @Test
  void replaysOriginalIntentAfterLaterFailureAndRejectsKeyReuse() throws Exception {
    OrderSetup setup = createOrderAndSelect("INTENT-REPLAY", "PREPAID", null, "RELIABLE");
    String key = "global-intent-replay-key-001";
    JsonNode created = createPrepaidPayment(setup, key);
    String paymentId = created.path("paymentId").asText();

    mockMvc.perform(post("/api/payments/{paymentId}/fail", paymentId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"DECLINED\",\"reason\":\"Test failure\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"));

    mockMvc.perform(post("/api/payments")
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "orderId", setup.orderId(),
                "amount", setup.quotedAmount(),
                "currency", "INR"
            ))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.paymentId").value(paymentId))
        .andExpect(jsonPath("$.status").value("PENDING"));
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payments WHERE order_id = (SELECT id FROM orders WHERE zippy_order_id = ?)",
        Long.class, setup.orderId())).isEqualTo(1L);

    mockMvc.perform(post("/api/payments")
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "orderId", setup.orderId(),
                "amount", setup.quotedAmount().add(BigDecimal.ONE),
                "currency", "INR"
            ))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("Idempotency key reused with a different payment operation"));
    mockMvc.perform(post("/api/payments/{paymentId}/confirm", paymentId)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isConflict());
  }

  @Test
  void treatsLikeMetacharactersInReportSearchAsLiteralText() throws Exception {
    OrderSetup literal = createOrderAndSelect("LITERAL-SEARCH-ONE", "PREPAID", null, "RELIABLE");
    String literalPayment = createPrepaidPayment(literal, null).path("paymentId").asText();
    mockMvc.perform(post("/api/payments/{paymentId}/fail", literalPayment)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"DECLINED\",\"reason\":\"One\",\"reference\":\"audit_ref_100\"}"))
        .andExpect(status().isOk());

    OrderSetup wildcardLookalike = createOrderAndSelect("LITERAL-SEARCH-TWO", "PREPAID", null, "RELIABLE");
    String lookalikePayment = createPrepaidPayment(wildcardLookalike, null).path("paymentId").asText();
    mockMvc.perform(post("/api/payments/{paymentId}/fail", lookalikePayment)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"DECLINED\",\"reason\":\"Two\",\"reference\":\"auditXrefY100\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/reports/payments").param("search", "audit_ref_"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalPayments").value(1))
        .andExpect(jsonPath("$.payments[0].paymentId").value(literalPayment));
    mockMvc.perform(get("/api/reports/payments").param("search", "%"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalPayments").value(0));
  }

  @Test
  void attributesShipmentCostOnlyToTheLatestPaymentOutcome() throws Exception {
    OrderSetup setup = createOrderAndSelect("CANONICAL-PAYMENT", "PREPAID", null, "FASTSHIP");
    String failedId = createPrepaidPayment(setup, null).path("paymentId").asText();
    mockMvc.perform(post("/api/payments/{paymentId}/fail", failedId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"DECLINED\",\"reason\":\"Retry allowed\"}"))
        .andExpect(status().isOk());
    String succeededId = createPrepaidPayment(setup, null).path("paymentId").asText();
    mockMvc.perform(post("/api/payments/{paymentId}/confirm", succeededId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/reports/summary").param("status", "FAILED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.finance.shipmentCost").value(0.00))
        .andExpect(jsonPath("$.finance.costedShipmentCount").value(0));
    mockMvc.perform(get("/api/reports/summary").param("status", "SUCCEEDED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.finance.shipmentCost").value(setup.quotedAmount().doubleValue()))
        .andExpect(jsonPath("$.finance.costedShipmentCount").value(1));
  }

  @Test
  void rejectsMalformedOrReversedUtcDateRanges() throws Exception {
    mockMvc.perform(get("/api/reports/summary")
            .param("from", "2026-08-08")
            .param("to", "2026-08-07"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("from must be on or before to"))
        .andExpect(jsonPath("$.requestId").isNotEmpty());

    mockMvc.perform(get("/api/reports/payments").param("from", "08/07/2026"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Invalid request parameter"))
        .andExpect(jsonPath("$.details[0]").value("from has an invalid value"));
  }

  private JsonNode reportSummary(LocalDate from, LocalDate to) throws Exception {
    var request = get("/api/reports/summary");
    if (from != null) {
      request.param("from", from.toString());
    }
    if (to != null) {
      request.param("to", to.toString());
    }
    return objectMapper.readTree(mockMvc.perform(request)
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString());
  }

  private OrderSetup createOrderAndSelect(
      String merchantOrderId,
      String paymentType,
      BigDecimal codAmount,
      String carrier
  ) throws Exception {
    JsonNode order = createOrder(merchantOrderId, paymentType, codAmount);
    String orderId = order.path("zippy_order_id").asText();
    JsonNode quote = null;
    for (JsonNode candidate : order.path("shippingOptions")) {
      if (carrier.equals(candidate.path("carrierCode").asText())) {
        quote = candidate;
        break;
      }
    }
    if (quote == null) {
      throw new IllegalStateException("Quote not found for " + carrier);
    }
    mockMvc.perform(post("/api/orders/{orderId}/select-carrier", orderId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "carrierCode", carrier,
                "serviceCode", quote.path("serviceCode").asText(),
                "quotedAmount", quote.path("totalCharge").decimalValue()
            ))))
        .andExpect(status().isOk());
    return new OrderSetup(orderId, carrier, quote.path("totalCharge").decimalValue());
  }

  private JsonNode createOrder(String merchantOrderId, String paymentType, BigDecimal codAmount) throws Exception {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("merchantOrderId", merchantOrderId);
    request.put("customer", Map.of(
        "name", "Finance Ledger Test",
        "phone", "9876543210",
        "email", "finance@example.com"
    ));
    request.put("pickupAddress", Map.of(
        "addressLine1", "1 Origin Road",
        "city", "Mumbai",
        "state", "Maharashtra",
        "pincode", "400001"
    ));
    request.put("deliveryAddress", Map.of(
        "addressLine1", "2 Destination Road",
        "city", "Delhi",
        "state", "Delhi",
        "pincode", "110001"
    ));
    request.put("package", Map.of(
        "weightGrams", 1000,
        "lengthCm", 20,
        "widthCm", 15,
        "heightCm", 10
    ));
    request.put("paymentType", paymentType);
    if (codAmount != null) {
      request.put("codAmount", codAmount);
    }
    return objectMapper.readTree(mockMvc.perform(post("/api/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString());
  }

  private JsonNode createPrepaidPayment(OrderSetup setup, String idempotencyKey) throws Exception {
    var request = post("/api/payments")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(Map.of(
            "orderId", setup.orderId(),
            "amount", setup.quotedAmount(),
            "currency", "INR"
        )));
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }
    return objectMapper.readTree(mockMvc.perform(request)
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString());
  }

  private void createShipment(String orderId) throws Exception {
    mockMvc.perform(post("/api/orders/{orderId}/create-shipment", orderId))
        .andExpect(status().isOk());
  }

  private String firstOrderPaymentId(String orderId) throws Exception {
    return objectMapper.readTree(mockMvc.perform(get("/api/orders/{orderId}/payments", orderId))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString()).get(0).path("paymentId").asText();
  }

  private Map<String, Object> action(
      String reason,
      String provider,
      String reference,
      String reconciliationReference
  ) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reason", reason);
    body.put("provider", provider);
    body.put("reference", reference);
    if (reconciliationReference != null) {
      body.put("reconciliationReference", reconciliationReference);
    }
    return body;
  }

  private record OrderSetup(String orderId, String carrier, BigDecimal quotedAmount) {}
}
