package com.zippy.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "zippy.automation-enabled=false")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ZippyReportingAndPagingTest {
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
  void excludesCancelledShipmentRevenueAndVoidedCodFromReportTotals() throws Exception {
    JsonNode activeOrder = createOrder("REPORT-ACTIVE", 100);
    BigDecimal activeShippingCharge = selectAndCreate(activeOrder, "FASTSHIP");

    JsonNode cancelledOrder = createOrder("REPORT-CANCELLED", 200);
    selectAndCreate(cancelledOrder, "QUICKEXPRESS");
    mockMvc.perform(post("/api/orders/{orderId}/cancel", cancelledOrder.path("zippy_order_id").asText()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.order_status").value("CANCELLED"));

    JsonNode report = objectMapper.readTree(mockMvc.perform(get("/api/reports/summary"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString());
    assertThat(report.path("totalShippingCost").decimalValue())
        .isEqualByComparingTo(activeShippingCharge);
    assertThat(report.path("totalCodValue").decimalValue())
        .isEqualByComparingTo(new BigDecimal("100.00"));
  }

  @Test
  void pagesEventsInSqlAndReturnsBatchPopulatedOrderHistory() throws Exception {
    JsonNode order = createOrder("PAGING-ORDER", 150);
    String orderId = order.path("zippy_order_id").asText();
    selectAndCreate(order, "RELIABLE");
    for (int index = 0; index < 3; index++) {
      mockMvc.perform(post("/api/mock-carriers/{orderId}/advance", orderId))
          .andExpect(status().isOk());
    }
    createOrder("PAGING-ORDER-SECOND", 250);

    mockMvc.perform(get("/api/orders/{orderId}/events", orderId)
            .param("limit", "2")
            .param("offset", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalEvents").value(4))
        .andExpect(jsonPath("$.limit").value(2))
        .andExpect(jsonPath("$.offset").value(1))
        .andExpect(jsonPath("$.events.length()").value(2))
        .andExpect(jsonPath("$.events[0].status").value("PICKED_UP"))
        .andExpect(jsonPath("$.events[1].status").value("IN_TRANSIT"));

    mockMvc.perform(get("/api/orders/history").param("limit", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orders.length()").value(2))
        .andExpect(jsonPath("$.orders[1].zippyOrderId").value(orderId))
        .andExpect(jsonPath("$.orders[1].selectedShipment.carrierCode").value("RELIABLE"));
  }

  private JsonNode createOrder(String merchantOrderId, int codAmount) throws Exception {
    Map<String, Object> request = Map.of(
        "merchantOrderId", merchantOrderId,
        "customer", Map.of(
            "name", "Reporting Test",
            "phone", "9876543210",
            "email", "reporting@example.com"
        ),
        "pickupAddress", Map.of(
            "addressLine1", "1 Origin Road",
            "city", "Mumbai",
            "state", "Maharashtra",
            "pincode", "400001"
        ),
        "deliveryAddress", Map.of(
            "addressLine1", "2 Destination Road",
            "city", "Delhi",
            "state", "Delhi",
            "pincode", "110001"
        ),
        "package", Map.of(
            "weightGrams", 1000,
            "lengthCm", 20,
            "widthCm", 15,
            "heightCm", 10
        ),
        "paymentType", "COD",
        "codAmount", codAmount
    );
    return objectMapper.readTree(mockMvc.perform(post("/api/orders")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString());
  }

  private BigDecimal selectAndCreate(JsonNode order, String carrier) throws Exception {
    JsonNode quote = findQuote(order, carrier);
    String orderId = order.path("zippy_order_id").asText();
    mockMvc.perform(post("/api/orders/{orderId}/select-carrier", orderId)
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(Map.of(
                "carrierCode", quote.path("carrierCode").asText(),
                "serviceCode", quote.path("serviceCode").asText(),
                "quotedAmount", quote.path("totalCharge").decimalValue()
            ))))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/orders/{orderId}/create-shipment", orderId))
        .andExpect(status().isOk());
    return quote.path("totalCharge").decimalValue();
  }

  private JsonNode findQuote(JsonNode order, String carrier) {
    for (JsonNode quote : order.path("shippingOptions")) {
      if (carrier.equals(quote.path("carrierCode").asText())) {
        return quote;
      }
    }
    throw new IllegalStateException("Quote not found for " + carrier);
  }
}
