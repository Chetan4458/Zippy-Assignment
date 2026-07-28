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

  private Map<String, Object> sampleOrder() {
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
    order.put("merchantOrderId", "MERCHANT-10001");
    order.put("customer", customer);
    order.put("pickupAddress", pickupAddress);
    order.put("deliveryAddress", deliveryAddress);
    order.put("package", packageData);
    order.put("paymentType", "COD");
    order.put("codAmount", 2500);
    return order;
  }
}
