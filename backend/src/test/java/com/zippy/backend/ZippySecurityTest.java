package com.zippy.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zippy.backend.security.SecurityHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "zippy.automation-enabled=false",
    "zippy.security.api-key.enabled=true",
    "zippy.security.api-key.value=integration-api-key",
    "zippy.security.webhooks.enabled=true",
    "zippy.security.webhooks.max-age-seconds=300",
    "zippy.security.webhooks.secrets.fastship=fastship-test-secret-32-characters-minimum",
    "zippy.security.webhooks.secrets.quickexpress=quickexpress-test-secret-32-characters",
    "zippy.security.webhooks.secrets.reliable=reliable-test-secret-32-characters-minimum"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ZippySecurityTest {
  private static final String API_KEY = "integration-api-key";
  private static final String FASTSHIP_SECRET = "fastship-test-secret-32-characters-minimum";

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
  void requiresAConstantTimeApiKeyForOperationalRoutes() throws Exception {
    mockMvc.perform(get("/api/system/overview").header("X-Request-Id", "security-401"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("X-Request-Id", "security-401"))
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.message").value("Missing or invalid API key"))
        .andExpect(jsonPath("$.details").isArray());

    mockMvc.perform(get("/api/system/overview").header(SecurityHeaders.API_KEY, "wrong-key"))
        .andExpect(status().isUnauthorized());

    mockMvc.perform(get("/api/system/overview").header(SecurityHeaders.API_KEY, API_KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.supportedCarriers").isArray());
  }

  @Test
  void keepsHealthPublicAndProtectsOtherActuatorEndpoints() throws Exception {
    mockMvc.perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
    mockMvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));

    mockMvc.perform(get("/actuator/info"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/actuator/info").header(SecurityHeaders.API_KEY, API_KEY))
        .andExpect(status().isOk());
    mockMvc.perform(get("/actuator/prometheus").header(SecurityHeaders.API_KEY, API_KEY))
        .andExpect(status().isOk());
  }

  @Test
  void acceptsCorsPreflightForTheApiKeyHeaderFromConfiguredLocalOrigins() throws Exception {
    mockMvc.perform(options("/api/orders")
            .header("Origin", "http://localhost:5174")
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "content-type,x-api-key"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5174"))
        .andExpect(header().string("Access-Control-Allow-Headers", org.hamcrest.Matchers.containsStringIgnoringCase("x-api-key")));
  }

  @Test
  void verifiesValidInvalidAndStaleWebhookSignatures() throws Exception {
    JsonNode order = createCodOrder();
    String orderId = order.path("zippy_order_id").asText();
    JsonNode fastship = findQuote(order, "FASTSHIP");
    String selectionBody = objectMapper.writeValueAsString(Map.of(
        "carrierCode", fastship.path("carrierCode").asText(),
        "serviceCode", fastship.path("serviceCode").asText(),
        "quotedAmount", fastship.path("totalCharge").decimalValue()
    ));
    mockMvc.perform(post("/api/orders/{orderId}/select-carrier", orderId)
            .header(SecurityHeaders.API_KEY, API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(selectionBody))
        .andExpect(status().isOk());
    String createdShipment = mockMvc.perform(post("/api/orders/{orderId}/create-shipment", orderId)
            .header(SecurityHeaders.API_KEY, API_KEY))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    String shipmentId = objectMapper.readTree(createdShipment)
        .path("shipment").path("carrierShipmentId").asText();

    long currentTimestamp = Instant.now().getEpochSecond();
    String eventId = "security-event-valid-1";
    String body = fastshipWebhookBody(shipmentId, eventId, currentTimestamp);

    mockMvc.perform(post("/api/webhooks/fastship")
            .contentType(MediaType.APPLICATION_JSON)
            .header(SecurityHeaders.WEBHOOK_TIMESTAMP, String.valueOf(currentTimestamp))
            .header(SecurityHeaders.WEBHOOK_EVENT_ID, eventId)
            .header(SecurityHeaders.WEBHOOK_SIGNATURE, "sha256=" + "00".repeat(32))
            .content(body))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Invalid or stale webhook signature"));

    String mismatchedHeaderEventId = "security-event-header-mismatch";
    mockMvc.perform(post("/api/webhooks/fastship")
            .contentType(MediaType.APPLICATION_JSON)
            .header(SecurityHeaders.WEBHOOK_TIMESTAMP, String.valueOf(currentTimestamp))
            .header(SecurityHeaders.WEBHOOK_EVENT_ID, mismatchedHeaderEventId)
            .header(SecurityHeaders.WEBHOOK_SIGNATURE,
                sign(FASTSHIP_SECRET, currentTimestamp, mismatchedHeaderEventId, body))
            .content(body))
        .andExpect(status().isUnauthorized());

    long staleTimestamp = currentTimestamp - 301;
    String staleBody = fastshipWebhookBody(shipmentId, "security-event-stale-1", staleTimestamp);
    mockMvc.perform(post("/api/webhooks/fastship")
            .contentType(MediaType.APPLICATION_JSON)
            .header(SecurityHeaders.WEBHOOK_TIMESTAMP, String.valueOf(staleTimestamp))
            .header(SecurityHeaders.WEBHOOK_EVENT_ID, "security-event-stale-1")
            .header(SecurityHeaders.WEBHOOK_SIGNATURE,
                sign(FASTSHIP_SECRET, staleTimestamp, "security-event-stale-1", staleBody))
            .content(staleBody))
        .andExpect(status().isUnauthorized());

    mockMvc.perform(post("/api/webhooks/fastship")
            .contentType(MediaType.APPLICATION_JSON)
            .header(SecurityHeaders.WEBHOOK_TIMESTAMP, String.valueOf(currentTimestamp))
            .header(SecurityHeaders.WEBHOOK_EVENT_ID, eventId)
            .header(SecurityHeaders.WEBHOOK_SIGNATURE, sign(FASTSHIP_SECRET, currentTimestamp, eventId, body))
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PICKED_UP"))
        .andExpect(jsonPath("$.carrierEventId").value(eventId));
  }

  private JsonNode createCodOrder() throws Exception {
    return objectMapper.readTree(mockMvc.perform(post("/api/orders")
            .header(SecurityHeaders.API_KEY, API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(sampleOrder())))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString());
  }

  private JsonNode findQuote(JsonNode order, String carrierCode) {
    for (JsonNode quote : order.path("shippingOptions")) {
      if (carrierCode.equals(quote.path("carrierCode").asText())) {
        return quote;
      }
    }
    throw new IllegalStateException("Quote not found for " + carrierCode);
  }

  private String fastshipWebhookBody(String shipmentId, String eventId, long timestamp) throws Exception {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("event_id", eventId);
    event.put("shipment_id", shipmentId);
    event.put("tracking_number", "ignored-by-fastship-lookup");
    event.put("event_code", "PICKED_UP");
    event.put("event_description", "Secure pickup event");
    event.put("event_time", Instant.ofEpochSecond(timestamp).toString());
    event.put("location", "Signed Hub");
    return objectMapper.writeValueAsString(event);
  }

  private String sign(String secret, long timestamp, String eventId, String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    mac.update((timestamp + "." + eventId + ".").getBytes(StandardCharsets.UTF_8));
    return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }

  private Map<String, Object> sampleOrder() {
    return Map.of(
        "merchantOrderId", "SECURITY-ORDER-1",
        "customer", Map.of(
            "name", "Security Test",
            "phone", "9876543210",
            "email", "security@example.com"
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
        "codAmount", 499
    );
  }
}
