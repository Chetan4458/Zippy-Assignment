package com.zippy.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zippy.backend.dto.CarrierSelectionRequest;
import com.zippy.backend.dto.CreatePaymentIntentRequest;
import com.zippy.backend.dto.OrderCreateRequest;
import com.zippy.backend.dto.PaymentIntentResponse;
import com.zippy.backend.dto.PaymentFailureRequest;
import com.zippy.backend.exception.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ZippyService {
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
  private static final String STATUS_ORDER_CREATED = "ORDER_CREATED";
  private static final String STATUS_CARRIER_SELECTED = "CARRIER_SELECTED";
  private static final String STATUS_SHIPMENT_CREATED = "SHIPMENT_CREATED";
  private static final String STATUS_PICKED_UP = "PICKED_UP";
  private static final String STATUS_IN_TRANSIT = "IN_TRANSIT";
  private static final String STATUS_OUT_FOR_DELIVERY = "OUT_FOR_DELIVERY";
  private static final String STATUS_DELIVERED = "DELIVERED";
  private static final String STATUS_DELIVERY_FAILED = "DELIVERY_FAILED";
  private static final String STATUS_RTO = "RTO";
  private static final String STATUS_CANCELLED = "CANCELLED";
  private static final long DEFAULT_CARRIER_TIMEOUT_MS = 1500L;
  private static final long DEFAULT_AUTOMATION_DELAY_MS = 1500L;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final ExecutorService carrierExecutor;
  private final boolean automationEnabled;
  private final AtomicLong fastshipRateDelayMs = new AtomicLong(0);
  private final AtomicLong quickexpressRateDelayMs = new AtomicLong(0);
  private final AtomicLong reliableRateDelayMs = new AtomicLong(0);
  private final AtomicBoolean fastshipRateFailure = new AtomicBoolean(false);
  private final AtomicBoolean quickexpressRateFailure = new AtomicBoolean(false);
  private final AtomicBoolean reliableRateFailure = new AtomicBoolean(false);
  private final Object sequenceLock = new Object();

  public ZippyService(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      ExecutorService carrierExecutor,
      @Value("${zippy.automation-enabled:true}") boolean automationEnabled
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.carrierExecutor = carrierExecutor;
    this.automationEnabled = automationEnabled;
  }

  @Transactional
  public Map<String, Object> createOrder(OrderCreateRequest request, String idempotencyKey) {
    String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
    String requestHash = normalizedKey == null ? null : hashOrderRequest(request);
    if (normalizedKey != null) {
      IdempotencyRecord existing = findIdempotencyRecord(normalizedKey);
      if (existing != null) {
        if (!Objects.equals(existing.requestHash(), requestHash)) {
          throw new ApiException(409, "Idempotency key reused with a different request");
        }
        return parseMap(existing.responseJson());
      }
    }

    validateOrder(request);
    String now = now();
    String orderId = nextOrderId();
    BigDecimal codAmount = "COD".equalsIgnoreCase(request.paymentType()) ? defaultDecimal(request.codAmount()) : null;

    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      var statement = connection.prepareStatement("""
          INSERT INTO orders (
            zippy_order_id, merchant_order_id, customer_name, customer_phone, customer_email,
            pickup_address_json, delivery_address_json, pickup_pincode, delivery_pincode,
            weight_grams, length_cm, width_cm, height_cm, payment_type, cod_amount,
            order_status, created_at, updated_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """, new String[]{"id"});
      statement.setString(1, orderId);
      statement.setString(2, request.merchantOrderId());
      statement.setString(3, request.customer().name());
      statement.setString(4, request.customer().phone());
      statement.setString(5, request.customer().email());
      statement.setString(6, toJson(request.pickupAddress()));
      statement.setString(7, toJson(request.deliveryAddress()));
      statement.setString(8, request.pickupAddress().pincode());
      statement.setString(9, request.deliveryAddress().pincode());
      statement.setInt(10, request.packageDetails().weightGrams().intValue());
      statement.setBigDecimal(11, request.packageDetails().lengthCm());
      statement.setBigDecimal(12, request.packageDetails().widthCm());
      statement.setBigDecimal(13, request.packageDetails().heightCm());
      statement.setString(14, request.paymentType().toUpperCase());
      if (codAmount == null) {
        statement.setNull(15, java.sql.Types.DECIMAL);
      } else {
        statement.setBigDecimal(15, codAmount);
      }
      statement.setString(16, STATUS_ORDER_CREATED);
      statement.setString(17, now);
      statement.setString(18, now);
      return statement;
    }, keyHolder);

    OrderRow order = findOrderByZippyId(orderId);
    if ("COD".equalsIgnoreCase(order.paymentType())) {
      createCodPayment(order, now);
    }
    List<ShippingQuote> quotes = getCarrierQuotes(order);
    insertQuotes(order, quotes, now);
    Map<String, Object> response = getOrder(orderId);
    if (normalizedKey != null) {
      storeIdempotencyRecord(normalizedKey, requestHash, response, now);
    }
    return response;
  }

  public Map<String, Object> createOrder(OrderCreateRequest request) {
    return createOrder(request, null);
  }

  public Map<String, Object> getOrder(String orderId) {
    OrderRow order = findOrderByZippyId(orderId);
    List<ShippingQuote> quotes = listQuotes(order.id());
    ShipmentRow shipment = findShipment(order.id());
    List<ShipmentEventRow> events = shipment == null ? List.of() : listEvents(shipment.id());
    return buildOrderResponse(order, quotes, shipment, events);
  }

  public Map<String, Object> getTracking(String orderId) {
    return getOrder(orderId);
  }

  public Map<String, Object> getOrderHistory(int limit, int offset) {
    int safeLimit = Math.max(1, Math.min(limit, 50));
    int safeOffset = Math.max(0, offset);
    List<OrderRow> orders = jdbcTemplate.query("""
        SELECT * FROM orders
        ORDER BY id DESC
        LIMIT ? OFFSET ?
        """, orderRowMapper, safeLimit, safeOffset);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("limit", safeLimit);
    response.put("offset", safeOffset);
    response.put("totalOrders", countRows("orders"));
    response.put("orders", orders.stream().map(this::orderHistoryToMap).toList());
    return response;
  }

  public Map<String, Object> getShipmentEvents(String orderId, int limit, int offset) {
    OrderRow order = findOrderByZippyId(orderId);
    ShipmentRow shipment = findShipment(order.id());
    List<ShipmentEventRow> allEvents = shipment == null ? List.of() : listEvents(shipment.id());
    List<ShipmentEventRow> page = pageItems(allEvents, limit, offset);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("orderId", orderId);
    response.put("totalEvents", allEvents.size());
    response.put("limit", Math.max(0, limit));
    response.put("offset", Math.max(0, offset));
    response.put("events", page.stream().map(this::eventToMap).toList());
    return response;
  }

  public Map<String, Object> getSystemOverview() {
    Map<String, Object> overview = new LinkedHashMap<>();
    overview.put("orders", countRows("orders"));
    overview.put("quotes", countRows("shipping_quotes"));
    overview.put("shipments", countRows("shipments"));
    overview.put("events", countRows("shipment_events"));
    overview.put("payments", countRows("payments"));
    overview.put("automationEnabled", automationEnabled);
    overview.put("carrierTimeoutMs", DEFAULT_CARRIER_TIMEOUT_MS);
    overview.put("runtimeFlags", getRuntimeFlags());
    overview.put("supportedCarriers", List.of("FASTSHIP", "QUICKEXPRESS", "RELIABLE"));
    return overview;
  }

  public Map<String, Object> getReportsSummary() {
    Map<String, Object> summary = new LinkedHashMap<>();

    summary.put("totalOrders", countRows("orders"));
    summary.put("totalShipments", countRows("shipments"));
    summary.put("totalPayments", countRows("payments"));

    summary.put("ordersByStatus", jdbcTemplate.query("""
        SELECT order_status AS label, COUNT(*) AS count
        FROM orders
        GROUP BY order_status
        ORDER BY count DESC
        """, (rs, rowNum) -> Map.of(
        "label", rs.getString("label"),
        "count", rs.getLong("count")
    )));

    summary.put("ordersByPaymentType", jdbcTemplate.query("""
        SELECT payment_type AS label, COUNT(*) AS count
        FROM orders
        GROUP BY payment_type
        ORDER BY count DESC
        """, (rs, rowNum) -> Map.of(
        "label", rs.getString("label"),
        "count", rs.getLong("count")
    )));

    BigDecimal shippingRevenue = jdbcTemplate.queryForObject(
        "SELECT COALESCE(SUM(quoted_amount), 0) FROM shipments", BigDecimal.class);
    summary.put("totalShippingRevenue", defaultDecimal(shippingRevenue));

    BigDecimal codValue = jdbcTemplate.queryForObject(
        "SELECT COALESCE(SUM(cod_amount), 0) FROM orders WHERE payment_type = 'COD'", BigDecimal.class);
    summary.put("totalCodValue", defaultDecimal(codValue));

    summary.put("carrierBreakdown", jdbcTemplate.query("""
        SELECT carrier_code AS carrier, COUNT(*) AS shipments, COALESCE(SUM(quoted_amount), 0) AS revenue
        FROM shipments
        GROUP BY carrier_code
        ORDER BY shipments DESC
        """, (rs, rowNum) -> {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("carrier", rs.getString("carrier"));
      row.put("shipments", rs.getLong("shipments"));
      row.put("revenue", defaultDecimal(rs.getBigDecimal("revenue")));
      return row;
    }));

    summary.put("paymentsByStatus", jdbcTemplate.query("""
        SELECT status AS label, COUNT(*) AS count, COALESCE(SUM(amount), 0) AS totalAmount
        FROM payments
        GROUP BY status
        ORDER BY count DESC
        """, (rs, rowNum) -> {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("label", rs.getString("label"));
      row.put("count", rs.getLong("count"));
      row.put("totalAmount", defaultDecimal(rs.getBigDecimal("totalAmount")));
      return row;
    }));

    BigDecimal succeededAmount = jdbcTemplate.queryForObject(
        "SELECT COALESCE(SUM(amount), 0) FROM payments WHERE status = 'SUCCEEDED'", BigDecimal.class);
    summary.put("totalPaymentCollected", defaultDecimal(succeededAmount));

    return summary;
  }

  public Map<String, Object> getPaymentHistory(int limit, int offset) {
    int safeLimit = Math.max(1, Math.min(limit, 50));
    int safeOffset = Math.max(0, offset);
    List<PaymentRow> payments = jdbcTemplate.query("""
        SELECT * FROM payments
        ORDER BY id DESC
        LIMIT ? OFFSET ?
        """, paymentRowMapper, safeLimit, safeOffset);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("limit", safeLimit);
    response.put("offset", safeOffset);
    response.put("totalPayments", countRows("payments"));
    response.put("payments", payments.stream().map(this::paymentToMap).toList());
    return response;
  }

  public Map<String, Object> getRates(String orderId, String sortBy) {
    OrderRow order = findOrderByZippyId(orderId);
    return Map.of(
        "orderId", orderId,
        "shippingOptions", sortQuotes(listQuotes(order.id()), sortBy).stream().map(this::quoteToMap).toList()
    );
  }

  @Transactional
  public Map<String, Object> selectCarrier(String orderId, CarrierSelectionRequest request) {
    OrderRow order = findOrderByZippyId(orderId);
    ShippingQuote quote = findQuote(order.id(), request.carrierCode(), request.serviceCode());
    if (quote.totalCharge().compareTo(request.quotedAmount().setScale(2, RoundingMode.HALF_UP)) != 0) {
      throw new ApiException(409, "Quoted amount does not match stored quote");
    }

    String now = now();
    String selectedQuoteJson = toJson(quoteToMap(quote));
    ShipmentRow existingShipment = findShipment(order.id());
    if (existingShipment != null && !STATUS_CARRIER_SELECTED.equals(existingShipment.currentStatus())) {
      throw new ApiException(409, "Carrier cannot be changed after shipment creation");
    }
    if (existingShipment == null) {
      jdbcTemplate.update("""
          INSERT INTO shipments (
            order_id, carrier_code, carrier_shipment_id, tracking_number,
            selected_service_code, quoted_amount, selected_quote_json,
            current_status, selection_timestamp, created_at, updated_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          order.id(), quote.carrierCode(), null, null, quote.serviceCode(),
          quote.totalCharge(), selectedQuoteJson, STATUS_CARRIER_SELECTED, now, now, now);
    } else {
      jdbcTemplate.update("""
          UPDATE shipments
          SET carrier_code = ?, selected_service_code = ?, quoted_amount = ?, selected_quote_json = ?,
              current_status = ?, selection_timestamp = ?, updated_at = ?
          WHERE id = ?
          """,
          quote.carrierCode(), quote.serviceCode(), quote.totalCharge(), selectedQuoteJson,
          STATUS_CARRIER_SELECTED, now, now, existingShipment.id());
    }

    jdbcTemplate.update("UPDATE orders SET order_status = ?, updated_at = ? WHERE id = ?",
        STATUS_CARRIER_SELECTED, now, order.id());
    ShipmentRow shipment = findShipment(order.id());
    return Map.of(
        "orderId", orderId,
        "selectedShipment", Map.of(
            "carrierCode", shipment.carrierCode(),
            "selectedServiceCode", shipment.selectedServiceCode(),
            "quotedAmount", shipment.quotedAmount(),
            "currentStatus", shipment.currentStatus()
        )
    );
  }

  @Transactional
  public Map<String, Object> createShipment(String orderId) {
    OrderRow order = findOrderByZippyOrderIdForUpdate(orderId);
    ShipmentRow shipment = findShipment(order.id());
    if (shipment == null) {
      throw new ApiException(409, "Carrier not selected");
    }
    if (!STATUS_CARRIER_SELECTED.equals(shipment.currentStatus())) {
      throw new ApiException(409, "Shipment has already been created or is not ready for creation");
    }

    ShippingQuote quote = parseQuote(shipment.selectedQuoteJson());
    Map<String, Object> carrierResponse = switch (shipment.carrierCode()) {
      case "FASTSHIP" -> fastShipCreateShipment(order, quote);
      case "QUICKEXPRESS" -> quickExpressCreateShipment(order, quote);
      default -> reliableShipment(order, quote);
    };

    String now = now();
    if ("FASTSHIP".equals(shipment.carrierCode())) {
      jdbcTemplate.update("""
          UPDATE shipments SET carrier_shipment_id = ?, tracking_number = ?, current_status = ?, updated_at = ?
          WHERE id = ?
          """, stringValue(carrierResponse, "shipment_id"), stringValue(carrierResponse, "tracking_number"), STATUS_SHIPMENT_CREATED, now, shipment.id());
    } else if ("QUICKEXPRESS".equals(shipment.carrierCode())) {
      Map<String, Object> booking = mapValue(carrierResponse, "booking");
      jdbcTemplate.update("""
          UPDATE shipments SET carrier_shipment_id = ?, tracking_number = ?, current_status = ?, updated_at = ?
          WHERE id = ?
          """, stringValue(booking, "bookingId"), stringValue(booking, "awb"), STATUS_SHIPMENT_CREATED, now, shipment.id());
    } else {
      Map<String, Object> deliveryOrder = mapValue(carrierResponse, "deliveryOrder");
      jdbcTemplate.update("""
          UPDATE shipments SET carrier_shipment_id = ?, tracking_number = ?, current_status = ?, updated_at = ?
          WHERE id = ?
          """, stringValue(deliveryOrder, "id"), stringValue(deliveryOrder, "trackingCode"), STATUS_SHIPMENT_CREATED, now, shipment.id());
    }
    jdbcTemplate.update("UPDATE orders SET order_status = ?, updated_at = ? WHERE id = ?",
        STATUS_SHIPMENT_CREATED, now, order.id());
    ShipmentRow updatedShipment = findShipment(order.id());
    recordCarrierEvent(updatedShipment, initialWebhookPayloadJson(updatedShipment, order), now);
    if (automationEnabled) {
      scheduleAutomation(updatedShipment.id(), updatedShipment.carrierCode());
    }
    return Map.of(
        "orderId", orderId,
        "shipment", Map.of(
            "carrierCode", updatedShipment.carrierCode(),
            "carrierShipmentId", updatedShipment.carrierShipmentId(),
            "trackingNumber", updatedShipment.trackingNumber(),
            "currentStatus", updatedShipment.currentStatus()
        ),
        "carrierResponse", carrierResponse
    );
  }

  @Transactional
  public Map<String, Object> cancelOrder(String orderId) {
    OrderRow order = findOrderByZippyOrderIdForUpdate(orderId);
    ShipmentRow shipment = findShipment(order.id());
    String currentStatus = shipment == null ? order.orderStatus() : shipment.currentStatus();
    if (!isCancellableStatus(currentStatus)) {
      throw new ApiException(409, "Order cannot be cancelled from status " + currentStatus);
    }

    String timestamp = now();
    if (shipment != null) {
      jdbcTemplate.update("UPDATE shipments SET current_status = ?, updated_at = ? WHERE id = ?",
          STATUS_CANCELLED, timestamp, shipment.id());
      jdbcTemplate.update("""
          INSERT INTO shipment_events (
            shipment_id, carrier_event_id, carrier_status, normalized_status, description,
            location, event_time, raw_event_payload, received_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          shipment.id(), "ZIPPY-CANCEL-" + UUID.randomUUID(), "CANCELLED", STATUS_CANCELLED,
          "Order cancelled by operations", null, timestamp, "{\"source\":\"zippy\"}", timestamp);
    }
    jdbcTemplate.update("UPDATE orders SET order_status = ?, updated_at = ? WHERE id = ?",
        STATUS_CANCELLED, timestamp, order.id());
    settlePaymentsForTerminalOrder(order.id(), "CANCELLED");
    return getOrder(orderId);
  }

  private boolean isCancellableStatus(String status) {
    return STATUS_ORDER_CREATED.equals(status)
        || STATUS_CARRIER_SELECTED.equals(status)
        || STATUS_SHIPMENT_CREATED.equals(status);
  }

  public Map<String, Object> handleWebhook(String carrierCode, JsonNode payload) {
    String normalizedCarrier = carrierCode == null ? "" : carrierCode.trim().toUpperCase();
    if (!List.of("FASTSHIP", "QUICKEXPRESS", "RELIABLE").contains(normalizedCarrier)) {
      throw new ApiException(404, "Unsupported carrier webhook: " + carrierCode);
    }

    CarrierEvent event = mapWebhook(normalizedCarrier, payload);
    ShipmentRow shipment = findShipmentByTracking(event.trackingKey(), event.trackingField());
    if (shipment == null) {
      throw new ApiException(404, "Unknown tracking number");
    }
    return recordCarrierEvent(shipment, payload, now(), event);
  }

  public Map<String, Object> advanceMockCarrier(String orderId) {
    ShipmentRow shipment = requireShipment(orderId);
    String nextStatus = nextAutomatedStatus(shipment.currentStatus());
    if (nextStatus == null) {
      return Map.of("status", shipment.currentStatus(), "advanced", false);
    }
    JsonNode payload = buildWebhookPayload(shipment, nextStatus);
    return handleWebhook(shipment.carrierCode(), payload);
  }

  public Map<String, Object> mockDeliveryFailure(String orderId) {
    ShipmentRow shipment = requireShipment(orderId);
    if (!STATUS_OUT_FOR_DELIVERY.equals(shipment.currentStatus())) {
      throw new ApiException(409, "Delivery failure can only be simulated from OUT_FOR_DELIVERY");
    }
    return handleWebhook(shipment.carrierCode(), buildWebhookPayload(shipment, STATUS_DELIVERY_FAILED));
  }

  public Map<String, Object> mockRto(String orderId) {
    ShipmentRow shipment = requireShipment(orderId);
    if (!STATUS_DELIVERY_FAILED.equals(shipment.currentStatus())) {
      throw new ApiException(409, "RTO can only be simulated after DELIVERY_FAILED");
    }
    return handleWebhook(shipment.carrierCode(), buildWebhookPayload(shipment, STATUS_RTO));
  }

  public Map<String, Object> mockFastshipRate(JsonNode payload) {
    OrderContext order = orderContext(
        Math.round(payload.path("weight_kg").asDouble(0) * 1000.0),
        payload.path("payment_mode").asText("PREPAID"),
        payload.path("invoice_value").decimalValue()
    );
    return fastShipRate(order);
  }

  public Map<String, Object> mockQuickexpressRate(JsonNode payload) {
    OrderContext order = orderContext(
        payload.path("weightInGrams").asLong(0),
        payload.path("isCod").asBoolean(false) ? "COD" : "PREPAID",
        payload.path("collectableAmount").decimalValue()
    );
    return quickExpressRate(order);
  }

  public Map<String, Object> mockReliableOptions(Map<String, String> params) {
    OrderContext order = orderContext(
        Long.parseLong(params.getOrDefault("weight", "0")),
        Boolean.parseBoolean(params.getOrDefault("cod", "false")) ? "COD" : "PREPAID",
        new BigDecimal(params.getOrDefault("amount", "0"))
    );
    return reliableRates(order);
  }

  public Map<String, Object> mockFastshipShipment(JsonNode payload) {
    return Map.of(
        "success", true,
        "shipment_id", "FS-700001",
        "tracking_number", "FST123456789",
        "label_url", "http://mock-fastship/labels/FST123456789.pdf",
        "status", "BOOKED"
    );
  }

  public Map<String, Object> mockQuickexpressShipment(JsonNode payload) {
    return Map.of(
        "bookingStatus", "CONFIRMED",
        "booking", Map.of(
            "bookingId", "QE-B-800001",
            "awb", "QE987654321",
            "currentState", "SHIPMENT_CREATED"
        )
    );
  }

  public Map<String, Object> mockReliableShipment(JsonNode payload) {
    return Map.of(
        "result", "ACCEPTED",
        "deliveryOrder", Map.of(
            "id", "RC-DO-600001",
            "trackingCode", "RC1122334455"
        ),
        "message", "Shipment successfully registered"
    );
  }

  public void setRuntimeFlags(Map<String, Object> payload) {
    if (payload.containsKey("fastshipRateDelayMs")) {
      fastshipRateDelayMs.set(longValue(payload.get("fastshipRateDelayMs")));
    }
    if (payload.containsKey("quickexpressRateDelayMs")) {
      quickexpressRateDelayMs.set(longValue(payload.get("quickexpressRateDelayMs")));
    }
    if (payload.containsKey("reliableRateDelayMs")) {
      reliableRateDelayMs.set(longValue(payload.get("reliableRateDelayMs")));
    }
    if (payload.containsKey("fastshipRateFailure")) {
      fastshipRateFailure.set(booleanValue(payload.get("fastshipRateFailure")));
    }
    if (payload.containsKey("quickexpressRateFailure")) {
      quickexpressRateFailure.set(booleanValue(payload.get("quickexpressRateFailure")));
    }
    if (payload.containsKey("reliableRateFailure")) {
      reliableRateFailure.set(booleanValue(payload.get("reliableRateFailure")));
    }
  }

  public void resetRuntimeFlags() {
    fastshipRateDelayMs.set(0);
    quickexpressRateDelayMs.set(0);
    reliableRateDelayMs.set(0);
    fastshipRateFailure.set(false);
    quickexpressRateFailure.set(false);
    reliableRateFailure.set(false);
  }

  public Map<String, Object> getRuntimeFlags() {
    Map<String, Object> flags = new LinkedHashMap<>();
    flags.put("fastshipRateDelayMs", fastshipRateDelayMs.get());
    flags.put("quickexpressRateDelayMs", quickexpressRateDelayMs.get());
    flags.put("reliableRateDelayMs", reliableRateDelayMs.get());
    flags.put("fastshipRateFailure", fastshipRateFailure.get());
    flags.put("quickexpressRateFailure", quickexpressRateFailure.get());
    flags.put("reliableRateFailure", reliableRateFailure.get());
    return flags;
  }

  private void validateOrder(OrderCreateRequest request) {
    List<String> issues = new ArrayList<>();
    if (request.merchantOrderId() == null || request.merchantOrderId().isBlank()) {
      issues.add("merchantOrderId is required");
    }
    if (request.customer() == null) {
      issues.add("customer is required");
    }
    if (request.pickupAddress() == null) {
      issues.add("pickupAddress is required");
    }
    if (request.deliveryAddress() == null) {
      issues.add("deliveryAddress is required");
    }
    if (request.packageDetails() == null) {
      issues.add("package is required");
    }
    if (request.paymentType() == null || request.paymentType().isBlank()) {
      issues.add("paymentType is required");
    }
    if (request.customer() != null) {
      if (request.customer().name() == null || request.customer().name().isBlank()) {
        issues.add("customer.name is required");
      }
      if (request.customer().phone() == null || request.customer().phone().isBlank()) {
        issues.add("customer.phone is required");
      }
      if (request.customer().email() == null || request.customer().email().isBlank()) {
        issues.add("customer.email is required");
      }
    }
    if (request.pickupAddress() != null && (request.pickupAddress().pincode() == null || request.pickupAddress().pincode().isBlank())) {
      issues.add("pickupAddress.pincode is required");
    }
    if (request.deliveryAddress() != null && (request.deliveryAddress().pincode() == null || request.deliveryAddress().pincode().isBlank())) {
      issues.add("deliveryAddress.pincode is required");
    }
    if (request.packageDetails() != null && request.packageDetails().weightGrams() != null && request.packageDetails().weightGrams().compareTo(BigDecimal.ZERO) <= 0) {
      issues.add("package.weightGrams must be greater than 0");
    }
    if ("COD".equalsIgnoreCase(request.paymentType()) && (request.codAmount() == null || request.codAmount().compareTo(BigDecimal.ZERO) <= 0)) {
      issues.add("codAmount is required for COD orders");
    }
    if (!issues.isEmpty()) {
      throw new ApiException(422, "Invalid order payload", issues);
    }
  }

  private Map<String, Object> buildOrderResponse(OrderRow order, List<ShippingQuote> quotes, ShipmentRow shipment, List<ShipmentEventRow> events) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("zippy_order_id", order.zippyOrderId());
    response.put("merchant_order_id", order.merchantOrderId());
    response.put("customer_name", order.customerName());
    response.put("customer_phone", order.customerPhone());
    response.put("customer_email", order.customerEmail());
    response.put("pickupAddress", parseMap(order.pickupAddressJson()));
    response.put("deliveryAddress", parseMap(order.deliveryAddressJson()));
    response.put("pickup_pincode", order.pickupPincode());
    response.put("delivery_pincode", order.deliveryPincode());
    response.put("weight_grams", order.weightGrams());
    response.put("length_cm", order.lengthCm());
    response.put("width_cm", order.widthCm());
    response.put("height_cm", order.heightCm());
    response.put("payment_type", order.paymentType());
    response.put("cod_amount", order.codAmount());
    response.put("order_status", order.orderStatus());
    response.put("created_at", order.createdAt());
    response.put("updated_at", order.updatedAt());
    response.put("shippingOptions", sortQuotes(quotes, "lowest").stream().map(this::quoteToMap).toList());
    response.put("selectedShipment", shipment == null ? null : shipmentToMap(shipment));
    response.put("shipmentEvents", events.stream().map(this::eventToMap).toList());
    return response;
  }

  private List<ShippingQuote> getCarrierQuotes(OrderRow order) {
    List<CompletableFuture<ShippingQuote>> futures = List.of(
        CompletableFuture.supplyAsync(() -> {
          sleep(fastshipRateDelayMs.get());
          if (fastshipRateFailure.get()) {
            throw new IllegalStateException("FastShip rate service unavailable");
          }
          return fastShipQuote(order);
        }, carrierExecutor),
        CompletableFuture.supplyAsync(() -> {
          sleep(quickexpressRateDelayMs.get());
          if (quickexpressRateFailure.get()) {
            throw new IllegalStateException("QuickExpress rate service unavailable");
          }
          return quickExpressQuote(order);
        }, carrierExecutor),
        CompletableFuture.supplyAsync(() -> {
          sleep(reliableRateDelayMs.get());
          if (reliableRateFailure.get()) {
            throw new IllegalStateException("ReliableCourier rate service unavailable");
          }
          return reliableQuote(order);
        }, carrierExecutor)
    );

    List<ShippingQuote> quotes = new ArrayList<>();
    for (CompletableFuture<ShippingQuote> future : futures) {
      try {
        quotes.add(future.orTimeout(DEFAULT_CARRIER_TIMEOUT_MS, TimeUnit.MILLISECONDS).join());
      } catch (Exception ignored) {
      }
    }
    return sortQuotes(quotes, "lowest");
  }

  private String nextAutomatedStatus(String currentStatus) {
    return switch (currentStatus) {
      case STATUS_SHIPMENT_CREATED -> STATUS_PICKED_UP;
      case STATUS_PICKED_UP -> STATUS_IN_TRANSIT;
      case STATUS_IN_TRANSIT -> STATUS_OUT_FOR_DELIVERY;
      case STATUS_OUT_FOR_DELIVERY -> STATUS_DELIVERED;
      case STATUS_DELIVERY_FAILED -> STATUS_IN_TRANSIT;
      default -> null;
    };
  }

  private void insertQuotes(OrderRow order, List<ShippingQuote> quotes, String now) {
    for (ShippingQuote quote : quotes) {
      jdbcTemplate.update("""
          INSERT INTO shipping_quotes (
            order_id, carrier_code, carrier_name, service_code, service_name,
            base_charge, cod_charge, additional_charges, tax, total_charge,
            estimated_min_days, estimated_max_days, raw_carrier_response, created_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          order.id(), quote.carrierCode(), quote.carrierName(), quote.serviceCode(), quote.serviceName(),
          quote.baseCharge(), quote.codCharge(), quote.additionalCharges(), quote.tax(), quote.totalCharge(),
          quote.estimatedMinDays(), quote.estimatedMaxDays(), toJson(quote.rawCarrierResponse()), now);
    }
  }

  private List<ShippingQuote> listQuotes(long orderId) {
    return jdbcTemplate.query("""
        SELECT * FROM shipping_quotes WHERE order_id = ? ORDER BY total_charge ASC, carrier_name ASC
        """, shippingQuoteRowMapper, orderId);
  }

  private ShippingQuote findQuote(long orderId, String carrierCode, String serviceCode) {
    try {
      return jdbcTemplate.queryForObject("""
          SELECT * FROM shipping_quotes WHERE order_id = ? AND carrier_code = ? AND service_code = ?
          """, shippingQuoteRowMapper, orderId, carrierCode, serviceCode);
    } catch (Exception exception) {
      throw new ApiException(404, "Carrier quote not found");
    }
  }

  private ShipmentRow findShipment(long orderId) {
    List<ShipmentRow> shipments = jdbcTemplate.query("SELECT * FROM shipments WHERE order_id = ?", shipmentRowMapper, orderId);
    return shipments.isEmpty() ? null : shipments.getFirst();
  }

  private ShipmentRow requireShipment(String orderId) {
    OrderRow order = findOrderByZippyId(orderId);
    ShipmentRow shipment = findShipment(order.id());
    if (shipment == null) {
      throw new ApiException(409, "Shipment not created yet");
    }
    return shipment;
  }

  private List<ShipmentEventRow> listEvents(long shipmentId) {
    return jdbcTemplate.query("SELECT * FROM shipment_events WHERE shipment_id = ? ORDER BY event_time ASC, id ASC", shipmentEventRowMapper, shipmentId);
  }

  private <T> List<T> pageItems(List<T> items, int limit, int offset) {
    int safeLimit = Math.max(0, limit);
    int safeOffset = Math.max(0, offset);
    if (safeLimit == 0 || safeOffset >= items.size()) {
      return List.of();
    }
    int end = Math.min(items.size(), safeOffset + safeLimit);
    return new ArrayList<>(items.subList(safeOffset, end));
  }

  private long countRows(String table) {
    Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    return count == null ? 0L : count;
  }

  private Map<String, Object> orderHistoryToMap(OrderRow order) {
    ShipmentRow shipment = findShipment(order.id());
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("zippyOrderId", order.zippyOrderId());
    response.put("merchantOrderId", order.merchantOrderId());
    response.put("orderStatus", order.orderStatus());
    response.put("paymentType", order.paymentType());
    response.put("codAmount", order.codAmount());
    response.put("createdAt", order.createdAt());
    response.put("updatedAt", order.updatedAt());
    response.put("pickupPincode", order.pickupPincode());
    response.put("deliveryPincode", order.deliveryPincode());
    response.put("selectedShipment", shipment == null ? null : Map.of(
        "carrierCode", shipment.carrierCode(),
        "selectedServiceCode", shipment.selectedServiceCode(),
        "trackingNumber", shipment.trackingNumber(),
        "quotedAmount", shipment.quotedAmount(),
        "currentStatus", shipment.currentStatus()
    ));
    return response;
  }

  private IdempotencyRecord findIdempotencyRecord(String idempotencyKey) {
    try {
      return jdbcTemplate.queryForObject(
          "SELECT * FROM idempotency_keys WHERE idempotency_key = ?",
          idempotencyRowMapper,
          idempotencyKey
      );
    } catch (Exception exception) {
      return null;
    }
  }

  private void storeIdempotencyRecord(String idempotencyKey, String requestHash, Map<String, Object> response, String now) {
    jdbcTemplate.update("""
        MERGE INTO idempotency_keys (idempotency_key, request_hash, response_json, created_at)
        KEY(idempotency_key)
        VALUES (?, ?, ?, ?)
        """, idempotencyKey, requestHash, toJson(response), now);
  }

  private Map<String, Object> recordCarrierEvent(ShipmentRow shipment, JsonNode payload, String receivedAt) {
    CarrierEvent event = mapWebhook(shipment.carrierCode(), payload);
    return recordCarrierEvent(shipment, payload, receivedAt, event);
  }

  private Map<String, Object> recordCarrierEvent(ShipmentRow shipment, JsonNode payload, String receivedAt, CarrierEvent event) {
    if (event.normalizedStatus() == null) {
      throw new ApiException(422, "Unsupported status from " + shipment.carrierCode());
    }

    ShipmentRow currentShipment = findShipment(shipment.orderId());
    if (!allowedTransition(currentShipment.currentStatus(), event.normalizedStatus())) {
      if (!Objects.equals(currentShipment.currentStatus(), event.normalizedStatus())) {
        throw new ApiException(409, "Invalid status transition from " + currentShipment.currentStatus() + " to " + event.normalizedStatus());
      }
    }

    try {
      jdbcTemplate.update("""
          INSERT INTO shipment_events (
            shipment_id, carrier_event_id, carrier_status, normalized_status, description,
            location, event_time, raw_event_payload, received_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          currentShipment.id(), event.carrierEventId(), event.carrierStatus(), event.normalizedStatus(), event.description(),
          event.location(), event.eventTime(), toJson(payload), receivedAt);
    } catch (DuplicateKeyException duplicate) {
        return Map.of(
            "duplicate", true,
            "status", currentShipment.currentStatus(),
            "carrierEventId", event.carrierEventId()
        );
    }

    jdbcTemplate.update("UPDATE shipments SET current_status = ?, updated_at = ? WHERE id = ?",
        event.normalizedStatus(), receivedAt, currentShipment.id());
    jdbcTemplate.update("UPDATE orders SET order_status = ?, updated_at = ? WHERE id = ?",
        event.normalizedStatus(), receivedAt, currentShipment.orderId());
    settlePaymentsForTerminalOrder(currentShipment.orderId(), event.normalizedStatus());
    return Map.of(
        "duplicate", false,
        "status", event.normalizedStatus(),
        "carrierEventId", event.carrierEventId()
    );
  }

  private void settlePaymentsForTerminalOrder(long orderId, String shipmentStatus) {
    if (STATUS_DELIVERY_FAILED.equals(shipmentStatus)) {
      return;
    }

    if (STATUS_RTO.equals(shipmentStatus)) {
      jdbcTemplate.update("""
          UPDATE payments
          SET status = CASE
                WHEN payment_method = 'COD' AND status = 'AWAITING_COLLECTION' THEN 'VOIDED'
                WHEN payment_method = 'PREPAID' AND status = 'PENDING' THEN 'CANCELLED'
                WHEN payment_method = 'PREPAID' AND status = 'SUCCEEDED' THEN 'REFUND_PENDING'
                ELSE status
              END,
              updated_at = ?
          WHERE order_id = ?
            AND status IN ('AWAITING_COLLECTION', 'PENDING', 'SUCCEEDED')
          """, now(), orderId);
    } else if (STATUS_CANCELLED.equals(shipmentStatus)) {
      jdbcTemplate.update("""
          UPDATE payments
          SET status = CASE
                WHEN payment_method = 'COD' AND status = 'AWAITING_COLLECTION' THEN 'VOIDED'
                WHEN payment_method = 'PREPAID' AND status = 'PENDING' THEN 'CANCELLED'
                WHEN payment_method = 'PREPAID' AND status = 'SUCCEEDED' THEN 'REFUND_PENDING'
                ELSE status
              END,
              updated_at = ?
          WHERE order_id = ?
            AND status IN ('AWAITING_COLLECTION', 'PENDING', 'SUCCEEDED')
          """, now(), orderId);
    }
  }

  private Map<String, Object> shipmentToMap(ShipmentRow shipment) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("id", shipment.id());
    response.put("order_id", shipment.orderId());
    response.put("carrier_code", shipment.carrierCode());
    response.put("carrier_shipment_id", shipment.carrierShipmentId());
    response.put("tracking_number", shipment.trackingNumber());
    response.put("selected_service_code", shipment.selectedServiceCode());
    response.put("quoted_amount", shipment.quotedAmount());
    response.put("selected_quote_json", parseMap(shipment.selectedQuoteJson()));
    response.put("current_status", shipment.currentStatus());
    response.put("selection_timestamp", shipment.selectionTimestamp());
    response.put("created_at", shipment.createdAt());
    response.put("updated_at", shipment.updatedAt());
    return response;
  }

  private Map<String, Object> eventToMap(ShipmentEventRow event) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("carrierEventId", event.carrierEventId());
    response.put("carrierStatus", event.carrierStatus());
    response.put("status", event.normalizedStatus());
    response.put("description", event.description());
    response.put("location", event.location());
    response.put("eventTime", event.eventTime());
    return response;
  }

  private Map<String, Object> quoteToMap(ShippingQuote quote) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("carrierCode", quote.carrierCode());
    response.put("carrierName", quote.carrierName());
    response.put("serviceCode", quote.serviceCode());
    response.put("serviceName", quote.serviceName());
    response.put("baseCharge", quote.baseCharge());
    response.put("codCharge", quote.codCharge());
    response.put("additionalCharges", quote.additionalCharges());
    response.put("tax", quote.tax());
    response.put("totalCharge", quote.totalCharge());
    response.put("estimatedMinDays", quote.estimatedMinDays());
    response.put("estimatedMaxDays", quote.estimatedMaxDays());
    response.put("rawCarrierResponse", quote.rawCarrierResponse());
    return response;
  }

  private List<ShippingQuote> sortQuotes(List<ShippingQuote> quotes, String sortBy) {
    List<ShippingQuote> sorted = new ArrayList<>(quotes);
    Comparator<ShippingQuote> lowest = Comparator.comparing(ShippingQuote::totalCharge).thenComparing(ShippingQuote::carrierName);
    Comparator<ShippingQuote> fastest = Comparator.comparing(ShippingQuote::estimatedMinDays).thenComparing(lowest);
    Comparator<ShippingQuote> carrier = Comparator.comparing(ShippingQuote::carrierName).thenComparing(lowest);
    if ("fastest".equalsIgnoreCase(sortBy)) {
      sorted.sort(fastest);
    } else if ("carrier".equalsIgnoreCase(sortBy)) {
      sorted.sort(carrier);
    } else {
      sorted.sort(lowest);
    }
    return sorted;
  }

  private void scheduleAutomation(long shipmentId, String carrierCode) {
    List<String> progression = List.of(STATUS_PICKED_UP, STATUS_IN_TRANSIT, STATUS_OUT_FOR_DELIVERY, STATUS_DELIVERED);
    for (int i = 0; i < progression.size(); i++) {
      String status = progression.get(i);
      long delay = DEFAULT_AUTOMATION_DELAY_MS * (i + 1);
      carrierExecutor.submit(() -> {
        sleep(delay);
        ShipmentRow shipment = findShipmentById(shipmentId);
        if (shipment == null) {
          return;
        }
        JsonNode payload = buildWebhookPayload(shipment, status);
        try {
          handleWebhook(carrierCode, payload);
        } catch (Exception ignored) {
        }
      });
    }
  }

  private ShipmentRow findShipmentById(long id) {
    try {
      return jdbcTemplate.queryForObject("SELECT * FROM shipments WHERE id = ?", shipmentRowMapper, id);
    } catch (Exception exception) {
      return null;
    }
  }

  private ShipmentRow findShipmentByTracking(String trackingKey, String trackingField) {
    String column = "carrier_shipment_id".equals(trackingField) ? "carrier_shipment_id" : "tracking_number";
    try {
      return jdbcTemplate.queryForObject("SELECT * FROM shipments WHERE " + column + " = ?", shipmentRowMapper, trackingKey);
    } catch (Exception exception) {
      return null;
    }
  }

  private Map<String, Object> initialWebhookPayloadData(ShipmentRow shipment, OrderRow order) {
    Map<String, Object> payload = new LinkedHashMap<>();
    if ("FASTSHIP".equals(shipment.carrierCode())) {
      payload.put("shipment_id", shipment.carrierShipmentId());
      payload.put("tracking_number", shipment.trackingNumber());
      payload.put("event_code", "BOOKED");
      payload.put("event_description", "Shipment created with FastShip");
      payload.put("event_time", now());
      payload.put("location", "Zippy Hub");
    } else if ("QUICKEXPRESS".equals(shipment.carrierCode())) {
      payload.put("awb", shipment.trackingNumber());
      payload.put("event", Map.of("type", "SC", "message", "Shipment created with QuickExpress", "occurredAt", now()));
      payload.put("facility", Map.of("city", order.pickupPincode(), "code", "ORIGIN"));
    } else {
      payload.put("trackingCode", shipment.trackingNumber());
      payload.put("statusId", 10);
      payload.put("statusText", "Shipment Created");
      payload.put("updatedOn", now());
    }
    return payload;
  }

  private JsonNode buildWebhookPayload(ShipmentRow shipment, String normalizedStatus) {
    Map<String, Object> payload = new LinkedHashMap<>();
    String now = now();
    if ("FASTSHIP".equals(shipment.carrierCode())) {
      payload.put("shipment_id", shipment.carrierShipmentId());
      payload.put("tracking_number", shipment.trackingNumber());
      payload.put("event_code", switch (normalizedStatus) {
        case STATUS_PICKED_UP -> "PICKED_UP";
        case STATUS_IN_TRANSIT -> "IN_TRANSIT";
        case STATUS_OUT_FOR_DELIVERY -> "OUT_FOR_DELIVERY";
        case STATUS_DELIVERED -> "DELIVERED";
        case STATUS_DELIVERY_FAILED -> "DELIVERY_FAILED";
        case STATUS_RTO -> "RTO";
        default -> "BOOKED";
      });
      payload.put("event_description", "Shipment updated to " + normalizedStatus);
      payload.put("event_time", now);
      payload.put("location", "Automated Update");
    } else if ("QUICKEXPRESS".equals(shipment.carrierCode())) {
      payload.put("awb", shipment.trackingNumber());
      payload.put("event", Map.of(
          "type", switch (normalizedStatus) {
            case STATUS_PICKED_UP -> "PU";
            case STATUS_IN_TRANSIT -> "IT";
            case STATUS_OUT_FOR_DELIVERY -> "OFD";
            case STATUS_DELIVERED -> "DLV";
            case STATUS_DELIVERY_FAILED -> "NDR";
            case STATUS_RTO -> "RTO";
            default -> "SC";
          },
          "message", "Shipment updated to " + normalizedStatus,
          "occurredAt", now
      ));
      payload.put("facility", Map.of("city", "Automated Update", "code", "AUTO"));
    } else {
      payload.put("trackingCode", shipment.trackingNumber());
      payload.put("statusId", switch (normalizedStatus) {
        case STATUS_PICKED_UP -> 20;
        case STATUS_IN_TRANSIT -> 30;
        case STATUS_OUT_FOR_DELIVERY -> 40;
        case STATUS_DELIVERED -> 50;
        case STATUS_DELIVERY_FAILED -> 60;
        case STATUS_RTO -> 70;
        default -> 10;
      });
      payload.put("statusText", normalizedStatus.replace('_', ' '));
      payload.put("updatedOn", now);
      payload.put("proofOfDelivery", STATUS_DELIVERED.equals(normalizedStatus) ? Map.of("receivedBy", "Rahul Sharma", "deliveryLocation", "New Delhi") : null);
    }
    return objectMapper.valueToTree(payload);
  }

  private CarrierEvent mapWebhook(String carrierCode, JsonNode payload) {
    return switch (carrierCode) {
      case "FASTSHIP" -> new CarrierEvent(
          payload.path("shipment_id").asText(),
          "carrier_shipment_id",
          payload.path("event_code").asText(),
          payload.path("event_code").asText(),
          mapFastshipStatus(payload.path("event_code").asText()),
          payload.path("event_description").asText(),
          payload.path("location").asText(null),
          payload.path("event_time").asText()
      );
      case "QUICKEXPRESS" -> new CarrierEvent(
          payload.path("awb").asText(),
          "tracking_number",
          payload.path("event").path("type").asText(),
          payload.path("event").path("type").asText(),
          mapQuickStatus(payload.path("event").path("type").asText()),
          payload.path("event").path("message").asText(),
          payload.path("facility").path("city").asText(null),
          payload.path("event").path("occurredAt").asText()
      );
      default -> new CarrierEvent(
          payload.path("trackingCode").asText(),
          "tracking_number",
          String.valueOf(payload.path("statusId").asInt()),
          String.valueOf(payload.path("statusId").asInt()),
          mapReliableStatus(payload.path("statusId").asInt()),
          payload.path("statusText").asText(),
          payload.path("proofOfDelivery").path("deliveryLocation").asText(null),
          payload.path("updatedOn").asText()
      );
    };
  }

  private Map<String, Object> fastShipRate(OrderContext order) {
    BigDecimal freight = money(90).add(order.weightKg().multiply(money(20)));
    BigDecimal cod = "COD".equalsIgnoreCase(order.paymentMode()) ? money(35) : ZERO;
    BigDecimal tax = freight.add(cod).multiply(money(0.18));
    BigDecimal total = freight.add(cod).add(tax);
    return Map.of(
        "success", true,
        "service", Map.of(
            "service_code", "FAST-AIR",
            "service_name", "FastShip Air Express",
            "freight_charge", freight,
            "cod_charge", cod,
            "tax", tax,
            "total_amount", total,
            "estimated_days", 2
        )
    );
  }

  private Map<String, Object> quickExpressRate(OrderContext order) {
    BigDecimal shipping = money(100).add(order.weightKg().multiply(money(10)));
    BigDecimal cod = "COD".equalsIgnoreCase(order.paymentMode()) ? money(40) : ZERO;
    BigDecimal fuelSurcharge = money(12);
    BigDecimal gst = shipping.add(cod).add(fuelSurcharge).multiply(money(0.18));
    BigDecimal payable = shipping.add(cod).add(fuelSurcharge).add(gst);
    return Map.of(
        "status", "AVAILABLE",
        "quoteId", "QE-Q-90001",
        "charges", Map.of(
            "shipping", shipping,
            "cod", cod,
            "fuelSurcharge", fuelSurcharge,
            "gst", gst
        ),
        "payable", payable,
        "deliveryEstimate", Map.of("minimumDays", 2, "maximumDays", 3),
        "product", "EXPRESS"
    );
  }

  private Map<String, Object> reliableRates(OrderContext order) {
    BigDecimal cod = "COD".equalsIgnoreCase(order.paymentMode()) ? money(30) : ZERO;
    return Map.of(
        "code", 200,
        "data", List.of(
            Map.of(
                "id", "RC-SURFACE",
                "name", "Reliable Surface",
                "rate", Map.of("base", money(95), "handling", money(10), "cashCollectionFee", cod, "taxAmount", money(24.30), "grandTotal", money(95).add(money(10)).add(cod).add(money(24.30))),
                "eta", "4-5 business days"
            ),
            Map.of(
                "id", "RC-AIR",
                "name", "Reliable Air",
                "rate", Map.of("base", money(130), "handling", money(12), "cashCollectionFee", cod, "taxAmount", money(30.96), "grandTotal", money(130).add(money(12)).add(cod).add(money(30.96))),
                "eta", "2-3 business days"
            )
        )
    );
  }

  private Map<String, Object> fastShipCreateShipment(OrderRow order, ShippingQuote quote) {
    return Map.of(
        "success", true,
        "shipment_id", "FS-700001",
        "tracking_number", "FST123456789",
        "label_url", "http://mock-fastship/labels/FST123456789.pdf",
        "status", "BOOKED"
    );
  }

  private Map<String, Object> quickExpressCreateShipment(OrderRow order, ShippingQuote quote) {
    return Map.of(
        "bookingStatus", "CONFIRMED",
        "booking", Map.of("bookingId", "QE-B-800001", "awb", "QE987654321", "currentState", "SHIPMENT_CREATED")
    );
  }

  private Map<String, Object> reliableShipment(OrderRow order, ShippingQuote quote) {
    return Map.of(
        "result", "ACCEPTED",
        "deliveryOrder", Map.of("id", "RC-DO-600001", "trackingCode", "RC1122334455"),
        "message", "Shipment successfully registered"
    );
  }

  private ShippingQuote fastShipQuote(OrderRow order) {
    Map<String, Object> response = fastShipRate(orderContext(order.weightGrams(), order.paymentType(), defaultDecimal(order.codAmount())));
    Map<String, Object> service = mapValue(response, "service");
    return new ShippingQuote(
        "FASTSHIP",
        "FastShip",
        "FAST-AIR",
        "FastShip Air Express",
        decimal(service.get("freight_charge")),
        decimal(service.get("cod_charge")),
        ZERO,
        decimal(service.get("tax")),
        decimal(service.get("total_amount")),
        2,
        2,
        response
    );
  }

  private ShippingQuote quickExpressQuote(OrderRow order) {
    Map<String, Object> response = quickExpressRate(orderContext(order.weightGrams(), order.paymentType(), defaultDecimal(order.codAmount())));
    Map<String, Object> charges = mapValue(response, "charges");
    Map<String, Object> estimate = mapValue(response, "deliveryEstimate");
    return new ShippingQuote(
        "QUICKEXPRESS",
        "QuickExpress",
        "EXPRESS",
        "QuickExpress Express",
        decimal(charges.get("shipping")),
        decimal(charges.get("cod")),
        decimal(charges.get("fuelSurcharge")),
        decimal(charges.get("gst")),
        decimal(response.get("payable")),
        Integer.parseInt(String.valueOf(estimate.get("minimumDays"))),
        Integer.parseInt(String.valueOf(estimate.get("maximumDays"))),
        response
    );
  }

  private ShippingQuote reliableQuote(OrderRow order) {
    Map<String, Object> response = reliableRates(orderContext(order.weightGrams(), order.paymentType(), defaultDecimal(order.codAmount())));
    List<Map<String, Object>> data = listValue(response, "data");
    Map<String, Object> best = data.stream().min(Comparator.comparing(item -> decimal(mapValue(item, "rate").get("grandTotal")))).orElseThrow();
    Map<String, Object> rate = mapValue(best, "rate");
    int[] eta = parseEta(String.valueOf(best.get("eta")));
    return new ShippingQuote(
        "RELIABLE",
        "ReliableCourier",
        String.valueOf(best.get("id")),
        String.valueOf(best.get("name")),
        decimal(rate.get("base")),
        decimal(rate.get("cashCollectionFee")),
        decimal(rate.get("handling")),
        decimal(rate.get("taxAmount")),
        decimal(rate.get("grandTotal")),
        eta[0],
        eta[1],
        response
    );
  }

  private JsonNode initialWebhookPayloadJson(ShipmentRow shipment, OrderRow order) {
    Map<String, Object> payload = initialWebhookPayloadData(shipment, order);
    return objectMapper.valueToTree(payload);
  }

  private OrderContext orderContext(long weightGrams, String paymentMode, BigDecimal invoiceValue) {
    return new OrderContext(weightGrams, paymentMode, invoiceValue);
  }

  private OrderRow findOrderByZippyId(String orderId) {
    try {
      return jdbcTemplate.queryForObject("SELECT * FROM orders WHERE zippy_order_id = ?", orderRowMapper, orderId);
    } catch (Exception exception) {
      throw new ApiException(404, "Order not found");
    }
  }

  private OrderRow findOrderByZippyOrderIdForUpdate(String orderId) {
    try {
      return jdbcTemplate.queryForObject(
          "SELECT * FROM orders WHERE zippy_order_id = ? FOR UPDATE",
          orderRowMapper,
          orderId
      );
    } catch (Exception exception) {
      throw new ApiException(404, "Order not found");
    }
  }

  private OrderRow findOrderByZippyOrderId(String orderId) {
    return findOrderByZippyId(orderId);
  }

  private OrderRow findOrderById(long orderId) {
    try {
      return jdbcTemplate.queryForObject("SELECT * FROM orders WHERE id = ?", orderRowMapper, orderId);
    } catch (Exception exception) {
      throw new ApiException(404, "Order not found");
    }
  }

  private String nextOrderId() {
    synchronized (sequenceLock) {
      String value = jdbcTemplate.queryForObject("SELECT meta_value FROM app_meta WHERE meta_key = ?", String.class, "order_sequence");
      long next = value == null ? 10001L : Long.parseLong(value) + 1L;
      jdbcTemplate.update("""
          MERGE INTO app_meta (meta_key, meta_value) KEY(meta_key) VALUES (?, ?)
          """, "order_sequence", String.valueOf(next));
      return "ZPY-ORD-" + next;
    }
  }

  private String normalizeIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey == null) {
      return null;
    }
    String normalized = idempotencyKey.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private String hashOrderRequest(OrderCreateRequest request) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(toJson(request).getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder();
      for (byte value : hash) {
        builder.append(String.format("%02x", value));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new ApiException(500, "Unable to hash order request");
    }
  }

  private ShippingQuote parseQuote(String json) {
    try {
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (Exception exception) {
      throw new ApiException(500, "Failed to parse selected quote");
    }
  }

  private ShippingQuote mapQuoteRow(ResultSet rs, int rowNum) throws SQLException {
    return new ShippingQuote(
        rs.getString("carrier_code"),
        rs.getString("carrier_name"),
        rs.getString("service_code"),
        rs.getString("service_name"),
        rs.getBigDecimal("base_charge").setScale(2, RoundingMode.HALF_UP),
        rs.getBigDecimal("cod_charge").setScale(2, RoundingMode.HALF_UP),
        rs.getBigDecimal("additional_charges").setScale(2, RoundingMode.HALF_UP),
        rs.getBigDecimal("tax").setScale(2, RoundingMode.HALF_UP),
        rs.getBigDecimal("total_charge").setScale(2, RoundingMode.HALF_UP),
        rs.getInt("estimated_min_days"),
        rs.getInt("estimated_max_days"),
        parseMap(rs.getString("raw_carrier_response"))
    );
  }

  private final RowMapper<OrderRow> orderRowMapper = (rs, rowNum) -> new OrderRow(
      rs.getLong("id"),
      rs.getString("zippy_order_id"),
      rs.getString("merchant_order_id"),
      rs.getString("customer_name"),
      rs.getString("customer_phone"),
      rs.getString("customer_email"),
      rs.getString("pickup_address_json"),
      rs.getString("delivery_address_json"),
      rs.getString("pickup_pincode"),
      rs.getString("delivery_pincode"),
      rs.getInt("weight_grams"),
      rs.getBigDecimal("length_cm"),
      rs.getBigDecimal("width_cm"),
      rs.getBigDecimal("height_cm"),
      rs.getString("payment_type"),
      rs.getBigDecimal("cod_amount"),
      rs.getString("order_status"),
      rs.getString("created_at"),
      rs.getString("updated_at")
  );

  private final RowMapper<ShippingQuote> shippingQuoteRowMapper = this::mapQuoteRow;

  private final RowMapper<ShipmentRow> shipmentRowMapper = (rs, rowNum) -> new ShipmentRow(
      rs.getLong("id"),
      rs.getLong("order_id"),
      rs.getString("carrier_code"),
      rs.getString("carrier_shipment_id"),
      rs.getString("tracking_number"),
      rs.getString("selected_service_code"),
      rs.getBigDecimal("quoted_amount").setScale(2, RoundingMode.HALF_UP),
      rs.getString("selected_quote_json"),
      rs.getString("current_status"),
      rs.getString("selection_timestamp"),
      rs.getString("created_at"),
      rs.getString("updated_at")
  );

  private final RowMapper<ShipmentEventRow> shipmentEventRowMapper = (rs, rowNum) -> new ShipmentEventRow(
      rs.getLong("id"),
      rs.getLong("shipment_id"),
      rs.getString("carrier_event_id"),
      rs.getString("carrier_status"),
      rs.getString("normalized_status"),
      rs.getString("description"),
      rs.getString("location"),
      rs.getString("event_time"),
      rs.getString("raw_event_payload"),
      rs.getString("received_at")
  );

  private final RowMapper<IdempotencyRecord> idempotencyRowMapper = (rs, rowNum) -> new IdempotencyRecord(
      rs.getString("idempotency_key"),
      rs.getString("request_hash"),
      rs.getString("response_json"),
      rs.getString("created_at")
  );

  private BigDecimal money(double value) {
    return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
  }

  private BigDecimal defaultDecimal(BigDecimal value) {
    return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
  }

  private BigDecimal decimal(Object value) {
    if (value instanceof BigDecimal bigDecimal) {
      return bigDecimal.setScale(2, RoundingMode.HALF_UP);
    }
    return new BigDecimal(String.valueOf(value)).setScale(2, RoundingMode.HALF_UP);
  }

  private Map<String, Object> mapValue(Map<String, Object> parent, String key) {
    return (Map<String, Object>) parent.get(key);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> listValue(Map<String, Object> parent, String key) {
    return (List<Map<String, Object>>) parent.get(key);
  }

  private String stringValue(Map<String, Object> parent, String key) {
    Object value = parent.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private Map<String, Object> parseMap(String json) {
    try {
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (Exception exception) {
      return Collections.emptyMap();
    }
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception exception) {
      throw new ApiException(500, "Failed to serialize payload");
    }
  }

  private String now() {
    return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
  }

  private void sleep(long millis) {
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interruptedException) {
      Thread.currentThread().interrupt();
    }
  }

  private boolean booleanValue(Object value) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    return Boolean.parseBoolean(String.valueOf(value));
  }

  private long longValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    return Long.parseLong(String.valueOf(value));
  }

  private int[] parseEta(String eta) {
    String[] parts = eta.replaceAll("[^0-9-]", "").split("-");
    if (parts.length == 2) {
      return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }
    int day = Integer.parseInt(parts[0]);
    return new int[]{day, day};
  }

  private String mapFastshipStatus(String status) {
    return switch (status) {
      case "BOOKED" -> STATUS_SHIPMENT_CREATED;
      case "PICKED_UP" -> STATUS_PICKED_UP;
      case "IN_TRANSIT" -> STATUS_IN_TRANSIT;
      case "OUT_FOR_DELIVERY" -> STATUS_OUT_FOR_DELIVERY;
      case "DELIVERED" -> STATUS_DELIVERED;
      case "DELIVERY_FAILED" -> STATUS_DELIVERY_FAILED;
      case "RTO" -> STATUS_RTO;
      default -> null;
    };
  }

  private String mapQuickStatus(String status) {
    return switch (status) {
      case "SC" -> STATUS_SHIPMENT_CREATED;
      case "PU" -> STATUS_PICKED_UP;
      case "IT" -> STATUS_IN_TRANSIT;
      case "OFD" -> STATUS_OUT_FOR_DELIVERY;
      case "DLV" -> STATUS_DELIVERED;
      case "NDR" -> STATUS_DELIVERY_FAILED;
      case "RTO" -> STATUS_RTO;
      default -> null;
    };
  }

  private String mapReliableStatus(int statusId) {
    return switch (statusId) {
      case 10 -> STATUS_SHIPMENT_CREATED;
      case 20 -> STATUS_PICKED_UP;
      case 30 -> STATUS_IN_TRANSIT;
      case 40 -> STATUS_OUT_FOR_DELIVERY;
      case 50 -> STATUS_DELIVERED;
      case 60 -> STATUS_DELIVERY_FAILED;
      case 70 -> STATUS_RTO;
      default -> null;
    };
  }

  private boolean allowedTransition(String currentStatus, String nextStatus) {
    if (Objects.equals(currentStatus, nextStatus)) {
      return true;
    }
    return switch (currentStatus) {
      case STATUS_ORDER_CREATED -> STATUS_CARRIER_SELECTED.equals(nextStatus) || STATUS_SHIPMENT_CREATED.equals(nextStatus) || STATUS_CANCELLED.equals(nextStatus);
      case STATUS_CARRIER_SELECTED -> STATUS_SHIPMENT_CREATED.equals(nextStatus) || STATUS_CANCELLED.equals(nextStatus);
      case STATUS_SHIPMENT_CREATED -> STATUS_PICKED_UP.equals(nextStatus) || STATUS_CANCELLED.equals(nextStatus);
      case STATUS_PICKED_UP -> STATUS_IN_TRANSIT.equals(nextStatus);
      case STATUS_IN_TRANSIT -> STATUS_OUT_FOR_DELIVERY.equals(nextStatus);
      case STATUS_OUT_FOR_DELIVERY -> STATUS_DELIVERED.equals(nextStatus) || STATUS_DELIVERY_FAILED.equals(nextStatus);
      case STATUS_DELIVERY_FAILED -> STATUS_IN_TRANSIT.equals(nextStatus) || STATUS_RTO.equals(nextStatus);
      case STATUS_DELIVERED, STATUS_RTO, STATUS_CANCELLED -> false;
      default -> false;
    };
  }

  private record OrderContext(long weightGrams, String paymentMode, BigDecimal invoiceValue) {
    BigDecimal weightKg() {
      return BigDecimal.valueOf(weightGrams).divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP);
    }
  }

  private record OrderRow(
      long id,
      String zippyOrderId,
      String merchantOrderId,
      String customerName,
      String customerPhone,
      String customerEmail,
      String pickupAddressJson,
      String deliveryAddressJson,
      String pickupPincode,
      String deliveryPincode,
      int weightGrams,
      BigDecimal lengthCm,
      BigDecimal widthCm,
      BigDecimal heightCm,
      String paymentType,
      BigDecimal codAmount,
      String orderStatus,
      String createdAt,
      String updatedAt
  ) {}

  private record ShippingQuote(
      String carrierCode,
      String carrierName,
      String serviceCode,
      String serviceName,
      BigDecimal baseCharge,
      BigDecimal codCharge,
      BigDecimal additionalCharges,
      BigDecimal tax,
      BigDecimal totalCharge,
      int estimatedMinDays,
      int estimatedMaxDays,
      Map<String, Object> rawCarrierResponse
  ) {}

  private record ShipmentRow(
      long id,
      long orderId,
      String carrierCode,
      String carrierShipmentId,
      String trackingNumber,
      String selectedServiceCode,
      BigDecimal quotedAmount,
      String selectedQuoteJson,
      String currentStatus,
      String selectionTimestamp,
      String createdAt,
      String updatedAt
  ) {}

  private record ShipmentEventRow(
      long id,
      long shipmentId,
      String carrierEventId,
      String carrierStatus,
      String normalizedStatus,
      String description,
      String location,
      String eventTime,
      String rawEventPayload,
      String receivedAt
  ) {}

  private record CarrierEvent(
      String trackingKey,
      String trackingField,
      String carrierEventId,
      String carrierStatus,
      String normalizedStatus,
      String description,
      String location,
      String eventTime
  ) {}

  private record IdempotencyRecord(
      String idempotencyKey,
      String requestHash,
      String responseJson,
      String createdAt
  ) {}

  private record PaymentRow(
      long id,
      String paymentId,
      long orderId,
      String zippyOrderId,
      BigDecimal amount,
      String currency,
      String status,
      String paymentMethod,
      String collectionStage,
      String failureCode,
      String failureReason,
      BigDecimal refundedAmount,
      String createdAt,
      String updatedAt
  ) {}

  private final RowMapper<PaymentRow> paymentRowMapper = (rs, rowNum) -> new PaymentRow(
      rs.getLong("id"),
      rs.getString("payment_id"),
      rs.getLong("order_id"),
      rs.getString("zippy_order_id"),
      defaultDecimal(rs.getBigDecimal("amount")),
      rs.getString("currency"),
      rs.getString("status"),
      rs.getString("payment_method"),
      rs.getString("collection_stage"),
      rs.getString("failure_code"),
      rs.getString("failure_reason"),
      defaultDecimal(rs.getBigDecimal("refunded_amount")),
      rs.getString("created_at"),
      rs.getString("updated_at")
  );

  @Transactional
  public PaymentIntentResponse createPaymentIntent(CreatePaymentIntentRequest request) {
    OrderRow order = findOrderByZippyId(request.getOrderId());
    if (!"PREPAID".equalsIgnoreCase(order.paymentType())) {
      throw new ApiException(409, "Payment intents are only available for prepaid orders");
    }

    ShipmentRow shipment = findShipment(order.id());
    if (shipment == null) {
      throw new ApiException(409, "Select a carrier before creating a payment");
    }

    String currency = request.getCurrency().trim().toUpperCase();
    if (!List.of("INR", "USD").contains(currency)) {
      throw new ApiException(422, "Unsupported currency: " + currency);
    }

    BigDecimal amount = request.getAmount().setScale(2, RoundingMode.HALF_UP);
    if (!"INR".equals(currency) || amount.compareTo(shipment.quotedAmount()) != 0) {
      throw new ApiException(422, "Payment amount must match the selected shipment charge in INR");
    }

    PaymentRow existingPending = findPendingPaymentForOrder(order.id());
    if (existingPending != null) {
      if (existingPending.amount().compareTo(amount) != 0 || !currency.equals(existingPending.currency())) {
        throw new ApiException(409, "A payment intent already exists for this order");
      }
      return toPaymentIntentResponse(existingPending);
    }

    String paymentId = "PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    String timestamp = now();
    jdbcTemplate.update("""
        INSERT INTO payments (
          payment_id, order_id, zippy_order_id, amount, currency, status,
          payment_method, collection_stage, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        paymentId, order.id(), order.zippyOrderId(), amount, currency, "PENDING", "PREPAID", "CHECKOUT", timestamp, timestamp);

    return toPaymentIntentResponse(requirePayment(paymentId));
  }

  @Transactional
  public PaymentIntentResponse confirmPayment(String paymentId) {
    PaymentRow payment = requirePayment(paymentId);
    if (!"PREPAID".equals(payment.paymentMethod())) {
      throw new ApiException(409, "Only prepaid payments can be confirmed");
    }
    if ("SUCCEEDED".equals(payment.status())) {
      return toPaymentIntentResponse(payment);
    }
    if (!"PENDING".equals(payment.status())) {
      throw new ApiException(409, "Payment cannot be confirmed from status " + payment.status());
    }

    String timestamp = now();
    jdbcTemplate.update("UPDATE payments SET status = ?, updated_at = ? WHERE payment_id = ?",
        "SUCCEEDED", timestamp, paymentId);
    return toPaymentIntentResponse(requirePayment(paymentId));
  }

  @Transactional
  public PaymentIntentResponse failPayment(String paymentId, PaymentFailureRequest request) {
    PaymentRow payment = requirePayment(paymentId);
    if (!"PREPAID".equals(payment.paymentMethod()) || !"PENDING".equals(payment.status())) {
      throw new ApiException(409, "Only pending prepaid payments can be failed");
    }
    String timestamp = now();
    String code = request == null || request.code() == null || request.code().isBlank() ? "PAYMENT_DECLINED" : request.code().trim();
    String reason = request == null || request.reason() == null || request.reason().isBlank() ? "Payment was declined" : request.reason().trim();
    jdbcTemplate.update("UPDATE payments SET status = ?, failure_code = ?, failure_reason = ?, updated_at = ? WHERE payment_id = ?",
        "FAILED", code, reason, timestamp, paymentId);
    return toPaymentIntentResponse(requirePayment(paymentId));
  }

  @Transactional
  public PaymentIntentResponse cancelPayment(String paymentId) {
    PaymentRow payment = requirePayment(paymentId);
    if (!"PENDING".equals(payment.status())) {
      throw new ApiException(409, "Only pending payments can be cancelled");
    }
    jdbcTemplate.update("UPDATE payments SET status = ?, updated_at = ? WHERE payment_id = ?",
        "CANCELLED", now(), paymentId);
    return toPaymentIntentResponse(requirePayment(paymentId));
  }

  @Transactional
  public PaymentIntentResponse refundPayment(String paymentId) {
    PaymentRow payment = requirePayment(paymentId);
    if (!"PREPAID".equals(payment.paymentMethod())
        || !("SUCCEEDED".equals(payment.status()) || "REFUND_PENDING".equals(payment.status()))) {
      throw new ApiException(409, "Only succeeded or refund-pending prepaid payments can be refunded");
    }
    jdbcTemplate.update("UPDATE payments SET status = ?, refunded_amount = amount, updated_at = ? WHERE payment_id = ?",
        "REFUNDED", now(), paymentId);
    return toPaymentIntentResponse(requirePayment(paymentId));
  }

  @Transactional
  public PaymentIntentResponse collectPayment(String paymentId) {
    PaymentRow payment = requirePayment(paymentId);
    if (!"COD".equals(payment.paymentMethod())) {
      throw new ApiException(409, "Only COD payments can be collected at delivery");
    }
    if ("SUCCEEDED".equals(payment.status())) {
      return toPaymentIntentResponse(payment);
    }
    if (!"AWAITING_COLLECTION".equals(payment.status())) {
      throw new ApiException(409, "COD payment cannot be collected from status " + payment.status());
    }

    OrderRow order = findOrderByZippyId(payment.zippyOrderId());
    ShipmentRow shipment = findShipment(order.id());
    if (shipment == null || !STATUS_DELIVERED.equals(shipment.currentStatus())) {
      throw new ApiException(409, "COD can only be collected after delivery is confirmed");
    }

    String timestamp = now();
    jdbcTemplate.update("UPDATE payments SET status = ?, collection_stage = ?, updated_at = ? WHERE payment_id = ?",
        "SUCCEEDED", "DELIVERY", timestamp, paymentId);
    return toPaymentIntentResponse(requirePayment(paymentId));
  }

  public PaymentIntentResponse getPayment(String paymentId) {
    return toPaymentIntentResponse(requirePayment(paymentId));
  }

  public List<PaymentIntentResponse> getPaymentsForOrder(String orderId) {
    OrderRow order = findOrderByZippyId(orderId);
    return jdbcTemplate.query(
        "SELECT * FROM payments WHERE order_id = ? ORDER BY id DESC",
        paymentRowMapper,
        order.id()
    ).stream().map(this::toPaymentIntentResponse).toList();
  }

  private PaymentRow findPendingPaymentForOrder(long orderId) {
    List<PaymentRow> payments = jdbcTemplate.query(
        "SELECT * FROM payments WHERE order_id = ? AND status = 'PENDING' ORDER BY id DESC LIMIT 1",
        paymentRowMapper,
        orderId
    );
    return payments.isEmpty() ? null : payments.getFirst();
  }

  private void createCodPayment(OrderRow order, String timestamp) {
    Long existing = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payments WHERE order_id = ? AND payment_method = 'COD'",
        Long.class,
        order.id()
    );
    if (existing != null && existing > 0) {
      return;
    }

    String paymentId = "COD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    jdbcTemplate.update("""
        INSERT INTO payments (
          payment_id, order_id, zippy_order_id, amount, currency, status,
          payment_method, collection_stage, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        paymentId, order.id(), order.zippyOrderId(), defaultDecimal(order.codAmount()), "INR",
        "AWAITING_COLLECTION", "COD", "DELIVERY", timestamp, timestamp);
  }

  private PaymentRow requirePayment(String paymentId) {
    try {
      return jdbcTemplate.queryForObject(
          "SELECT * FROM payments WHERE payment_id = ?",
          paymentRowMapper,
          paymentId
      );
    } catch (Exception exception) {
      throw new ApiException(404, "Payment not found");
    }
  }

  private PaymentIntentResponse toPaymentIntentResponse(PaymentRow payment) {
    PaymentIntentResponse response = new PaymentIntentResponse();
    response.setPaymentId(payment.paymentId());
    response.setOrderId(payment.zippyOrderId());
    response.setAmount(payment.amount());
    response.setCurrency(payment.currency());
    response.setStatus(payment.status());
    response.setPaymentMethod(payment.paymentMethod());
    response.setCollectionStage(payment.collectionStage());
    response.setFailureCode(payment.failureCode());
    response.setFailureReason(payment.failureReason());
    response.setRefundedAmount(payment.refundedAmount());
    response.setCreatedAt(LocalDateTime.ofInstant(Instant.parse(payment.createdAt()), ZoneOffset.UTC));
    return response;
  }

  private Map<String, Object> paymentToMap(PaymentRow payment) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("paymentId", payment.paymentId());
    response.put("orderId", payment.zippyOrderId());
    response.put("amount", payment.amount());
    response.put("currency", payment.currency());
    response.put("status", payment.status());
    response.put("paymentMethod", payment.paymentMethod());
    response.put("collectionStage", payment.collectionStage());
    response.put("failureCode", payment.failureCode());
    response.put("failureReason", payment.failureReason());
    response.put("refundedAmount", payment.refundedAmount());
    response.put("createdAt", payment.createdAt());
    response.put("updatedAt", payment.updatedAt());
    return response;
  }
}
