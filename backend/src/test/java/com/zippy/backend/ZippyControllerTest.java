package com.zippy.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zippy.backend.service.ZippyService;
import java.util.LinkedHashMap;
import java.util.Map;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ZippyControllerTest {
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
    jdbcTemplate.execute("DELETE FROM shipment_events");
    jdbcTemplate.execute("DELETE FROM shipments");
    jdbcTemplate.execute("DELETE FROM shipping_quotes");
    jdbcTemplate.execute("DELETE FROM orders");
    jdbcTemplate.execute("DELETE FROM idempotency_keys");
    jdbcTemplate.execute("MERGE INTO app_meta (meta_key, meta_value) KEY(meta_key) VALUES ('order_sequence', '10000')");
    zippyService.resetRuntimeFlags();
  }

  @Test
  void createsOrderAndReturnsNormalizedQuotes() throws Exception {
    String response = mockMvc.perform(post("/api/orders")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(sampleOrder())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.zippy_order_id").value("ZPY-ORD-10001"))
        .andExpect(jsonPath("$.shippingOptions.length()").value(3))
        .andReturn()
        .getResponse()
        .getContentAsString();

    JsonNode body = objectMapper.readTree(response);
    assertThat(body.path("shippingOptions").get(0).path("carrierCode").asText()).isEqualTo("RELIABLE");
  }

  @Test
  void quickexpressFailureDoesNotBlockOtherCarrierQuotes() throws Exception {
    zippyService.setRuntimeFlags(Map.of("quickexpressRateFailure", true));

    String response = mockMvc.perform(post("/api/orders")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(sampleOrder())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.shippingOptions.length()").value(2))
        .andReturn()
        .getResponse()
        .getContentAsString();

    JsonNode body = objectMapper.readTree(response);
    assertThat(body.path("shippingOptions").findValuesAsText("carrierCode"))
        .contains("FASTSHIP", "RELIABLE")
        .doesNotContain("QUICKEXPRESS");
  }

  @Test
  void slowCarrierTimesOutWithoutBlockingOtherQuotes() throws Exception {
    zippyService.setRuntimeFlags(Map.of("fastshipRateDelayMs", 2500));

    long startedAt = System.nanoTime();
    String response = mockMvc.perform(post("/api/orders")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(sampleOrder())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.shippingOptions.length()").value(2))
        .andReturn()
        .getResponse()
        .getContentAsString();
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

    JsonNode body = objectMapper.readTree(response);
    assertThat(body.path("shippingOptions").findValuesAsText("carrierCode"))
        .contains("QUICKEXPRESS", "RELIABLE")
        .doesNotContain("FASTSHIP");
    assertThat(elapsedMs).isLessThan(4000);
  }

  @Test
  void selectsCarrierCreatesShipmentAndTracksDuplicateWebhook() throws Exception {
    JsonNode orderBody = createOrder();
    JsonNode fastShipQuote = findQuote(orderBody, "FASTSHIP");

    mockMvc.perform(post("/api/orders/{orderId}/select-carrier", "ZPY-ORD-10001")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(Map.of(
                "carrierCode", fastShipQuote.path("carrierCode").asText(),
                "serviceCode", fastShipQuote.path("serviceCode").asText(),
                "quotedAmount", fastShipQuote.path("totalCharge").decimalValue()
            ))))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/orders/{orderId}/create-shipment", "ZPY-ORD-10001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.shipment.currentStatus").value("SHIPMENT_CREATED"));

    Map<String, Object> pickedUp = new LinkedHashMap<>();
    pickedUp.put("shipment_id", "FS-700001");
    pickedUp.put("tracking_number", "FST123456789");
    pickedUp.put("event_code", "PICKED_UP");
    pickedUp.put("event_description", "Picked up");
    pickedUp.put("event_time", "2026-07-25T10:00:00Z");
    pickedUp.put("location", "Bengaluru Hub");

    mockMvc.perform(post("/api/webhooks/fastship")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(pickedUp)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PICKED_UP"));

    mockMvc.perform(post("/api/webhooks/fastship")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(pickedUp)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.duplicate").value(true));
  }

  @Test
  void rejectsWebhookForUnknownTrackingNumber() throws Exception {
    JsonNode orderBody = createOrder();
    JsonNode fastShipQuote = findQuote(orderBody, "FASTSHIP");

    mockMvc.perform(post("/api/orders/{orderId}/select-carrier", "ZPY-ORD-10001")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(Map.of(
                "carrierCode", fastShipQuote.path("carrierCode").asText(),
                "serviceCode", fastShipQuote.path("serviceCode").asText(),
                "quotedAmount", fastShipQuote.path("totalCharge").decimalValue()
            ))))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/orders/{orderId}/create-shipment", "ZPY-ORD-10001"))
        .andExpect(status().isOk());

    Map<String, Object> unknownTracking = new LinkedHashMap<>();
    unknownTracking.put("shipment_id", "FS-UNKNOWN");
    unknownTracking.put("tracking_number", "UNKNOWN123");
    unknownTracking.put("event_code", "IN_TRANSIT");
    unknownTracking.put("event_description", "Unknown shipment");
    unknownTracking.put("event_time", "2026-07-25T13:00:00Z");
    unknownTracking.put("location", "Nowhere");

    mockMvc.perform(post("/api/webhooks/fastship")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(unknownTracking)))
        .andExpect(status().isNotFound());
  }

  @Test
  void rejectsInvalidStatusTransition() throws Exception {
    JsonNode orderBody = createOrder();
    JsonNode fastShipQuote = findQuote(orderBody, "FASTSHIP");

    mockMvc.perform(post("/api/orders/{orderId}/select-carrier", "ZPY-ORD-10001")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(Map.of(
                "carrierCode", fastShipQuote.path("carrierCode").asText(),
                "serviceCode", fastShipQuote.path("serviceCode").asText(),
                "quotedAmount", fastShipQuote.path("totalCharge").decimalValue()
            ))))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/orders/{orderId}/create-shipment", "ZPY-ORD-10001"))
        .andExpect(status().isOk());

    Map<String, Object> pickedUp = new LinkedHashMap<>();
    pickedUp.put("shipment_id", "FS-700001");
    pickedUp.put("tracking_number", "FST123456789");
    pickedUp.put("event_code", "PICKED_UP");
    pickedUp.put("event_description", "Picked up");
    pickedUp.put("event_time", "2026-07-25T10:00:00Z");
    pickedUp.put("location", "Bengaluru Hub");
    mockMvc.perform(post("/api/webhooks/fastship")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(pickedUp)))
        .andExpect(status().isOk());

    Map<String, Object> inTransit = new LinkedHashMap<>();
    inTransit.put("shipment_id", "FS-700001");
    inTransit.put("tracking_number", "FST123456789");
    inTransit.put("event_code", "IN_TRANSIT");
    inTransit.put("event_description", "Moved again");
    inTransit.put("event_time", "2026-07-25T11:00:00Z");
    inTransit.put("location", "Delhi Hub");
    mockMvc.perform(post("/api/webhooks/fastship")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(inTransit)))
        .andExpect(status().isOk());

    Map<String, Object> delivered = new LinkedHashMap<>();
    delivered.put("shipment_id", "FS-700001");
    delivered.put("tracking_number", "FST123456789");
    delivered.put("event_code", "DELIVERED");
    delivered.put("event_description", "Delivered too early");
    delivered.put("event_time", "2026-07-25T12:00:00Z");
    delivered.put("location", "Delhi Hub");
    mockMvc.perform(post("/api/webhooks/fastship")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(delivered)))
        .andExpect(status().isConflict());
  }

  @Test
  void preservesSelectedQuoteAmountEvenIfStoredRatesChangeLater() throws Exception {
    JsonNode orderBody = createOrder();
    JsonNode fastShipQuote = findQuote(orderBody, "FASTSHIP");
    String quotedAmount = fastShipQuote.path("totalCharge").decimalValue().toPlainString();

    mockMvc.perform(post("/api/orders/{orderId}/select-carrier", "ZPY-ORD-10001")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(Map.of(
                "carrierCode", fastShipQuote.path("carrierCode").asText(),
                "serviceCode", fastShipQuote.path("serviceCode").asText(),
                "quotedAmount", fastShipQuote.path("totalCharge").decimalValue()
            ))))
        .andExpect(status().isOk());

    jdbcTemplate.update("""
        UPDATE shipping_quotes
        SET total_charge = ?
        WHERE order_id = (SELECT id FROM orders WHERE zippy_order_id = ?)
          AND carrier_code = ?
          AND service_code = ?
        """, 9999.00, "ZPY-ORD-10001", fastShipQuote.path("carrierCode").asText(), fastShipQuote.path("serviceCode").asText());

    mockMvc.perform(post("/api/orders/{orderId}/create-shipment", "ZPY-ORD-10001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.shipment.currentStatus").value("SHIPMENT_CREATED"));

    mockMvc.perform(get("/api/orders/{orderId}/tracking", "ZPY-ORD-10001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.selectedShipment.quoted_amount").value(Double.parseDouble(quotedAmount)));
  }

  @Test
  void rejectsDuplicateShipmentCreation() throws Exception {
    JsonNode orderBody = createOrder();
    JsonNode fastShipQuote = findQuote(orderBody, "FASTSHIP");
    selectCarrier(fastShipQuote);

    mockMvc.perform(post("/api/orders/{orderId}/create-shipment", "ZPY-ORD-10001"))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/orders/{orderId}/create-shipment", "ZPY-ORD-10001"))
        .andExpect(status().isConflict());
  }

  @Test
  void rejectsChangingCarrierAfterShipmentCreation() throws Exception {
    JsonNode orderBody = createOrder();
    JsonNode fastShipQuote = findQuote(orderBody, "FASTSHIP");
    selectCarrier(fastShipQuote);

    mockMvc.perform(post("/api/orders/{orderId}/create-shipment", "ZPY-ORD-10001"))
        .andExpect(status().isOk());

    JsonNode reliableQuote = findQuote(orderBody, "RELIABLE");
    mockMvc.perform(post("/api/orders/{orderId}/select-carrier", "ZPY-ORD-10001")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(Map.of(
                "carrierCode", reliableQuote.path("carrierCode").asText(),
                "serviceCode", reliableQuote.path("serviceCode").asText(),
                "quotedAmount", reliableQuote.path("totalCharge").decimalValue()
            ))))
        .andExpect(status().isConflict());
  }

  @Test
  void rejectsUnsupportedWebhookStatus() throws Exception {
    JsonNode orderBody = createOrder();
    JsonNode fastShipQuote = findQuote(orderBody, "FASTSHIP");
    selectCarrier(fastShipQuote);

    mockMvc.perform(post("/api/orders/{orderId}/create-shipment", "ZPY-ORD-10001"))
        .andExpect(status().isOk());

    Map<String, Object> unsupported = new LinkedHashMap<>();
    unsupported.put("shipment_id", "FS-700001");
    unsupported.put("tracking_number", "FST123456789");
    unsupported.put("event_code", "SOMETHING_NEW");
    unsupported.put("event_description", "Unknown status");
    unsupported.put("event_time", "2026-07-25T10:00:00Z");

    mockMvc.perform(post("/api/webhooks/fastship")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(unsupported)))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void reusesOrderResponseForSameIdempotencyKey() throws Exception {
    String requestBody = objectMapper.writeValueAsString(sampleOrder());

    String first = mockMvc.perform(post("/api/orders")
            .header("Idempotency-Key", "order-key-123")
            .contentType("application/json")
            .content(requestBody))
        .andExpect(status().isCreated())
        .andReturn()
        .getResponse()
        .getContentAsString();

    String second = mockMvc.perform(post("/api/orders")
            .header("Idempotency-Key", "order-key-123")
            .contentType("application/json")
            .content(requestBody))
        .andExpect(status().isCreated())
        .andReturn()
        .getResponse()
        .getContentAsString();

    assertThat(objectMapper.readTree(second).path("zippy_order_id").asText())
        .isEqualTo(objectMapper.readTree(first).path("zippy_order_id").asText());

    Integer orderCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Integer.class);
    assertThat(orderCount).isEqualTo(1);
  }

  @Test
  void returnsPagedEventsForShipmentHistory() throws Exception {
    JsonNode orderBody = createOrder();
    JsonNode fastShipQuote = findQuote(orderBody, "FASTSHIP");

    mockMvc.perform(post("/api/orders/{orderId}/select-carrier", "ZPY-ORD-10001")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(Map.of(
                "carrierCode", fastShipQuote.path("carrierCode").asText(),
                "serviceCode", fastShipQuote.path("serviceCode").asText(),
                "quotedAmount", fastShipQuote.path("totalCharge").decimalValue()
            ))))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/orders/{orderId}/create-shipment", "ZPY-ORD-10001"))
        .andExpect(status().isOk());

    String response = mockMvc.perform(get("/api/orders/{orderId}/events", "ZPY-ORD-10001")
            .param("limit", "1")
            .param("offset", "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limit").value(1))
        .andExpect(jsonPath("$.offset").value(0))
        .andExpect(jsonPath("$.events.length()").value(1))
        .andReturn()
        .getResponse()
        .getContentAsString();

    JsonNode body = objectMapper.readTree(response);
    assertThat(body.path("totalEvents").asInt()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void returnsSystemOverviewMetrics() throws Exception {
    mockMvc.perform(post("/api/orders")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(sampleOrder())))
        .andExpect(status().isCreated());

    mockMvc.perform(get("/api/system/overview"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orders").value(1))
        .andExpect(jsonPath("$.automationEnabled").exists())
        .andExpect(jsonPath("$.supportedCarriers.length()").value(3));
  }

  @Test
  void completesCodPaymentAfterDelivery() throws Exception {
    JsonNode orderBody = createOrder();
    JsonNode fastShipQuote = findQuote(orderBody, "FASTSHIP");

    String paymentsResponse = mockMvc.perform(get("/api/orders/{orderId}/payments", "ZPY-ORD-10001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].paymentMethod").value("COD"))
        .andExpect(jsonPath("$[0].status").value("AWAITING_COLLECTION"))
        .andReturn()
        .getResponse()
        .getContentAsString();
    String paymentId = objectMapper.readTree(paymentsResponse).get(0).path("paymentId").asText();

    selectCarrier(fastShipQuote);
    mockMvc.perform(post("/api/orders/{orderId}/create-shipment", "ZPY-ORD-10001"))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/payments/{paymentId}/collect", paymentId))
        .andExpect(status().isConflict());

    for (int index = 0; index < 4; index++) {
      mockMvc.perform(post("/api/mock-carriers/{orderId}/advance", "ZPY-ORD-10001"));
    }

    mockMvc.perform(post("/api/payments/{paymentId}/collect", paymentId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.paymentMethod").value("COD"));
  }

  @Test
  void voidsCodReceivableOnRtoAndAllowsDeliveryRetryBranch() throws Exception {
    JsonNode orderBody = createOrder();
    JsonNode fastShipQuote = findQuote(orderBody, "FASTSHIP");
    String paymentId = objectMapper.readTree(mockMvc.perform(get("/api/orders/{orderId}/payments", "ZPY-ORD-10001"))
        .andReturn().getResponse().getContentAsString()).get(0).path("paymentId").asText();
    selectCarrier(fastShipQuote);
    mockMvc.perform(post("/api/orders/{orderId}/create-shipment", "ZPY-ORD-10001"));
    for (int index = 0; index < 3; index++) {
      mockMvc.perform(post("/api/mock-carriers/{orderId}/advance", "ZPY-ORD-10001"));
    }

    Map<String, Object> failed = new LinkedHashMap<>();
    failed.put("shipment_id", "FS-700001");
    failed.put("tracking_number", "FST123456789");
    failed.put("event_code", "DELIVERY_FAILED");
    failed.put("event_description", "Customer unavailable");
    failed.put("event_time", "2026-08-02T10:00:00Z");
    mockMvc.perform(post("/api/webhooks/fastship")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(failed)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DELIVERY_FAILED"));

    Map<String, Object> rto = new LinkedHashMap<>(failed);
    rto.put("event_code", "RTO");
    rto.put("event_description", "Returned to origin");
    rto.put("event_time", "2026-08-02T11:00:00Z");
    mockMvc.perform(post("/api/webhooks/fastship")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(rto)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RTO"));

    mockMvc.perform(get("/api/payments/{paymentId}", paymentId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("VOIDED"));
  }

  @Test
  void cancelsOrderAndVoidsUncollectedCod() throws Exception {
    createOrder();
    String paymentId = objectMapper.readTree(mockMvc.perform(get("/api/orders/{orderId}/payments", "ZPY-ORD-10001"))
        .andReturn().getResponse().getContentAsString()).get(0).path("paymentId").asText();

    mockMvc.perform(post("/api/orders/{orderId}/cancel", "ZPY-ORD-10001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.order_status").value("CANCELLED"));

    mockMvc.perform(get("/api/payments/{paymentId}", paymentId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("VOIDED"));
  }

  @Test
  void returnsRecentOrderHistory() throws Exception {
    mockMvc.perform(post("/api/orders")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(sampleOrder("MERCHANT-10001"))))
        .andExpect(status().isCreated());

    mockMvc.perform(post("/api/orders")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(sampleOrder("MERCHANT-10002"))))
        .andExpect(status().isCreated());

    mockMvc.perform(get("/api/orders/history").param("limit", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orders.length()").value(2))
        .andExpect(jsonPath("$.orders[0].merchantOrderId").value("MERCHANT-10002"))
        .andExpect(jsonPath("$.orders[1].merchantOrderId").value("MERCHANT-10001"));
  }

  private JsonNode createOrder() throws Exception {
    return objectMapper.readTree(mockMvc.perform(post("/api/orders")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(sampleOrder())))
        .andExpect(status().isCreated())
        .andReturn()
        .getResponse()
        .getContentAsString());
  }

  private JsonNode findQuote(JsonNode orderBody, String carrierCode) {
    for (JsonNode quote : orderBody.path("shippingOptions")) {
      if (carrierCode.equals(quote.path("carrierCode").asText())) {
        return quote;
      }
    }
    throw new IllegalStateException("Quote not found for " + carrierCode);
  }

  private void selectCarrier(JsonNode quote) throws Exception {
    mockMvc.perform(post("/api/orders/{orderId}/select-carrier", "ZPY-ORD-10001")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(Map.of(
                "carrierCode", quote.path("carrierCode").asText(),
                "serviceCode", quote.path("serviceCode").asText(),
                "quotedAmount", quote.path("totalCharge").decimalValue()
            ))))
        .andExpect(status().isOk());
  }

  private Map<String, Object> sampleOrder() {
    return sampleOrder("MERCHANT-10001");
  }

  private Map<String, Object> sampleOrder(String merchantOrderId) {
    Map<String, Object> customer = Map.of(
        "name", "Rahul Sharma",
        "phone", "9876543210",
        "email", "rahul@example.com"
    );
    Map<String, Object> pickupAddress = Map.of(
        "addressLine1", "15 MG Road",
        "city", "Bengaluru",
        "state", "Karnataka",
        "pincode", "560001"
    );
    Map<String, Object> deliveryAddress = Map.of(
        "addressLine1", "22 Connaught Place",
        "city", "New Delhi",
        "state", "Delhi",
        "pincode", "110001"
    );
    Map<String, Object> packageData = Map.of(
        "weightGrams", 1500,
        "lengthCm", 20,
        "widthCm", 15,
        "heightCm", 10
    );
    Map<String, Object> order = new LinkedHashMap<>();
    order.put("merchantOrderId", merchantOrderId);
    order.put("customer", customer);
    order.put("pickupAddress", pickupAddress);
    order.put("deliveryAddress", deliveryAddress);
    order.put("package", packageData);
    order.put("paymentType", "COD");
    order.put("codAmount", 2500);
    return order;
  }
}
