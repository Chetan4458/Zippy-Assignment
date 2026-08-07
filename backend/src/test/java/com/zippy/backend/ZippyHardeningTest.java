package com.zippy.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zippy.backend.service.ZippyService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ZippyHardeningTest {
  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private ZippyService zippyService;

  @BeforeEach
  void resetDatabase() {
    jdbcTemplate.execute("DELETE FROM payment_transactions");
    jdbcTemplate.execute("DELETE FROM shipment_events");
    jdbcTemplate.execute("DELETE FROM shipments");
    jdbcTemplate.execute("DELETE FROM shipping_quotes");
    jdbcTemplate.execute("DELETE FROM orders");
    jdbcTemplate.execute("DELETE FROM idempotency_keys");
    jdbcTemplate.execute("DELETE FROM idempotency_locks");
    jdbcTemplate.execute("MERGE INTO app_meta (meta_key, meta_value) KEY(meta_key) VALUES ('order_sequence', '10000')");
    zippyService.resetRuntimeFlags();
  }

  @Test
  void returnsStableNonLeakingErrorsWithRequestIds() throws Exception {
    mockMvc.perform(post("/api/orders")
            .header("X-Request-Id", "audit-request-1")
            .contentType("application/json")
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("X-Request-Id", "audit-request-1"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.message").value("Validation failed"))
        .andExpect(jsonPath("$.requestId").value("audit-request-1"))
        .andExpect(jsonPath("$.details").isArray());

    mockMvc.perform(post("/api/orders")
            .contentType("application/json")
            .content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.message").value("Malformed JSON request"));

    mockMvc.perform(get("/api/orders/history").param("limit", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Validation failed"));

    mockMvc.perform(get("/api/orders/missing/extra"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Resource not found"));

    mockMvc.perform(get("/api"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.message").value("Resource not found"));
  }

  @Test
  void mapsDuplicateBusinessKeysToConflictWithoutSqlDetails() throws Exception {
    String body = objectMapper.writeValueAsString(sampleOrder("DUPLICATE-ORDER", "COD"));
    mockMvc.perform(post("/api/orders").contentType("application/json").content(body))
        .andExpect(status().isCreated());
    mockMvc.perform(post("/api/orders").contentType("application/json").content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("Request conflicts with existing data"))
        .andExpect(jsonPath("$.requestId").isNotEmpty());
  }

  @Test
  void validatesCustomerAddressPaymentTypeAndPackageShape() throws Exception {
    Map<String, Object> invalid = sampleOrder("INVALID", "WIRE");
    invalid.put("customer", Map.of("name", "A", "phone", "123", "email", "not-an-email"));
    invalid.put("deliveryAddress", Map.of(
        "addressLine1", "B", "city", "Delhi", "state", "Delhi", "pincode", "012345"));
    invalid.put("package", Map.of("weightGrams", 1.5, "lengthCm", 20, "widthCm", 15, "heightCm", 10));

    String response = mockMvc.perform(post("/api/orders")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(invalid)))
        .andExpect(status().isBadRequest())
        .andReturn().getResponse().getContentAsString();

    JsonNode details = objectMapper.readTree(response).path("details");
    assertThat(details.toString()).contains("customer.phone", "customer.email", "deliveryAddress.pincode", "paymentType", "packageDetails.weightGrams");
  }

  @Test
  void exposesDatabaseBackedHealth() throws Exception {
    mockMvc.perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.database").value("UP"))
        .andExpect(jsonPath("$.timestamp").isNotEmpty());
  }

  @Test
  void createsUniqueCarrierIdentifiersAndRoutesWebhookToSecondShipment() throws Exception {
    JsonNode first = createOrder("TRACKING-1", "COD");
    JsonNode firstShipment = selectAndCreate(first, "FASTSHIP");
    JsonNode second = createOrder("TRACKING-2", "COD");
    JsonNode secondShipment = selectAndCreate(second, "FASTSHIP");

    assertThat(firstShipment.path("shipment").path("carrierShipmentId").asText()).isEqualTo("FS-700001");
    assertThat(secondShipment.path("shipment").path("carrierShipmentId").asText()).isEqualTo("FS-700002");
    assertThat(secondShipment.path("shipment").path("trackingNumber").asText())
        .isNotEqualTo(firstShipment.path("shipment").path("trackingNumber").asText());

    Map<String, Object> event = new LinkedHashMap<>();
    event.put("event_id", "FS-EVENT-SECOND-1");
    event.put("shipment_id", "FS-700002");
    event.put("tracking_number", secondShipment.path("shipment").path("trackingNumber").asText());
    event.put("event_code", "PICKED_UP");
    event.put("event_description", "Second parcel picked up");
    event.put("event_time", "2026-08-07T10:00:00Z");
    event.put("location", "Bengaluru Hub");
    mockMvc.perform(post("/api/webhooks/fastship")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(event)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PICKED_UP"))
        .andExpect(jsonPath("$.carrierEventId").value("FS-EVENT-SECOND-1"));
  }

  @Test
  void preventsCancelledOrderResurrectionAndPaymentCreation() throws Exception {
    JsonNode cancelledBeforeSelection = createOrder("CANCEL-BEFORE-SELECT", "PREPAID");
    String firstOrderId = cancelledBeforeSelection.path("zippy_order_id").asText();
    JsonNode firstQuote = findQuote(cancelledBeforeSelection, "FASTSHIP");
    mockMvc.perform(post("/api/orders/{orderId}/cancel", firstOrderId)).andExpect(status().isOk());
    select(firstOrderId, firstQuote, status().isConflict());

    JsonNode cancelledAfterSelection = createOrder("CANCEL-BEFORE-PAY", "PREPAID");
    String secondOrderId = cancelledAfterSelection.path("zippy_order_id").asText();
    JsonNode secondQuote = findQuote(cancelledAfterSelection, "RELIABLE");
    select(secondOrderId, secondQuote, status().isOk());
    mockMvc.perform(post("/api/orders/{orderId}/cancel", secondOrderId)).andExpect(status().isOk());
    mockMvc.perform(post("/api/payments")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(Map.of(
                "orderId", secondOrderId,
                "amount", secondQuote.path("totalCharge").decimalValue(),
                "currency", "INR"
            ))))
        .andExpect(status().isConflict());
  }

  @Test
  void allowsDeliveryRetryAndDeduplicatesExactOldEventAfterStateAdvances() throws Exception {
    JsonNode order = createOrder("DELIVERY-RETRY", "COD");
    String orderId = order.path("zippy_order_id").asText();
    JsonNode shipment = selectAndCreate(order, "QUICKEXPRESS");
    for (int index = 0; index < 3; index++) {
      mockMvc.perform(post("/api/mock-carriers/{orderId}/advance", orderId))
          .andExpect(status().isOk());
    }
    String failureResponse = mockMvc.perform(post("/api/mock-carriers/{orderId}/delivery-failed", orderId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DELIVERY_FAILED"))
        .andReturn().getResponse().getContentAsString();
    String failureEventId = objectMapper.readTree(failureResponse).path("carrierEventId").asText();

    mockMvc.perform(post("/api/mock-carriers/{orderId}/advance", orderId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.duplicate").value(false))
        .andExpect(jsonPath("$.status").value("IN_TRANSIT"));

    Map<String, Object> replay = Map.of(
        "event_id", failureEventId,
        "awb", shipment.path("shipment").path("trackingNumber").asText(),
        "event", Map.of(
            "type", "NDR",
            "message", "Customer unavailable",
            "occurredAt", "2026-08-07T11:00:00Z"
        ),
        "facility", Map.of("city", "Delhi")
    );
    mockMvc.perform(post("/api/webhooks/quickexpress")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(replay)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.duplicate").value(true))
        .andExpect(jsonPath("$.status").value("IN_TRANSIT"));
  }

  @Test
  void appliesOneGlobalDeadlineAcrossMultipleSlowCarriers() throws Exception {
    zippyService.setRuntimeFlags(Map.of(
        "fastshipRateDelayMs", 3000,
        "quickexpressRateDelayMs", 3000
    ));
    long startedAt = System.nanoTime();
    mockMvc.perform(post("/api/orders")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(sampleOrder("GLOBAL-DEADLINE", "COD"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.shippingOptions.length()").value(1))
        .andExpect(jsonPath("$.shippingOptions[0].carrierCode").value("RELIABLE"));
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
    assertThat(elapsedMs).isLessThan(2600L);
  }

  @Test
  void normalizesWebhooksForEveryCarrier() throws Exception {
    for (String carrier : new String[]{"FASTSHIP", "QUICKEXPRESS", "RELIABLE"}) {
      JsonNode order = createOrder("ALL-CARRIERS-" + carrier, "COD");
      String orderId = order.path("zippy_order_id").asText();
      selectAndCreate(order, carrier);
      mockMvc.perform(post("/api/mock-carriers/{orderId}/advance", orderId))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("PICKED_UP"));
    }
  }

  @Test
  void keepsPrepaidLifecycleIdempotentAndReturnsUtcTimestamps() throws Exception {
    JsonNode order = createOrder("PREPAID-LIFECYCLE", "PREPAID");
    String orderId = order.path("zippy_order_id").asText();
    JsonNode quote = findQuote(order, "RELIABLE");
    select(orderId, quote, status().isOk());
    Map<String, Object> paymentRequest = Map.of(
        "orderId", orderId,
        "amount", quote.path("totalCharge").decimalValue(),
        "currency", "INR"
    );
    String requestBody = objectMapper.writeValueAsString(paymentRequest);
    String created = mockMvc.perform(post("/api/payments")
            .contentType("application/json").content(requestBody))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.createdAt").isNotEmpty())
        .andExpect(jsonPath("$.updatedAt").isNotEmpty())
        .andReturn().getResponse().getContentAsString();
    JsonNode createdPayment = objectMapper.readTree(created);
    String paymentId = createdPayment.path("paymentId").asText();
    assertThat(createdPayment.path("createdAt").asText()).endsWith("Z");
    assertThat(createdPayment.path("updatedAt").asText()).endsWith("Z");

    mockMvc.perform(post("/api/orders/{orderId}/create-shipment", orderId))
        .andExpect(status().isConflict());
    mockMvc.perform(post("/api/payments").contentType("application/json").content(requestBody))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.paymentId").value(paymentId));
    mockMvc.perform(post("/api/payments/{paymentId}/confirm", paymentId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    mockMvc.perform(post("/api/orders/{orderId}/create-shipment", orderId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.shipment.currentStatus").value("SHIPMENT_CREATED"));
    mockMvc.perform(post("/api/payments").contentType("application/json").content(requestBody))
        .andExpect(status().isConflict());
    mockMvc.perform(post("/api/payments/{paymentId}/refund", paymentId))
        .andExpect(status().isConflict());
    mockMvc.perform(post("/api/orders/{orderId}/cancel", orderId))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/payments/{paymentId}/refund", paymentId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REFUNDED"))
        .andExpect(jsonPath("$.updatedAt").isNotEmpty());
  }

  @Test
  void calculatesReliablePrepaidTaxFromActualSubtotal() throws Exception {
    JsonNode order = createOrder("PREPAID-TAX", "PREPAID");
    JsonNode reliable = findQuote(order, "RELIABLE");
    assertThat(reliable.path("tax").decimalValue()).isEqualByComparingTo(new BigDecimal("18.90"));
    assertThat(reliable.path("totalCharge").decimalValue()).isEqualByComparingTo(new BigDecimal("123.90"));
  }

  @Test
  void replaysConcurrentRequestsWithTheSameIdempotencyKey() throws Exception {
    zippyService.setRuntimeFlags(Map.of(
        "fastshipRateDelayMs", 250,
        "quickexpressRateDelayMs", 250,
        "reliableRateDelayMs", 250
    ));
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<JsonNode> first = executor.submit(() -> {
        start.await(5, TimeUnit.SECONDS);
        return createOrder("CONCURRENT-IDEMPOTENT", "COD", "concurrent-create-key");
      });
      Future<JsonNode> second = executor.submit(() -> {
        start.await(5, TimeUnit.SECONDS);
        return createOrder("CONCURRENT-IDEMPOTENT", "COD", "concurrent-create-key");
      });
      start.countDown();

      JsonNode firstResponse = first.get(10, TimeUnit.SECONDS);
      JsonNode secondResponse = second.get(10, TimeUnit.SECONDS);
      assertThat(firstResponse.path("zippy_order_id").asText())
          .isEqualTo(secondResponse.path("zippy_order_id").asText());
      assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Long.class)).isEqualTo(1L);
    } finally {
      executor.shutdownNow();
      zippyService.resetRuntimeFlags();
    }
  }

  @Test
  void allocatesUniqueOrderNumbersAcrossConcurrentRequests() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<JsonNode> first = executor.submit(() -> {
        start.await(5, TimeUnit.SECONDS);
        return createOrder("CONCURRENT-ORDER-A", "COD");
      });
      Future<JsonNode> second = executor.submit(() -> {
        start.await(5, TimeUnit.SECONDS);
        return createOrder("CONCURRENT-ORDER-B", "COD");
      });
      start.countDown();

      Set<String> orderIds = Set.of(
          first.get(10, TimeUnit.SECONDS).path("zippy_order_id").asText(),
          second.get(10, TimeUnit.SECONDS).path("zippy_order_id").asText()
      );
      assertThat(orderIds).containsExactlyInAnyOrder("ZPY-ORD-10001", "ZPY-ORD-10002");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void serializesCompetingPaymentTransitions() throws Exception {
    JsonNode order = createOrder("PAYMENT-RACE", "PREPAID");
    String orderId = order.path("zippy_order_id").asText();
    JsonNode quote = findQuote(order, "FASTSHIP");
    select(orderId, quote, status().isOk());
    String paymentId = objectMapper.readTree(mockMvc.perform(post("/api/payments")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(Map.of(
                "orderId", orderId,
                "amount", quote.path("totalCharge").decimalValue(),
                "currency", "INR"
            ))))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString()).path("paymentId").asText();

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> confirmStatus = executor.submit(() -> {
        start.await(5, TimeUnit.SECONDS);
        return mockMvc.perform(post("/api/payments/{paymentId}/confirm", paymentId))
            .andReturn().getResponse().getStatus();
      });
      Future<Integer> failStatus = executor.submit(() -> {
        start.await(5, TimeUnit.SECONDS);
        return mockMvc.perform(post("/api/payments/{paymentId}/fail", paymentId)
                .contentType("application/json")
                .content("{\"code\":\"DECLINED\",\"reason\":\"Concurrent failure\"}"))
            .andReturn().getResponse().getStatus();
      });
      start.countDown();

      assertThat(new int[] {
          confirmStatus.get(10, TimeUnit.SECONDS),
          failStatus.get(10, TimeUnit.SECONDS)
      }).containsExactlyInAnyOrder(200, 409);
      String finalStatus = objectMapper.readTree(mockMvc.perform(get("/api/payments/{paymentId}", paymentId))
          .andExpect(status().isOk())
          .andReturn().getResponse().getContentAsString()).path("status").asText();
      assertThat(finalStatus).isIn("SUCCEEDED", "FAILED");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void serializesCancellationAgainstCarrierWebhooks() throws Exception {
    JsonNode order = createOrder("WEBHOOK-CANCEL-RACE", "COD");
    String orderId = order.path("zippy_order_id").asText();
    JsonNode shipment = selectAndCreate(order, "FASTSHIP");
    String carrierShipmentId = shipment.path("shipment").path("carrierShipmentId").asText();
    Map<String, Object> webhook = Map.of(
        "event_id", "race-picked-up",
        "shipment_id", carrierShipmentId,
        "event_code", "PICKED_UP",
        "event_description", "Picked up during cancellation race",
        "location", "Concurrency Hub",
        "event_time", java.time.Instant.now().toString()
    );

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> cancelStatus = executor.submit(() -> {
        start.await(5, TimeUnit.SECONDS);
        return mockMvc.perform(post("/api/orders/{orderId}/cancel", orderId))
            .andReturn().getResponse().getStatus();
      });
      Future<Integer> webhookStatus = executor.submit(() -> {
        start.await(5, TimeUnit.SECONDS);
        return mockMvc.perform(post("/api/webhooks/fastship")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(webhook)))
            .andReturn().getResponse().getStatus();
      });
      start.countDown();

      assertThat(new int[] {
          cancelStatus.get(10, TimeUnit.SECONDS),
          webhookStatus.get(10, TimeUnit.SECONDS)
      }).containsExactlyInAnyOrder(200, 409);
      String finalStatus = objectMapper.readTree(mockMvc.perform(get("/api/orders/{orderId}/tracking", orderId))
          .andExpect(status().isOk())
          .andReturn().getResponse().getContentAsString()).path("selectedShipment").path("current_status").asText();
      assertThat(finalStatus).isIn("CANCELLED", "PICKED_UP");
    } finally {
      executor.shutdownNow();
    }
  }

  private JsonNode createOrder(String merchantOrderId, String paymentType) throws Exception {
    return objectMapper.readTree(mockMvc.perform(post("/api/orders")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(sampleOrder(merchantOrderId, paymentType))))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString());
  }

  private JsonNode createOrder(String merchantOrderId, String paymentType, String idempotencyKey) throws Exception {
    return objectMapper.readTree(mockMvc.perform(post("/api/orders")
            .header("Idempotency-Key", idempotencyKey)
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(sampleOrder(merchantOrderId, paymentType))))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString());
  }

  private JsonNode selectAndCreate(JsonNode order, String carrier) throws Exception {
    String orderId = order.path("zippy_order_id").asText();
    select(orderId, findQuote(order, carrier), status().isOk());
    return objectMapper.readTree(mockMvc.perform(post("/api/orders/{orderId}/create-shipment", orderId))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString());
  }

  private void select(
      String orderId,
      JsonNode quote,
      org.springframework.test.web.servlet.ResultMatcher expectedStatus
  ) throws Exception {
    mockMvc.perform(post("/api/orders/{orderId}/select-carrier", orderId)
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(Map.of(
                "carrierCode", quote.path("carrierCode").asText(),
                "serviceCode", quote.path("serviceCode").asText(),
                "quotedAmount", quote.path("totalCharge").decimalValue()
            ))))
        .andExpect(expectedStatus);
  }

  private JsonNode findQuote(JsonNode order, String carrier) {
    for (JsonNode quote : order.path("shippingOptions")) {
      if (carrier.equals(quote.path("carrierCode").asText())) {
        return quote;
      }
    }
    throw new IllegalStateException("Quote not found for " + carrier);
  }

  private Map<String, Object> sampleOrder(String merchantOrderId, String paymentType) {
    Map<String, Object> order = new LinkedHashMap<>();
    order.put("merchantOrderId", merchantOrderId);
    order.put("customer", Map.of(
        "name", "Rahul Sharma",
        "phone", "9876543210",
        "email", "rahul@example.com"
    ));
    order.put("pickupAddress", Map.of(
        "addressLine1", "15 MG Road",
        "city", "Bengaluru",
        "state", "Karnataka",
        "pincode", "560001"
    ));
    order.put("deliveryAddress", Map.of(
        "addressLine1", "22 Connaught Place",
        "city", "New Delhi",
        "state", "Delhi",
        "pincode", "110001"
    ));
    order.put("package", Map.of(
        "weightGrams", 1500,
        "lengthCm", 20,
        "widthCm", 15,
        "heightCm", 10
    ));
    order.put("paymentType", paymentType);
    if ("COD".equals(paymentType)) {
      order.put("codAmount", 2500);
    }
    return order;
  }
}
