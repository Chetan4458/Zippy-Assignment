package com.zippy.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zippy.backend.dto.CarrierSelectionRequest;
import com.zippy.backend.dto.CreatePaymentIntentRequest;
import com.zippy.backend.dto.DailyFinanceTrendResponse;
import com.zippy.backend.dto.DailyTrendReportResponse;
import com.zippy.backend.dto.FinanceAmountResponse;
import com.zippy.backend.dto.FinanceFilterResponse;
import com.zippy.backend.dto.FinanceMetricsResponse;
import com.zippy.backend.dto.OrderCreateRequest;
import com.zippy.backend.dto.PaymentActionRequest;
import com.zippy.backend.dto.PaymentHistoryResponse;
import com.zippy.backend.dto.PaymentIntentResponse;
import com.zippy.backend.dto.PaymentFailureRequest;
import com.zippy.backend.dto.PaymentTransactionHistoryResponse;
import com.zippy.backend.dto.PaymentTransactionResponse;
import com.zippy.backend.exception.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ZippyService {
  private static final Logger LOGGER = LoggerFactory.getLogger(ZippyService.class);
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
  private static final long MAX_RUNTIME_DELAY_MS = 10_000L;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final ExecutorService carrierExecutor;
  private final ScheduledExecutorService automationScheduler;
  private final TransactionTemplate transactionTemplate;
  private final TransactionTemplate isolatedTransactionTemplate;
  private final boolean automationEnabled;
  private final AtomicLong fastshipRateDelayMs = new AtomicLong(0);
  private final AtomicLong quickexpressRateDelayMs = new AtomicLong(0);
  private final AtomicLong reliableRateDelayMs = new AtomicLong(0);
  private final AtomicBoolean fastshipRateFailure = new AtomicBoolean(false);
  private final AtomicBoolean quickexpressRateFailure = new AtomicBoolean(false);
  private final AtomicBoolean reliableRateFailure = new AtomicBoolean(false);

  public ZippyService(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      @Qualifier("carrierExecutor") ExecutorService carrierExecutor,
      @Qualifier("automationScheduler") ScheduledExecutorService automationScheduler,
      PlatformTransactionManager transactionManager,
      @Value("${zippy.automation-enabled:true}") boolean automationEnabled
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.carrierExecutor = carrierExecutor;
    this.automationScheduler = automationScheduler;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.isolatedTransactionTemplate = new TransactionTemplate(transactionManager);
    this.isolatedTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.automationEnabled = automationEnabled;
  }

  @Transactional
  public Map<String, Object> createOrder(OrderCreateRequest request, String idempotencyKey) {
    String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
    String requestHash = normalizedKey == null ? null : hashOrderRequest(request);
    Map<String, Object> replay = findIdempotentReplay(normalizedKey, requestHash);
    if (replay != null) {
      return replay;
    }

    validateOrder(request);
    if (normalizedKey != null) {
      ensureIdempotencyLockRow(normalizedKey);
      lockIdempotencyKey(normalizedKey);
      replay = findIdempotentReplay(normalizedKey, requestHash);
      if (replay != null) {
        return replay;
      }
    }
    String now = now();
    String orderId = reserveNextOrderId();
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
      statement.setInt(10, exactWeightGrams(request.packageDetails().weightGrams()));
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
    Map<Long, ShipmentRow> shipmentsByOrderId = findShipmentsByOrderIds(
        orders.stream().map(OrderRow::id).toList()
    );

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("limit", safeLimit);
    response.put("offset", safeOffset);
    response.put("totalOrders", countRows("orders"));
    response.put("orders", orders.stream()
        .map(order -> orderHistoryToMap(order, shipmentsByOrderId.get(order.id())))
        .toList());
    return response;
  }

  public Map<String, Object> getShipmentEvents(String orderId, int limit, int offset) {
    OrderRow order = findOrderByZippyId(orderId);
    ShipmentRow shipment = findShipment(order.id());
    int safeLimit = Math.max(1, Math.min(limit, 50));
    int safeOffset = Math.max(0, offset);
    long totalEvents = 0L;
    List<ShipmentEventRow> page = List.of();
    if (shipment != null) {
      Long count = jdbcTemplate.queryForObject(
          "SELECT COUNT(*) FROM shipment_events WHERE shipment_id = ?",
          Long.class,
          shipment.id()
      );
      totalEvents = count == null ? 0L : count;
      page = jdbcTemplate.query("""
          SELECT * FROM shipment_events
          WHERE shipment_id = ?
          ORDER BY event_time ASC, id ASC
          LIMIT ? OFFSET ?
          """, shipmentEventRowMapper, shipment.id(), safeLimit, safeOffset);
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("orderId", orderId);
    response.put("totalEvents", totalEvents);
    response.put("limit", safeLimit);
    response.put("offset", safeOffset);
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

  public Map<String, Object> getHealth() {
    try {
      Integer databaseProbe = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
      if (!Integer.valueOf(1).equals(databaseProbe)) {
        throw new ApiException(503, "Database health check failed");
      }
    } catch (DataAccessException exception) {
      LOGGER.warn("Database health check failed", exception);
      throw new ApiException(503, "Database health check failed");
    }
    return Map.of(
        "status", "UP",
        "database", "UP",
        "timestamp", now()
    );
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> getReportsSummary(
      LocalDate from,
      LocalDate to,
      String status,
      String method,
      String carrier
  ) {
    ReportFilter filter = reportFilter(from, to, status, method, carrier, null);
    FinanceMetricsResponse finance = calculateFinanceMetrics(filter);
    Map<String, Object> summary = new LinkedHashMap<>();

    summary.put("totalOrders", countRows("orders"));
    summary.put("totalShipments", countRows("shipments"));
    summary.put("totalPayments", countFilteredPayments(filter));
    summary.put("scope", Map.of(
        "operationalTotals", "ALL_TIME",
        "finance", "FILTERED",
        "cashFlowDateBasis", "PAYMENT_TRANSACTION_CREATED_AT_UTC",
        "balanceDateBasis", "PAYMENT_CREATED_AT_UTC",
        "shipmentDateBasis", "SHIPMENT_CREATED_AT_UTC"
    ));

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

    summary.put("totalShippingCost", finance.shipmentCost());
    summary.put("totalCodValue", finance.codOutstanding().amount());
    summary.put("totalPaymentCollected", finance.grossCollected());
    summary.put("filters", toFilterResponse(filter));
    summary.put("finance", finance);
    summary.put("dailyTrend", dailyTrend(filter));
    summary.put("carrierBreakdown", carrierBreakdown(filter));
    summary.put("paymentMethodBreakdown", paymentMethodBreakdown(filter));
    summary.put("paymentsByStatus", paymentStatusBreakdown(filter));

    return summary;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PaymentHistoryResponse getPaymentHistory(
      int limit,
      int offset,
      LocalDate from,
      LocalDate to,
      String status,
      String method,
      String carrier,
      String search
  ) {
    int safeLimit = Math.max(1, Math.min(limit, 50));
    int safeOffset = Math.max(0, offset);
    ReportFilter filter = reportFilter(from, to, status, method, carrier, search);
    SqlFilter paymentFilter = paymentSqlFilter(filter, "p", "s", "p.created_at", true);
    List<Object> pageArgs = new ArrayList<>(paymentFilter.arguments());
    pageArgs.add(safeLimit);
    pageArgs.add(safeOffset);
    List<PaymentIntentResponse> payments = jdbcTemplate.query("""
            SELECT p.*
            FROM payments p
            LEFT JOIN shipments s ON s.order_id = p.order_id
            """ + paymentFilter.clause() + " ORDER BY p.created_at DESC, p.id DESC LIMIT ? OFFSET ?",
        paymentRowMapper,
        pageArgs.toArray()
    ).stream().map(this::toPaymentIntentResponse).toList();

    return new PaymentHistoryResponse(
        safeLimit,
        safeOffset,
        countFilteredPayments(filter),
        payments,
        toFilterResponse(filter),
        calculateFinanceMetrics(filter)
    );
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public DailyTrendReportResponse getDailyTrend(
      LocalDate from,
      LocalDate to,
      String status,
      String method,
      String carrier
  ) {
    ReportFilter filter = reportFilter(from, to, status, method, carrier, null);
    return new DailyTrendReportResponse(toFilterResponse(filter), dailyTrend(filter));
  }

  private ReportFilter reportFilter(
      LocalDate from,
      LocalDate to,
      String status,
      String method,
      String carrier,
      String search
  ) {
    if (from != null && to != null && from.isAfter(to)) {
      throw new ApiException(400, "from must be on or before to");
    }
    Instant fromInclusive = from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant toExclusive;
    try {
      toExclusive = to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    } catch (RuntimeException exception) {
      throw new ApiException(400, "to is outside the supported date range");
    }
    return new ReportFilter(
        from,
        to,
        fromInclusive,
        toExclusive,
        normalizeReportValue(status),
        normalizeReportValue(method),
        normalizeReportValue(carrier),
        normalizeSearch(search)
    );
  }

  private String normalizeReportValue(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim().toUpperCase();
  }

  private String normalizeSearch(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim().toLowerCase();
  }

  private String literalLikePattern(String value) {
    return "%" + value
        .replace("!", "!!")
        .replace("%", "!%")
        .replace("_", "!_") + "%";
  }

  private FinanceFilterResponse toFilterResponse(ReportFilter filter) {
    return new FinanceFilterResponse(
        filter.from(),
        filter.to(),
        filter.fromInclusive(),
        filter.toExclusive(),
        filter.status(),
        filter.method(),
        filter.carrier(),
        filter.search()
    );
  }

  private SqlFilter paymentSqlFilter(
      ReportFilter filter,
      String paymentAlias,
      String shipmentAlias,
      String dateExpression,
      boolean includeSearch
  ) {
    StringBuilder clause = new StringBuilder(" WHERE 1 = 1");
    List<Object> arguments = new ArrayList<>();
    if (filter.fromInclusive() != null) {
      clause.append(" AND ").append(dateExpression).append(" >= ?");
      arguments.add(filter.fromInclusive().toString());
    }
    if (filter.toExclusive() != null) {
      clause.append(" AND ").append(dateExpression).append(" < ?");
      arguments.add(filter.toExclusive().toString());
    }
    if (filter.status() != null) {
      clause.append(" AND ").append(paymentAlias).append(".status = ?");
      arguments.add(filter.status());
    }
    if (filter.method() != null) {
      clause.append(" AND ").append(paymentAlias).append(".payment_method = ?");
      arguments.add(filter.method());
    }
    if (filter.carrier() != null) {
      clause.append(" AND ").append(shipmentAlias).append(".carrier_code = ?");
      arguments.add(filter.carrier());
    }
    if (includeSearch && filter.search() != null) {
      clause.append(" AND (")
          .append("LOWER(").append(paymentAlias).append(".payment_id) LIKE ? ESCAPE '!'")
          .append(" OR LOWER(").append(paymentAlias).append(".zippy_order_id) LIKE ? ESCAPE '!'")
          .append(" OR LOWER(COALESCE(").append(paymentAlias).append(".provider_reference, '')) LIKE ? ESCAPE '!'")
          .append(" OR LOWER(COALESCE(").append(paymentAlias).append(".reconciliation_reference, '')) LIKE ? ESCAPE '!'")
          .append(" OR LOWER(COALESCE(").append(paymentAlias).append(".refund_reference, '')) LIKE ? ESCAPE '!'")
          .append(" OR LOWER(COALESCE(").append(paymentAlias).append(".refund_reconciliation_reference, '')) LIKE ? ESCAPE '!'")
          .append(" OR EXISTS (SELECT 1 FROM payment_transactions search_tx")
          .append(" WHERE search_tx.payment_id = ").append(paymentAlias).append(".id")
          .append(" AND (LOWER(COALESCE(search_tx.provider_reference, '')) LIKE ? ESCAPE '!'")
          .append(" OR LOWER(COALESCE(search_tx.reconciliation_reference, '')) LIKE ? ESCAPE '!')))");
      String pattern = literalLikePattern(filter.search());
      arguments.add(pattern);
      arguments.add(pattern);
      arguments.add(pattern);
      arguments.add(pattern);
      arguments.add(pattern);
      arguments.add(pattern);
      arguments.add(pattern);
      arguments.add(pattern);
    }
    return new SqlFilter(clause.toString(), arguments);
  }

  private long countFilteredPayments(ReportFilter filter) {
    SqlFilter sqlFilter = paymentSqlFilter(filter, "p", "s", "p.created_at", true);
    Long count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM payments p
            LEFT JOIN shipments s ON s.order_id = p.order_id
            """ + sqlFilter.clause(), Long.class, sqlFilter.arguments().toArray());
    return count == null ? 0 : count;
  }

  private FinanceMetricsResponse calculateFinanceMetrics(ReportFilter filter) {
    SqlFilter cashFilter = paymentSqlFilter(filter, "p", "s", "t.created_at", true);
    CashTotals cash = jdbcTemplate.queryForObject("""
            SELECT
              COALESCE(SUM(CASE WHEN t.event_type IN ('CAPTURED', 'COD_COLLECTED') THEN t.amount ELSE 0 END), 0) AS gross_collected,
              COALESCE(SUM(CASE WHEN t.event_type = 'REFUNDED' THEN t.amount ELSE 0 END), 0) AS refunds
            FROM payment_transactions t
            JOIN payments p ON p.id = t.payment_id
            LEFT JOIN shipments s ON s.order_id = p.order_id
            """ + cashFilter.clause(),
        (rs, rowNum) -> new CashTotals(
            defaultDecimal(rs.getBigDecimal("gross_collected")),
            defaultDecimal(rs.getBigDecimal("refunds"))
        ),
        cashFilter.arguments().toArray()
    );

    SqlFilter paymentFilter = paymentSqlFilter(filter, "p", "s", "p.created_at", true);
    PaymentBuckets buckets = jdbcTemplate.queryForObject("""
            SELECT
              COUNT(CASE WHEN p.status = 'PENDING' THEN 1 END) AS pending_count,
              COALESCE(SUM(CASE WHEN p.status = 'PENDING' THEN p.amount ELSE 0 END), 0) AS pending_amount,
              COUNT(CASE WHEN p.status = 'FAILED' THEN 1 END) AS failed_count,
              COALESCE(SUM(CASE WHEN p.status = 'FAILED' THEN p.amount ELSE 0 END), 0) AS failed_amount,
              COUNT(CASE WHEN p.status = 'VOIDED' THEN 1 END) AS voided_count,
              COALESCE(SUM(CASE WHEN p.status = 'VOIDED' THEN p.amount ELSE 0 END), 0) AS voided_amount,
              COUNT(CASE WHEN p.status = 'AWAITING_COLLECTION' AND p.payment_method = 'COD' THEN 1 END) AS cod_count,
              COALESCE(SUM(CASE WHEN p.status = 'AWAITING_COLLECTION' AND p.payment_method = 'COD' THEN p.amount ELSE 0 END), 0) AS cod_amount
            FROM payments p
            LEFT JOIN shipments s ON s.order_id = p.order_id
            """ + paymentFilter.clause(),
        (rs, rowNum) -> new PaymentBuckets(
            rs.getLong("pending_count"), defaultDecimal(rs.getBigDecimal("pending_amount")),
            rs.getLong("failed_count"), defaultDecimal(rs.getBigDecimal("failed_amount")),
            rs.getLong("voided_count"), defaultDecimal(rs.getBigDecimal("voided_amount")),
            rs.getLong("cod_count"), defaultDecimal(rs.getBigDecimal("cod_amount"))
        ),
        paymentFilter.arguments().toArray()
    );

    ShipmentTotals shipmentTotals = shipmentTotals(filter);
    CashTotals safeCash = cash == null ? new CashTotals(ZERO, ZERO) : cash;
    PaymentBuckets safeBuckets = buckets == null ? PaymentBuckets.empty() : buckets;
    return new FinanceMetricsResponse(
        "INR",
        safeCash.grossCollected(),
        safeCash.refunds(),
        safeCash.grossCollected().subtract(safeCash.refunds()).setScale(2, RoundingMode.HALF_UP),
        new FinanceAmountResponse(safeBuckets.pendingCount(), safeBuckets.pendingAmount()),
        new FinanceAmountResponse(safeBuckets.failedCount(), safeBuckets.failedAmount()),
        new FinanceAmountResponse(safeBuckets.voidedCount(), safeBuckets.voidedAmount()),
        new FinanceAmountResponse(safeBuckets.codCount(), safeBuckets.codAmount()),
        shipmentTotals.shippingCost(),
        shipmentTotals.costedCount(),
        shipmentTotals.activeCount()
    );
  }

  private ShipmentTotals shipmentTotals(ReportFilter filter) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    List<Object> arguments = new ArrayList<>();
    if (filter.fromInclusive() != null) {
      where.append(" AND s.created_at >= ?");
      arguments.add(filter.fromInclusive().toString());
    }
    if (filter.toExclusive() != null) {
      where.append(" AND s.created_at < ?");
      arguments.add(filter.toExclusive().toString());
    }
    if (filter.carrier() != null) {
      where.append(" AND s.carrier_code = ?");
      arguments.add(filter.carrier());
    }
    appendShipmentPaymentExistsFilter(where, arguments, filter);
    ShipmentTotals result = jdbcTemplate.queryForObject("""
            SELECT
              COALESCE(SUM(CASE WHEN s.current_status NOT IN ('CANCELLED', 'RTO') THEN s.quoted_amount ELSE 0 END), 0) AS shipping_cost,
              COUNT(CASE WHEN s.current_status NOT IN ('CANCELLED', 'RTO') THEN 1 END) AS costed_count,
              COUNT(CASE WHEN s.current_status NOT IN ('CANCELLED', 'RTO', 'DELIVERED') THEN 1 END) AS active_count
            FROM shipments s
            """ + where,
        (rs, rowNum) -> new ShipmentTotals(
            defaultDecimal(rs.getBigDecimal("shipping_cost")),
            rs.getLong("costed_count"),
            rs.getLong("active_count")
        ),
        arguments.toArray()
    );
    return result == null ? new ShipmentTotals(ZERO, 0, 0) : result;
  }

  private void appendShipmentPaymentExistsFilter(
      StringBuilder where,
      List<Object> arguments,
      ReportFilter filter
  ) {
    if (filter.status() == null && filter.method() == null && filter.search() == null) {
      return;
    }
    where.append(" AND EXISTS (SELECT 1 FROM payments ep WHERE ep.order_id = s.order_id")
        .append(" AND ep.id = (SELECT MAX(canonical_payment.id) FROM payments canonical_payment")
        .append(" WHERE canonical_payment.order_id = s.order_id)");
    if (filter.status() != null) {
      where.append(" AND ep.status = ?");
      arguments.add(filter.status());
    }
    if (filter.method() != null) {
      where.append(" AND ep.payment_method = ?");
      arguments.add(filter.method());
    }
    if (filter.search() != null) {
      String pattern = literalLikePattern(filter.search());
      where.append(" AND (LOWER(ep.payment_id) LIKE ? ESCAPE '!' OR LOWER(ep.zippy_order_id) LIKE ? ESCAPE '!'")
          .append(" OR LOWER(COALESCE(ep.provider_reference, '')) LIKE ? ESCAPE '!'")
          .append(" OR LOWER(COALESCE(ep.reconciliation_reference, '')) LIKE ? ESCAPE '!'")
          .append(" OR LOWER(COALESCE(ep.refund_reference, '')) LIKE ? ESCAPE '!'")
          .append(" OR LOWER(COALESCE(ep.refund_reconciliation_reference, '')) LIKE ? ESCAPE '!'")
          .append(" OR EXISTS (SELECT 1 FROM payment_transactions search_tx")
          .append(" WHERE search_tx.payment_id = ep.id")
          .append(" AND (LOWER(COALESCE(search_tx.provider_reference, '')) LIKE ? ESCAPE '!'")
          .append(" OR LOWER(COALESCE(search_tx.reconciliation_reference, '')) LIKE ? ESCAPE '!')))");
      arguments.add(pattern);
      arguments.add(pattern);
      arguments.add(pattern);
      arguments.add(pattern);
      arguments.add(pattern);
      arguments.add(pattern);
      arguments.add(pattern);
      arguments.add(pattern);
    }
    where.append(')');
  }

  private List<DailyFinanceTrendResponse> dailyTrend(ReportFilter filter) {
    SqlFilter sqlFilter = paymentSqlFilter(filter, "p", "s", "t.created_at", false);
    return jdbcTemplate.query("""
            SELECT SUBSTRING(t.created_at, 1, 10) AS report_date,
                   COALESCE(SUM(CASE WHEN t.event_type IN ('CAPTURED', 'COD_COLLECTED') THEN t.amount ELSE 0 END), 0) AS gross_collected,
                   COALESCE(SUM(CASE WHEN t.event_type = 'REFUNDED' THEN t.amount ELSE 0 END), 0) AS refunds,
                   COUNT(*) AS transactions
            FROM payment_transactions t
            JOIN payments p ON p.id = t.payment_id
            LEFT JOIN shipments s ON s.order_id = p.order_id
            """ + sqlFilter.clause()
            + " AND t.event_type IN ('CAPTURED', 'COD_COLLECTED', 'REFUNDED')"
            + " GROUP BY SUBSTRING(t.created_at, 1, 10) ORDER BY report_date",
        (rs, rowNum) -> {
          BigDecimal gross = defaultDecimal(rs.getBigDecimal("gross_collected"));
          BigDecimal refunds = defaultDecimal(rs.getBigDecimal("refunds"));
          return new DailyFinanceTrendResponse(
              LocalDate.parse(rs.getString("report_date")),
              gross,
              refunds,
              gross.subtract(refunds).setScale(2, RoundingMode.HALF_UP),
              rs.getLong("transactions")
          );
        },
        sqlFilter.arguments().toArray()
    );
  }

  private List<Map<String, Object>> paymentStatusBreakdown(ReportFilter filter) {
    SqlFilter sqlFilter = paymentSqlFilter(filter, "p", "s", "p.created_at", false);
    return jdbcTemplate.query("""
            SELECT p.status AS label, COUNT(*) AS count, COALESCE(SUM(p.amount), 0) AS total_amount
            FROM payments p
            LEFT JOIN shipments s ON s.order_id = p.order_id
            """ + sqlFilter.clause() + " GROUP BY p.status ORDER BY count DESC, p.status",
        (rs, rowNum) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("label", rs.getString("label"));
          row.put("count", rs.getLong("count"));
          row.put("totalAmount", defaultDecimal(rs.getBigDecimal("total_amount")));
          return row;
        },
        sqlFilter.arguments().toArray()
    );
  }

  private List<Map<String, Object>> paymentMethodBreakdown(ReportFilter filter) {
    Map<String, BreakdownTotals> totals = new LinkedHashMap<>();
    SqlFilter paymentFilter = paymentSqlFilter(filter, "p", "s", "p.created_at", false);
    jdbcTemplate.query("""
            SELECT p.payment_method AS label, COUNT(*) AS count,
                   COALESCE(SUM(p.amount), 0) AS total_amount,
                   COALESCE(SUM(CASE WHEN p.status = 'AWAITING_COLLECTION' THEN p.amount ELSE 0 END), 0) AS outstanding
            FROM payments p
            LEFT JOIN shipments s ON s.order_id = p.order_id
            """ + paymentFilter.clause() + " GROUP BY p.payment_method ORDER BY p.payment_method",
        rs -> {
          BreakdownTotals value = totals.computeIfAbsent(rs.getString("label"), ignored -> new BreakdownTotals());
          value.count = rs.getLong("count");
          value.totalAmount = defaultDecimal(rs.getBigDecimal("total_amount"));
          value.outstanding = defaultDecimal(rs.getBigDecimal("outstanding"));
        }, paymentFilter.arguments().toArray());

    SqlFilter cashFilter = paymentSqlFilter(filter, "p", "s", "t.created_at", false);
    jdbcTemplate.query("""
            SELECT p.payment_method AS label,
                   COALESCE(SUM(CASE WHEN t.event_type IN ('CAPTURED', 'COD_COLLECTED') THEN t.amount ELSE 0 END), 0) AS gross,
                   COALESCE(SUM(CASE WHEN t.event_type = 'REFUNDED' THEN t.amount ELSE 0 END), 0) AS refunds
            FROM payment_transactions t
            JOIN payments p ON p.id = t.payment_id
            LEFT JOIN shipments s ON s.order_id = p.order_id
            """ + cashFilter.clause()
            + " AND t.event_type IN ('CAPTURED', 'COD_COLLECTED', 'REFUNDED')"
            + " GROUP BY p.payment_method ORDER BY p.payment_method",
        rs -> {
          BreakdownTotals value = totals.computeIfAbsent(rs.getString("label"), ignored -> new BreakdownTotals());
          value.gross = defaultDecimal(rs.getBigDecimal("gross"));
          value.refunds = defaultDecimal(rs.getBigDecimal("refunds"));
        }, cashFilter.arguments().toArray());

    return totals.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> {
      BreakdownTotals value = entry.getValue();
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("label", entry.getKey());
      row.put("count", value.count);
      row.put("totalAmount", value.totalAmount);
      row.put("grossCollected", value.gross);
      row.put("refunds", value.refunds);
      row.put("netCollected", value.gross.subtract(value.refunds).setScale(2, RoundingMode.HALF_UP));
      row.put("outstanding", value.outstanding);
      return row;
    }).toList();
  }

  private List<Map<String, Object>> carrierBreakdown(ReportFilter filter) {
    Map<String, CarrierTotals> totals = new LinkedHashMap<>();
    StringBuilder shipmentWhere = new StringBuilder(" WHERE 1 = 1");
    List<Object> shipmentArguments = new ArrayList<>();
    if (filter.fromInclusive() != null) {
      shipmentWhere.append(" AND s.created_at >= ?");
      shipmentArguments.add(filter.fromInclusive().toString());
    }
    if (filter.toExclusive() != null) {
      shipmentWhere.append(" AND s.created_at < ?");
      shipmentArguments.add(filter.toExclusive().toString());
    }
    if (filter.carrier() != null) {
      shipmentWhere.append(" AND s.carrier_code = ?");
      shipmentArguments.add(filter.carrier());
    }
    appendShipmentPaymentExistsFilter(shipmentWhere, shipmentArguments, filter);
    jdbcTemplate.query("""
            SELECT s.carrier_code AS carrier, COUNT(*) AS shipments,
                   COUNT(CASE WHEN s.current_status NOT IN ('CANCELLED', 'RTO', 'DELIVERED') THEN 1 END) AS active_shipments,
                   COALESCE(SUM(CASE WHEN s.current_status NOT IN ('CANCELLED', 'RTO') THEN s.quoted_amount ELSE 0 END), 0) AS shipping_cost
            FROM shipments s
            """ + shipmentWhere + " GROUP BY s.carrier_code ORDER BY s.carrier_code",
        rs -> {
          CarrierTotals value = totals.computeIfAbsent(rs.getString("carrier"), ignored -> new CarrierTotals());
          value.shipments = rs.getLong("shipments");
          value.activeShipments = rs.getLong("active_shipments");
          value.shippingCost = defaultDecimal(rs.getBigDecimal("shipping_cost"));
        }, shipmentArguments.toArray());

    SqlFilter cashFilter = paymentSqlFilter(filter, "p", "s", "t.created_at", false);
    jdbcTemplate.query("""
            SELECT s.carrier_code AS carrier,
                   COALESCE(SUM(CASE WHEN t.event_type IN ('CAPTURED', 'COD_COLLECTED') THEN t.amount ELSE 0 END), 0) AS gross,
                   COALESCE(SUM(CASE WHEN t.event_type = 'REFUNDED' THEN t.amount ELSE 0 END), 0) AS refunds
            FROM payment_transactions t
            JOIN payments p ON p.id = t.payment_id
            JOIN shipments s ON s.order_id = p.order_id
            """ + cashFilter.clause()
            + " AND t.event_type IN ('CAPTURED', 'COD_COLLECTED', 'REFUNDED')"
            + " GROUP BY s.carrier_code ORDER BY s.carrier_code",
        rs -> {
          CarrierTotals value = totals.computeIfAbsent(rs.getString("carrier"), ignored -> new CarrierTotals());
          value.gross = defaultDecimal(rs.getBigDecimal("gross"));
          value.refunds = defaultDecimal(rs.getBigDecimal("refunds"));
        }, cashFilter.arguments().toArray());

    SqlFilter outstandingFilter = paymentSqlFilter(filter, "p", "s", "p.created_at", false);
    jdbcTemplate.query("""
            SELECT s.carrier_code AS carrier,
                   COALESCE(SUM(CASE WHEN p.status = 'AWAITING_COLLECTION' AND p.payment_method = 'COD' THEN p.amount ELSE 0 END), 0) AS outstanding
            FROM payments p
            JOIN shipments s ON s.order_id = p.order_id
            """ + outstandingFilter.clause() + " GROUP BY s.carrier_code ORDER BY s.carrier_code",
        rs -> {
          totals.computeIfAbsent(rs.getString("carrier"), ignored -> new CarrierTotals()).outstanding =
              defaultDecimal(rs.getBigDecimal("outstanding"));
        },
        outstandingFilter.arguments().toArray());

    return totals.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> {
      CarrierTotals value = entry.getValue();
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("carrier", entry.getKey());
      row.put("shipments", value.shipments);
      row.put("activeShipments", value.activeShipments);
      row.put("shippingCost", value.shippingCost);
      row.put("grossCollected", value.gross);
      row.put("refunds", value.refunds);
      row.put("netCollected", value.gross.subtract(value.refunds).setScale(2, RoundingMode.HALF_UP));
      row.put("codOutstanding", value.outstanding);
      return row;
    }).toList();
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
    OrderRow order = findOrderByZippyOrderIdForUpdate(orderId);
    if (!STATUS_ORDER_CREATED.equals(order.orderStatus())
        && !STATUS_CARRIER_SELECTED.equals(order.orderStatus())) {
      throw new ApiException(409, "Carrier cannot be selected for an order in status " + order.orderStatus());
    }
    ShippingQuote quote = findQuote(order.id(), request.carrierCode(), request.serviceCode());
    if (quote.totalCharge().compareTo(request.quotedAmount().setScale(2, RoundingMode.HALF_UP)) != 0) {
      throw new ApiException(409, "Quoted amount does not match stored quote");
    }

    String now = now();
    String selectedQuoteJson = toJson(quoteToMap(quote));
    ShipmentRow existingShipment = findShipmentForUpdate(order.id());
    if (existingShipment != null && !STATUS_CARRIER_SELECTED.equals(existingShipment.currentStatus())) {
      throw new ApiException(409, "Carrier cannot be changed after shipment creation");
    }
    if (existingShipment != null
        && existingShipment.carrierCode().equals(quote.carrierCode())
        && existingShipment.selectedServiceCode().equals(quote.serviceCode())
        && existingShipment.quotedAmount().compareTo(quote.totalCharge()) == 0) {
      return selectedCarrierResponse(orderId, existingShipment);
    }
    if (existingShipment != null
        && "PREPAID".equalsIgnoreCase(order.paymentType())
        && hasAnyPrepaidPayment(order.id())) {
      throw new ApiException(409, "Carrier cannot be changed after a prepaid payment intent exists");
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
    ShipmentRow shipment = findShipmentForUpdate(order.id());
    return selectedCarrierResponse(orderId, shipment);
  }

  private Map<String, Object> selectedCarrierResponse(String orderId, ShipmentRow shipment) {
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
    ShipmentRow shipment = findShipmentForUpdate(order.id());
    if (shipment == null) {
      throw new ApiException(409, "Carrier not selected");
    }
    if (!STATUS_CARRIER_SELECTED.equals(shipment.currentStatus())) {
      throw new ApiException(409, "Shipment has already been created or is not ready for creation");
    }
    if ("PREPAID".equalsIgnoreCase(order.paymentType())
        && !hasMatchingSucceededPrepaidPayment(order.id(), shipment.quotedAmount())) {
      throw new ApiException(409, "A successful prepaid payment matching the selected shipment charge is required");
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
      scheduleAutomationAfterCommit(updatedShipment.id(), updatedShipment.carrierCode());
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
    ShipmentRow shipment = findShipmentForUpdate(order.id());
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

  @Transactional
  public Map<String, Object> handleWebhook(String carrierCode, JsonNode payload) {
    String normalizedCarrier = carrierCode == null ? "" : carrierCode.trim().toUpperCase();
    if (!List.of("FASTSHIP", "QUICKEXPRESS", "RELIABLE").contains(normalizedCarrier)) {
      throw new ApiException(404, "Unsupported carrier webhook: " + carrierCode);
    }

    CarrierEvent event = mapWebhook(normalizedCarrier, payload);
    ShipmentRow candidate = findShipmentByTracking(
        normalizedCarrier,
        event.trackingKey(),
        event.trackingField()
    );
    if (candidate == null) {
      throw new ApiException(404, "Unknown tracking number");
    }
    OrderRow order = findOrderByIdForUpdate(candidate.orderId());
    ShipmentRow shipment = findShipmentByTrackingForUpdate(
        normalizedCarrier,
        event.trackingKey(),
        event.trackingField()
    );
    if (shipment == null || shipment.orderId() != order.id()) {
      throw new ApiException(404, "Unknown tracking number");
    }
    return recordCarrierEvent(shipment, payload, now(), event);
  }

  @Transactional
  public Map<String, Object> advanceMockCarrier(String orderId) {
    ShipmentRow shipment = requireShipment(orderId);
    String nextStatus = nextAutomatedStatus(shipment.currentStatus());
    if (nextStatus == null) {
      return Map.of("status", shipment.currentStatus(), "advanced", false);
    }
    JsonNode payload = buildWebhookPayload(shipment, nextStatus);
    return handleWebhook(shipment.carrierCode(), payload);
  }

  @Transactional
  public Map<String, Object> mockDeliveryFailure(String orderId) {
    ShipmentRow shipment = requireShipment(orderId);
    if (!STATUS_OUT_FOR_DELIVERY.equals(shipment.currentStatus())) {
      throw new ApiException(409, "Delivery failure can only be simulated from OUT_FOR_DELIVERY");
    }
    return handleWebhook(shipment.carrierCode(), buildWebhookPayload(shipment, STATUS_DELIVERY_FAILED));
  }

  @Transactional
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
    long offset = mockPayloadOffset(payload);
    String shipmentId = mockIdentifier("FS-", 700001L, offset, 6);
    String trackingNumber = mockIdentifier("FST", 123456789L, offset, 9);
    return Map.of(
        "success", true,
        "shipment_id", shipmentId,
        "tracking_number", trackingNumber,
        "label_url", "http://mock-fastship/labels/" + trackingNumber + ".pdf",
        "status", "BOOKED"
    );
  }

  public Map<String, Object> mockQuickexpressShipment(JsonNode payload) {
    long offset = mockPayloadOffset(payload);
    return Map.of(
        "bookingStatus", "CONFIRMED",
        "booking", Map.of(
            "bookingId", mockIdentifier("QE-B-", 800001L, offset, 6),
            "awb", mockIdentifier("QE", 987654321L, offset, 9),
            "currentState", "SHIPMENT_CREATED"
        )
    );
  }

  public Map<String, Object> mockReliableShipment(JsonNode payload) {
    long offset = mockPayloadOffset(payload);
    return Map.of(
        "result", "ACCEPTED",
        "deliveryOrder", Map.of(
            "id", mockIdentifier("RC-DO-", 600001L, offset, 6),
            "trackingCode", mockIdentifier("RC", 1122334455L, offset, 10)
        ),
        "message", "Shipment successfully registered"
    );
  }

  public void setRuntimeFlags(Map<String, Object> payload) {
    if (payload.containsKey("fastshipRateDelayMs")) {
      fastshipRateDelayMs.set(runtimeDelay(payload.get("fastshipRateDelayMs"), "fastshipRateDelayMs"));
    }
    if (payload.containsKey("quickexpressRateDelayMs")) {
      quickexpressRateDelayMs.set(runtimeDelay(payload.get("quickexpressRateDelayMs"), "quickexpressRateDelayMs"));
    }
    if (payload.containsKey("reliableRateDelayMs")) {
      reliableRateDelayMs.set(runtimeDelay(payload.get("reliableRateDelayMs"), "reliableRateDelayMs"));
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
    ExecutorCompletionService<ShippingQuote> completionService = new ExecutorCompletionService<>(carrierExecutor);
    List<Future<ShippingQuote>> futures = new ArrayList<>();
    submitCarrierQuote(completionService, futures, () -> {
      sleep(fastshipRateDelayMs.get());
      if (fastshipRateFailure.get()) {
        throw new IllegalStateException("FastShip rate service unavailable");
      }
      return fastShipQuote(order);
    });
    submitCarrierQuote(completionService, futures, () -> {
      sleep(quickexpressRateDelayMs.get());
      if (quickexpressRateFailure.get()) {
        throw new IllegalStateException("QuickExpress rate service unavailable");
      }
      return quickExpressQuote(order);
    });
    submitCarrierQuote(completionService, futures, () -> {
      sleep(reliableRateDelayMs.get());
      if (reliableRateFailure.get()) {
        throw new IllegalStateException("ReliableCourier rate service unavailable");
      }
      return reliableQuote(order);
    });

    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEFAULT_CARRIER_TIMEOUT_MS);
    List<ShippingQuote> quotes = new ArrayList<>();
    int remaining = futures.size();
    while (remaining > 0) {
      long remainingNanos = deadline - System.nanoTime();
      if (remainingNanos <= 0) {
        break;
      }
      try {
        Future<ShippingQuote> completed = completionService.poll(remainingNanos, TimeUnit.NANOSECONDS);
        if (completed == null) {
          break;
        }
        remaining--;
        quotes.add(completed.get());
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception exception) {
        LOGGER.warn("Carrier quote request failed: {}", exception.getMessage());
      }
    }
    futures.stream().filter(future -> !future.isDone()).forEach(future -> future.cancel(true));
    return sortQuotes(quotes, "lowest");
  }

  private void submitCarrierQuote(
      ExecutorCompletionService<ShippingQuote> completionService,
      List<Future<ShippingQuote>> futures,
      Supplier<ShippingQuote> supplier
  ) {
    try {
      futures.add(completionService.submit(supplier::get));
    } catch (RejectedExecutionException exception) {
      LOGGER.warn("Carrier quote request rejected because the worker pool is saturated");
    }
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

  private ShipmentRow findShipmentForUpdate(long orderId) {
    List<ShipmentRow> shipments = jdbcTemplate.query(
        "SELECT * FROM shipments WHERE order_id = ? FOR UPDATE",
        shipmentRowMapper,
        orderId
    );
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

  private Map<Long, ShipmentRow> findShipmentsByOrderIds(List<Long> orderIds) {
    Map<Long, ShipmentRow> shipmentsByOrderId = new LinkedHashMap<>();
    if (orderIds.isEmpty()) {
      return shipmentsByOrderId;
    }
    String placeholders = String.join(",", Collections.nCopies(orderIds.size(), "?"));
    List<ShipmentRow> shipments = jdbcTemplate.query(
        "SELECT * FROM shipments WHERE order_id IN (" + placeholders + ")",
        shipmentRowMapper,
        orderIds.toArray()
    );
    for (ShipmentRow shipment : shipments) {
      shipmentsByOrderId.put(shipment.orderId(), shipment);
    }
    return shipmentsByOrderId;
  }

  private long countRows(String table) {
    Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    return count == null ? 0L : count;
  }

  private Map<String, Object> orderHistoryToMap(OrderRow order, ShipmentRow shipment) {
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

  private Map<String, Object> findIdempotentReplay(String idempotencyKey, String requestHash) {
    if (idempotencyKey == null) {
      return null;
    }
    IdempotencyRecord existing = findIdempotencyRecord(idempotencyKey);
    if (existing == null) {
      return null;
    }
    if (!Objects.equals(existing.requestHash(), requestHash)) {
      throw new ApiException(409, "Idempotency key reused with a different request");
    }
    return parseMap(existing.responseJson());
  }

  private void ensureIdempotencyLockRow(String idempotencyKey) {
    isolatedTransactionTemplate.executeWithoutResult(status -> jdbcTemplate.update("""
        MERGE INTO idempotency_locks (idempotency_key, created_at)
        KEY(idempotency_key)
        VALUES (?, ?)
        """, idempotencyKey, now()));
  }

  private void lockIdempotencyKey(String idempotencyKey) {
    String lockedKey = jdbcTemplate.queryForObject(
        "SELECT idempotency_key FROM idempotency_locks WHERE idempotency_key = ? FOR UPDATE",
        String.class,
        idempotencyKey
    );
    if (!idempotencyKey.equals(lockedKey)) {
      throw new IllegalStateException("Idempotency lock could not be acquired");
    }
  }

  private void lockPaymentOperationKey(String idempotencyKey) {
    if (idempotencyKey == null) {
      return;
    }
    String lockKey = "PAYMENT:" + sha256(idempotencyKey);
    ensureIdempotencyLockRow(lockKey);
    lockIdempotencyKey(lockKey);
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

    ShipmentRow currentShipment = shipment;
    if (shipmentEventExists(currentShipment.id(), event.carrierEventId())) {
      return Map.of(
          "duplicate", true,
          "status", currentShipment.currentStatus(),
          "carrierEventId", event.carrierEventId()
      );
    }

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
    if (!STATUS_RTO.equals(shipmentStatus) && !STATUS_CANCELLED.equals(shipmentStatus)) {
      return;
    }

    List<PaymentRow> payments = jdbcTemplate.query("""
        SELECT * FROM payments
        WHERE order_id = ? AND status IN ('AWAITING_COLLECTION', 'PENDING', 'SUCCEEDED')
        ORDER BY id
        FOR UPDATE
        """, paymentRowMapper, orderId);
    String timestamp = now();
    for (PaymentRow payment : payments) {
      String resultingStatus;
      String eventType;
      if ("COD".equals(payment.paymentMethod()) && "AWAITING_COLLECTION".equals(payment.status())) {
        resultingStatus = "VOIDED";
        eventType = "AUTO_VOIDED";
      } else if ("PREPAID".equals(payment.paymentMethod()) && "PENDING".equals(payment.status())) {
        resultingStatus = "CANCELLED";
        eventType = "AUTO_CANCELLED";
      } else if ("PREPAID".equals(payment.paymentMethod()) && "SUCCEEDED".equals(payment.status())) {
        resultingStatus = "REFUND_PENDING";
        eventType = "REFUND_PENDING";
      } else {
        continue;
      }
      int updated = jdbcTemplate.update(
          "UPDATE payments SET status = ?, updated_at = ? WHERE id = ? AND status = ?",
          resultingStatus,
          timestamp,
          payment.id(),
          payment.status()
      );
      requireTransition(updated, "Payment was changed by another operation");
      recordPaymentTransaction(
          payment,
          eventType,
          payment.status(),
          resultingStatus,
          payment.amount(),
          "ZIPPY_SYSTEM",
          "SHIPMENT-" + shipmentStatus + "-" + payment.paymentId(),
          null,
          "Shipment moved to " + shipmentStatus,
          "ZIPPY_SYSTEM",
          null,
          null,
          timestamp
      );
    }
  }

  private boolean shipmentEventExists(long shipmentId, String carrierEventId) {
    Long count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM shipment_events WHERE shipment_id = ? AND carrier_event_id = ?",
        Long.class,
        shipmentId,
        carrierEventId
    );
    return count != null && count > 0;
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

  private void scheduleAutomationAfterCommit(long shipmentId, String carrierCode) {
    Runnable scheduling = () -> scheduleAutomation(shipmentId, carrierCode);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          scheduling.run();
        }
      });
    } else {
      scheduling.run();
    }
  }

  private void scheduleAutomation(long shipmentId, String carrierCode) {
    try {
      automationScheduler.schedule(() -> {
        boolean shouldContinue = false;
        try {
          shouldContinue = Boolean.TRUE.equals(transactionTemplate.execute(ignored -> {
            ShipmentRow shipment = findShipmentById(shipmentId);
            if (shipment == null) {
              return false;
            }
            String nextStatus = nextAutomatedStatus(shipment.currentStatus());
            if (nextStatus == null) {
              return false;
            }
            JsonNode payload = buildWebhookPayload(shipment, nextStatus);
            handleWebhook(carrierCode, payload);
            return !STATUS_DELIVERED.equals(nextStatus) && !STATUS_RTO.equals(nextStatus);
          }));
        } catch (Exception exception) {
          LOGGER.debug("Automated shipment update was skipped: {}", exception.getMessage());
        }
        if (shouldContinue) {
          scheduleAutomation(shipmentId, carrierCode);
        }
      }, DEFAULT_AUTOMATION_DELAY_MS, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException exception) {
      LOGGER.warn("Shipment automation task was rejected for shipment {}", shipmentId);
    }
  }

  private ShipmentRow findShipmentById(long id) {
    try {
      return jdbcTemplate.queryForObject("SELECT * FROM shipments WHERE id = ?", shipmentRowMapper, id);
    } catch (Exception exception) {
      return null;
    }
  }

  private ShipmentRow findShipmentByTracking(
      String carrierCode,
      String trackingKey,
      String trackingField
  ) {
    String column = "carrier_shipment_id".equals(trackingField) ? "carrier_shipment_id" : "tracking_number";
    List<ShipmentRow> shipments = jdbcTemplate.query(
        "SELECT * FROM shipments WHERE carrier_code = ? AND " + column + " = ?",
        shipmentRowMapper,
        carrierCode,
        trackingKey
    );
    return shipments.isEmpty() ? null : shipments.getFirst();
  }

  private ShipmentRow findShipmentByTrackingForUpdate(
      String carrierCode,
      String trackingKey,
      String trackingField
  ) {
    String column = "carrier_shipment_id".equals(trackingField) ? "carrier_shipment_id" : "tracking_number";
    List<ShipmentRow> shipments = jdbcTemplate.query(
        "SELECT * FROM shipments WHERE carrier_code = ? AND " + column + " = ? FOR UPDATE",
        shipmentRowMapper,
        carrierCode,
        trackingKey
    );
    return shipments.isEmpty() ? null : shipments.getFirst();
  }

  private Map<String, Object> initialWebhookPayloadData(ShipmentRow shipment, OrderRow order) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("event_id", mockEventId(shipment, STATUS_SHIPMENT_CREATED));
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
    payload.put("event_id", mockEventId(shipment, normalizedStatus));
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

  private String mockEventId(ShipmentRow shipment, String status) {
    return shipment.carrierCode() + "-" + shipment.id() + "-" + status + "-" + UUID.randomUUID();
  }

  private CarrierEvent mapWebhook(String carrierCode, JsonNode payload) {
    if (payload == null || !payload.isObject()) {
      throw new ApiException(400, "Invalid webhook payload", List.of("JSON body must be an object"));
    }
    return switch (carrierCode) {
      case "FASTSHIP" -> mapFastshipWebhook(payload);
      case "QUICKEXPRESS" -> mapQuickexpressWebhook(payload);
      default -> mapReliableWebhook(payload);
    };
  }

  private CarrierEvent mapFastshipWebhook(JsonNode payload) {
    String status = requiredText(payload.path("event_code"), "event_code", 32);
    String eventTime = requiredEventTime(payload.path("event_time"), "event_time");
    return new CarrierEvent(
        requiredText(payload.path("shipment_id"), "shipment_id", 64),
        "carrier_shipment_id",
        carrierEventId("FASTSHIP", status, eventTime, firstText(payload, "event_id", "eventId")),
        status,
        mapFastshipStatus(status),
        requiredText(payload.path("event_description"), "event_description", 255),
        optionalText(payload.path("location"), "location", 120),
        eventTime
    );
  }

  private CarrierEvent mapQuickexpressWebhook(JsonNode payload) {
    JsonNode eventNode = payload.path("event");
    if (!eventNode.isObject()) {
      throw new ApiException(400, "Invalid webhook payload", List.of("event must be an object"));
    }
    String status = requiredText(eventNode.path("type"), "event.type", 32);
    String eventTime = requiredEventTime(eventNode.path("occurredAt"), "event.occurredAt");
    String explicitEventId = firstText(payload, "event_id", "eventId");
    if (explicitEventId == null) {
      explicitEventId = optionalText(eventNode.path("id"), "event.id", 120);
    }
    return new CarrierEvent(
        requiredText(payload.path("awb"), "awb", 64),
        "tracking_number",
        carrierEventId("QUICKEXPRESS", status, eventTime, explicitEventId),
        status,
        mapQuickStatus(status),
        requiredText(eventNode.path("message"), "event.message", 255),
        optionalText(payload.path("facility").path("city"), "facility.city", 120),
        eventTime
    );
  }

  private CarrierEvent mapReliableWebhook(JsonNode payload) {
    JsonNode statusNode = payload.path("statusId");
    if (!statusNode.isIntegralNumber() || !statusNode.canConvertToInt() || statusNode.asInt() <= 0) {
      throw new ApiException(400, "Invalid webhook payload", List.of("statusId must be a positive integer"));
    }
    int statusId = statusNode.asInt();
    String status = String.valueOf(statusId);
    String eventTime = requiredEventTime(payload.path("updatedOn"), "updatedOn");
    return new CarrierEvent(
        requiredText(payload.path("trackingCode"), "trackingCode", 64),
        "tracking_number",
        carrierEventId("RELIABLE", status, eventTime, firstText(payload, "event_id", "eventId")),
        status,
        mapReliableStatus(statusId),
        requiredText(payload.path("statusText"), "statusText", 255),
        optionalText(payload.path("proofOfDelivery").path("deliveryLocation"), "proofOfDelivery.deliveryLocation", 120),
        eventTime
    );
  }

  private String requiredText(JsonNode node, String field, int maxLength) {
    String value = optionalText(node, field, maxLength);
    if (value == null) {
      throw new ApiException(400, "Invalid webhook payload", List.of(field + " is required"));
    }
    return value;
  }

  private String optionalText(JsonNode node, String field, int maxLength) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    if (!node.isTextual()) {
      throw new ApiException(400, "Invalid webhook payload", List.of(field + " must be a string"));
    }
    String value = node.asText().trim();
    if (value.isEmpty()) {
      return null;
    }
    if (value.length() > maxLength) {
      throw new ApiException(400, "Invalid webhook payload", List.of(field + " exceeds " + maxLength + " characters"));
    }
    return value;
  }

  private String firstText(JsonNode payload, String... fields) {
    for (String field : fields) {
      String value = optionalText(payload.path(field), field, 120);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private String requiredEventTime(JsonNode node, String field) {
    String value = requiredText(node, field, 40);
    try {
      return OffsetDateTime.parse(value).toInstant().toString();
    } catch (DateTimeParseException exception) {
      throw new ApiException(400, "Invalid webhook payload", List.of(field + " must be an ISO-8601 timestamp"));
    }
  }

  private String carrierEventId(String carrier, String status, String eventTime, String explicitEventId) {
    if (explicitEventId != null) {
      return explicitEventId;
    }
    return carrier + ":" + status + ":" + eventTime;
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
    BigDecimal surfaceBase = money(95);
    BigDecimal surfaceHandling = money(10);
    BigDecimal surfaceTax = tax(surfaceBase.add(surfaceHandling).add(cod));
    BigDecimal airBase = money(130);
    BigDecimal airHandling = money(12);
    BigDecimal airTax = tax(airBase.add(airHandling).add(cod));
    return Map.of(
        "code", 200,
        "data", List.of(
            Map.of(
                "id", "RC-SURFACE",
                "name", "Reliable Surface",
                "rate", Map.of(
                    "base", surfaceBase,
                    "handling", surfaceHandling,
                    "cashCollectionFee", cod,
                    "taxAmount", surfaceTax,
                    "grandTotal", surfaceBase.add(surfaceHandling).add(cod).add(surfaceTax)
                ),
                "eta", "4-5 business days"
            ),
            Map.of(
                "id", "RC-AIR",
                "name", "Reliable Air",
                "rate", Map.of(
                    "base", airBase,
                    "handling", airHandling,
                    "cashCollectionFee", cod,
                    "taxAmount", airTax,
                    "grandTotal", airBase.add(airHandling).add(cod).add(airTax)
                ),
                "eta", "2-3 business days"
            )
        )
    );
  }

  private BigDecimal tax(BigDecimal taxableAmount) {
    return taxableAmount.multiply(BigDecimal.valueOf(0.18)).setScale(2, RoundingMode.HALF_UP);
  }

  private Map<String, Object> fastShipCreateShipment(OrderRow order, ShippingQuote quote) {
    long offset = mockOrderOffset(order);
    String shipmentId = mockIdentifier("FS-", 700001L, offset, 6);
    String trackingNumber = mockIdentifier("FST", 123456789L, offset, 9);
    return Map.of(
        "success", true,
        "shipment_id", shipmentId,
        "tracking_number", trackingNumber,
        "label_url", "http://mock-fastship/labels/" + trackingNumber + ".pdf",
        "status", "BOOKED"
    );
  }

  private Map<String, Object> quickExpressCreateShipment(OrderRow order, ShippingQuote quote) {
    long offset = mockOrderOffset(order);
    return Map.of(
        "bookingStatus", "CONFIRMED",
        "booking", Map.of(
            "bookingId", mockIdentifier("QE-B-", 800001L, offset, 6),
            "awb", mockIdentifier("QE", 987654321L, offset, 9),
            "currentState", "SHIPMENT_CREATED"
        )
    );
  }

  private Map<String, Object> reliableShipment(OrderRow order, ShippingQuote quote) {
    long offset = mockOrderOffset(order);
    return Map.of(
        "result", "ACCEPTED",
        "deliveryOrder", Map.of(
            "id", mockIdentifier("RC-DO-", 600001L, offset, 6),
            "trackingCode", mockIdentifier("RC", 1122334455L, offset, 10)
        ),
        "message", "Shipment successfully registered"
    );
  }

  private long mockOrderOffset(OrderRow order) {
    String orderId = order.zippyOrderId();
    int separator = orderId.lastIndexOf('-');
    if (separator >= 0 && separator + 1 < orderId.length()) {
      try {
        return Math.max(0L, Long.parseLong(orderId.substring(separator + 1)) - 10001L);
      } catch (NumberFormatException ignored) {
        // Fall back to the stable database identity below.
      }
    }
    return Math.max(0L, order.id() - 1L);
  }

  private long mockPayloadOffset(JsonNode payload) {
    UUID stableId = UUID.nameUUIDFromBytes(payload.toString().getBytes(StandardCharsets.UTF_8));
    return Math.floorMod(stableId.getMostSignificantBits() ^ stableId.getLeastSignificantBits(), 900_000L);
  }

  private String mockIdentifier(String prefix, long base, long offset, int minimumDigits) {
    return prefix + String.format("%0" + minimumDigits + "d", base + offset);
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

  private OrderRow findOrderByIdForUpdate(long orderId) {
    try {
      return jdbcTemplate.queryForObject(
          "SELECT * FROM orders WHERE id = ? FOR UPDATE",
          orderRowMapper,
          orderId
      );
    } catch (Exception exception) {
      throw new ApiException(404, "Order not found");
    }
  }

  private String reserveNextOrderId() {
    return isolatedTransactionTemplate.execute(status -> {
      String value = jdbcTemplate.queryForObject(
          "SELECT meta_value FROM app_meta WHERE meta_key = ? FOR UPDATE",
          String.class,
          "order_sequence"
      );
      long next = value == null ? 10001L : Long.parseLong(value) + 1L;
      int updated = jdbcTemplate.update(
          "UPDATE app_meta SET meta_value = ? WHERE meta_key = ?",
          String.valueOf(next),
          "order_sequence"
      );
      if (updated != 1) {
        throw new IllegalStateException("Order sequence is not initialized");
      }
      return "ZPY-ORD-" + next;
    });
  }

  private String normalizeIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey == null) {
      return null;
    }
    String normalized = idempotencyKey.trim();
    if (normalized.length() > 128) {
      throw new ApiException(400, "Idempotency-Key must not exceed 128 characters");
    }
    return normalized.isEmpty() ? null : normalized;
  }

  private int exactWeightGrams(BigDecimal weightGrams) {
    try {
      return weightGrams.intValueExact();
    } catch (ArithmeticException exception) {
      throw new ApiException(422, "package.weightGrams must be a whole number within the supported range");
    }
  }

  private String hashOrderRequest(OrderCreateRequest request) {
    return sha256(toJson(request));
  }

  private String hashPaymentIntentRequest(CreatePaymentIntentRequest request) {
    return sha256(
        request.getOrderId().trim()
            + "|" + request.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString()
            + "|" + request.getCurrency().trim().toUpperCase()
    );
  }

  private String hashPaymentAction(String eventType, String paymentId, ActionAudit audit) {
    return sha256(String.join(
        "|",
        eventType,
        paymentId,
        Objects.toString(audit.provider(), ""),
        Objects.toString(audit.reference(), ""),
        Objects.toString(audit.reconciliationReference(), ""),
        Objects.toString(audit.reason(), "")
    ));
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder();
      for (byte hashByte : hash) {
        builder.append(String.format("%02x", hashByte));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new ApiException(500, "Unable to hash request");
    }
  }

  private String currentActor() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken)
        && authentication.getName() != null
        && !authentication.getName().isBlank()) {
      return authentication.getName();
    }
    return "LOCAL_OPERATOR";
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
      throw new CancellationException("Carrier request interrupted");
    }
  }

  private boolean booleanValue(Object value) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    return Boolean.parseBoolean(String.valueOf(value));
  }

  private long longValue(Object value) {
    try {
      if (value instanceof Number number) {
        return number.longValue();
      }
      return Long.parseLong(String.valueOf(value));
    } catch (RuntimeException exception) {
      throw new ApiException(400, "Runtime delay must be an integer");
    }
  }

  private long runtimeDelay(Object value, String field) {
    long delay = longValue(value);
    if (delay < 0 || delay > MAX_RUNTIME_DELAY_MS) {
      throw new ApiException(
          400,
          "Invalid runtime delay",
          List.of(field + " must be between 0 and " + MAX_RUNTIME_DELAY_MS)
      );
    }
    return delay;
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

  private boolean isTerminalOrderStatus(String status) {
    return STATUS_DELIVERED.equals(status)
        || STATUS_RTO.equals(status)
        || STATUS_CANCELLED.equals(status);
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

  private record PaymentOperationContext(
      OrderRow order,
      PaymentRow payment
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
      String provider,
      String providerReference,
      String reconciliationReference,
      String refundReference,
      String refundReconciliationReference,
      String capturedAt,
      String collectedAt,
      String refundedAt,
      String createdAt,
      String updatedAt
  ) {}

  private record PaymentTransactionRow(
      long id,
      String transactionId,
      long paymentDatabaseId,
      String paymentId,
      long orderDatabaseId,
      String orderId,
      String eventType,
      String previousStatus,
      String resultingStatus,
      BigDecimal amount,
      String currency,
      String provider,
      String providerReference,
      String reconciliationReference,
      String reason,
      String actor,
      String idempotencyKey,
      String requestHash,
      String responseJson,
      String createdAt
  ) {}

  private record ActionAudit(
      String provider,
      String reference,
      String reconciliationReference,
      String reason
  ) {}

  private record ReportFilter(
      LocalDate from,
      LocalDate to,
      Instant fromInclusive,
      Instant toExclusive,
      String status,
      String method,
      String carrier,
      String search
  ) {}

  private record SqlFilter(String clause, List<Object> arguments) {}

  private record CashTotals(BigDecimal grossCollected, BigDecimal refunds) {}

  private record ShipmentTotals(BigDecimal shippingCost, long costedCount, long activeCount) {}

  private record PaymentBuckets(
      long pendingCount,
      BigDecimal pendingAmount,
      long failedCount,
      BigDecimal failedAmount,
      long voidedCount,
      BigDecimal voidedAmount,
      long codCount,
      BigDecimal codAmount
  ) {
    private static PaymentBuckets empty() {
      return new PaymentBuckets(0, ZERO, 0, ZERO, 0, ZERO, 0, ZERO);
    }
  }

  private static final class BreakdownTotals {
    private long count;
    private BigDecimal totalAmount = ZERO;
    private BigDecimal gross = ZERO;
    private BigDecimal refunds = ZERO;
    private BigDecimal outstanding = ZERO;
  }

  private static final class CarrierTotals {
    private long shipments;
    private long activeShipments;
    private BigDecimal shippingCost = ZERO;
    private BigDecimal gross = ZERO;
    private BigDecimal refunds = ZERO;
    private BigDecimal outstanding = ZERO;
  }

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
      rs.getString("provider"),
      rs.getString("provider_reference"),
      rs.getString("reconciliation_reference"),
      rs.getString("refund_reference"),
      rs.getString("refund_reconciliation_reference"),
      rs.getString("captured_at"),
      rs.getString("collected_at"),
      rs.getString("refunded_at"),
      rs.getString("created_at"),
      rs.getString("updated_at")
  );

  private final RowMapper<PaymentTransactionRow> paymentTransactionRowMapper = (rs, rowNum) ->
      new PaymentTransactionRow(
          rs.getLong("id"),
          rs.getString("transaction_id"),
          rs.getLong("payment_database_id"),
          rs.getString("payment_id"),
          rs.getLong("order_database_id"),
          rs.getString("zippy_order_id"),
          rs.getString("event_type"),
          rs.getString("previous_status"),
          rs.getString("resulting_status"),
          defaultDecimal(rs.getBigDecimal("amount")),
          rs.getString("currency"),
          rs.getString("provider"),
          rs.getString("provider_reference"),
          rs.getString("reconciliation_reference"),
          rs.getString("reason"),
          rs.getString("actor"),
          rs.getString("idempotency_key"),
          rs.getString("request_hash"),
          rs.getString("response_json"),
          rs.getString("created_at")
      );

  @Transactional
  public PaymentIntentResponse createPaymentIntent(CreatePaymentIntentRequest request, String idempotencyKey) {
    String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
    String requestHash = normalizedKey == null ? null : hashPaymentIntentRequest(request);
    lockPaymentOperationKey(normalizedKey);
    PaymentIntentResponse replay = findPaymentOperationReplay(
        null,
        normalizedKey,
        "INTENT_CREATED",
        requestHash
    );
    if (replay != null) {
      return replay;
    }

    OrderRow order = findOrderByZippyOrderIdForUpdate(request.getOrderId());
    if (!"PREPAID".equalsIgnoreCase(order.paymentType())) {
      throw new ApiException(409, "Payment intents are only available for prepaid orders");
    }
    if (isTerminalOrderStatus(order.orderStatus())) {
      throw new ApiException(409, "Payment cannot be created for an order in status " + order.orderStatus());
    }

    ShipmentRow shipment = findShipment(order.id());
    if (shipment == null) {
      throw new ApiException(409, "Select a carrier before creating a payment");
    }
    if (isTerminalOrderStatus(shipment.currentStatus())) {
      throw new ApiException(409, "Payment cannot be created for a shipment in status " + shipment.currentStatus());
    }

    String currency = request.getCurrency().trim().toUpperCase();
    if (!"INR".equals(currency)) {
      throw new ApiException(422, "Unsupported currency: " + currency);
    }

    BigDecimal amount = request.getAmount().setScale(2, RoundingMode.HALF_UP);
    if (!"INR".equals(currency) || amount.compareTo(shipment.quotedAmount()) != 0) {
      throw new ApiException(422, "Payment amount must match the selected shipment charge in INR");
    }

    PaymentRow existingPayment = findBlockingPrepaidPaymentForOrder(order.id());
    if (existingPayment != null) {
      if ("PENDING".equals(existingPayment.status())
          && existingPayment.amount().compareTo(amount) == 0
          && currency.equals(existingPayment.currency())) {
        return toPaymentIntentResponse(existingPayment);
      }
      if ("PENDING".equals(existingPayment.status())) {
        throw new ApiException(409, "A payment intent already exists for this order");
      }
      throw new ApiException(409, "This order already has a completed or active payment outcome");
    }

    String paymentId = "PAY-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    String timestamp = now();
    String provider = "ZIPPY_CHECKOUT";
    String providerReference = paymentId;
    jdbcTemplate.update("""
        INSERT INTO payments (
          payment_id, order_id, zippy_order_id, amount, currency, status,
          payment_method, collection_stage, provider, provider_reference, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        paymentId, order.id(), order.zippyOrderId(), amount, currency, "PENDING", "PREPAID", "CHECKOUT",
        provider, providerReference, timestamp, timestamp);

    PaymentRow created = requirePayment(paymentId);
    recordPaymentTransaction(
        created,
        "INTENT_CREATED",
        null,
        "PENDING",
        created.amount(),
        provider,
        providerReference,
        null,
        "Prepaid payment intent created",
        currentActor(),
        normalizedKey,
        requestHash,
        timestamp
    );
    return toPaymentIntentResponse(created);
  }

  @Transactional
  public PaymentIntentResponse confirmPayment(
      String paymentId,
      PaymentActionRequest request,
      String idempotencyKey
  ) {
    String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
    lockPaymentOperationKey(normalizedKey);
    PaymentOperationContext context = lockPaymentAndOrder(paymentId);
    PaymentRow payment = context.payment();
    ActionAudit audit = actionAudit(request, payment, "ZIPPY_OPERATIONS", "CAPTURE-", "Payment captured by operations");
    String requestHash = normalizedKey == null ? null : hashPaymentAction("CAPTURED", paymentId, audit);
    PaymentIntentResponse replay = findPaymentOperationReplay(
        paymentId, normalizedKey, "CAPTURED", requestHash);
    if (replay != null) {
      return replay;
    }
    if (!"PREPAID".equals(payment.paymentMethod())) {
      throw new ApiException(409, "Only prepaid payments can be confirmed");
    }
    if ("SUCCEEDED".equals(payment.status())) {
      return toPaymentIntentResponse(payment);
    }
    if (!"PENDING".equals(payment.status())) {
      throw new ApiException(409, "Payment cannot be confirmed from status " + payment.status());
    }
    if (isTerminalOrderStatus(context.order().orderStatus())) {
      throw new ApiException(409, "Payment cannot be confirmed for an order in status " + context.order().orderStatus());
    }
    ShipmentRow shipment = findShipmentForUpdate(context.order().id());
    if (shipment == null
        || isTerminalOrderStatus(shipment.currentStatus())
        || !"INR".equals(payment.currency())
        || payment.amount().compareTo(shipment.quotedAmount()) != 0) {
      throw new ApiException(409, "Payment amount no longer matches the selected shipment charge");
    }

    String timestamp = now();
    int updated = jdbcTemplate.update("""
        UPDATE payments
        SET status = ?, provider = ?, provider_reference = ?, reconciliation_reference = ?,
            captured_at = ?, updated_at = ?
        WHERE payment_id = ? AND status = 'PENDING'
        """, "SUCCEEDED", audit.provider(), audit.reference(), audit.reconciliationReference(),
        timestamp, timestamp, paymentId);
    requireTransition(updated, "Payment was changed by another operation");
    PaymentRow confirmed = requirePayment(paymentId);
    recordPaymentTransaction(
        confirmed, "CAPTURED", "PENDING", "SUCCEEDED", confirmed.amount(), audit.provider(),
        audit.reference(), audit.reconciliationReference(), audit.reason(), currentActor(), normalizedKey,
        requestHash, timestamp);
    return toPaymentIntentResponse(confirmed);
  }

  @Transactional
  public PaymentIntentResponse failPayment(
      String paymentId,
      PaymentFailureRequest request,
      String idempotencyKey
  ) {
    String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
    lockPaymentOperationKey(normalizedKey);
    PaymentOperationContext context = lockPaymentAndOrder(paymentId);
    PaymentRow payment = context.payment();
    String code = request == null || request.code() == null || request.code().isBlank()
        ? "PAYMENT_DECLINED" : request.code().trim();
    String reason = request == null || request.reason() == null || request.reason().isBlank()
        ? "Payment was declined" : request.reason().trim();
    String reference = request == null || request.reference() == null || request.reference().isBlank()
        ? "FAIL-" + payment.paymentId() : request.reference().trim();
    ActionAudit audit = new ActionAudit(
        defaultString(payment.provider(), "ZIPPY_OPERATIONS"),
        reference,
        null,
        code + ": " + reason
    );
    String requestHash = normalizedKey == null ? null : hashPaymentAction("FAILED", paymentId, audit);
    PaymentIntentResponse replay = findPaymentOperationReplay(
        paymentId, normalizedKey, "FAILED", requestHash);
    if (replay != null) {
      return replay;
    }
    if ("FAILED".equals(payment.status())
        && Objects.equals(payment.failureCode(), code)
        && Objects.equals(payment.failureReason(), reason)) {
      return toPaymentIntentResponse(payment);
    }
    if (!"PREPAID".equals(payment.paymentMethod()) || !"PENDING".equals(payment.status())) {
      throw new ApiException(409, "Only pending prepaid payments can be failed");
    }
    if (isTerminalOrderStatus(context.order().orderStatus())) {
      throw new ApiException(409, "Payment cannot be failed for an order in status " + context.order().orderStatus());
    }
    String timestamp = now();
    int updated = jdbcTemplate.update("""
        UPDATE payments
        SET status = ?, failure_code = ?, failure_reason = ?, updated_at = ?
        WHERE payment_id = ? AND status = 'PENDING'
        """, "FAILED", code, reason, timestamp, paymentId);
    requireTransition(updated, "Payment was changed by another operation");
    PaymentRow failed = requirePayment(paymentId);
    recordPaymentTransaction(
        failed, "FAILED", "PENDING", "FAILED", failed.amount(), audit.provider(), audit.reference(), null,
        audit.reason(), currentActor(), normalizedKey, requestHash, timestamp);
    return toPaymentIntentResponse(failed);
  }

  @Transactional
  public PaymentIntentResponse cancelPayment(
      String paymentId,
      PaymentActionRequest request,
      String idempotencyKey
  ) {
    String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
    lockPaymentOperationKey(normalizedKey);
    PaymentOperationContext context = lockPaymentAndOrder(paymentId);
    PaymentRow payment = context.payment();
    ActionAudit audit = actionAudit(request, payment, "ZIPPY_OPERATIONS", "CANCEL-", "Payment cancelled by operations");
    String requestHash = normalizedKey == null ? null : hashPaymentAction("CANCELLED", paymentId, audit);
    PaymentIntentResponse replay = findPaymentOperationReplay(
        paymentId, normalizedKey, "CANCELLED", requestHash);
    if (replay != null) {
      return replay;
    }
    if ("CANCELLED".equals(payment.status())) {
      return toPaymentIntentResponse(payment);
    }
    if (!"PENDING".equals(payment.status())) {
      throw new ApiException(409, "Only pending payments can be cancelled");
    }
    if (isTerminalOrderStatus(context.order().orderStatus())) {
      throw new ApiException(409, "Payment cannot be cancelled for an order in status " + context.order().orderStatus());
    }
    String timestamp = now();
    int updated = jdbcTemplate.update(
        "UPDATE payments SET status = ?, updated_at = ? WHERE payment_id = ? AND status = 'PENDING'",
        "CANCELLED", timestamp, paymentId
    );
    requireTransition(updated, "Payment was changed by another operation");
    PaymentRow cancelled = requirePayment(paymentId);
    recordPaymentTransaction(
        cancelled, "CANCELLED", "PENDING", "CANCELLED", cancelled.amount(), audit.provider(),
        audit.reference(), audit.reconciliationReference(), audit.reason(), currentActor(), normalizedKey,
        requestHash, timestamp);
    return toPaymentIntentResponse(cancelled);
  }

  @Transactional
  public PaymentIntentResponse refundPayment(
      String paymentId,
      PaymentActionRequest request,
      String idempotencyKey
  ) {
    String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
    lockPaymentOperationKey(normalizedKey);
    PaymentOperationContext context = lockPaymentAndOrder(paymentId);
    PaymentRow payment = context.payment();
    ActionAudit audit = actionAudit(request, payment, "ZIPPY_OPERATIONS", "REFUND-", "Full refund completed by operations");
    String requestHash = normalizedKey == null ? null : hashPaymentAction("REFUNDED", paymentId, audit);
    PaymentIntentResponse replay = findPaymentOperationReplay(
        paymentId, normalizedKey, "REFUNDED", requestHash);
    if (replay != null) {
      return replay;
    }
    if ("REFUNDED".equals(payment.status())) {
      return toPaymentIntentResponse(payment);
    }
    if (!"PREPAID".equals(payment.paymentMethod())
        || !("SUCCEEDED".equals(payment.status()) || "REFUND_PENDING".equals(payment.status()))) {
      throw new ApiException(409, "Only succeeded or refund-pending prepaid payments can be refunded");
    }
    ShipmentRow shipment = findShipmentForUpdate(context.order().id());
    if (shipment == null || !List.of(STATUS_CANCELLED, STATUS_RTO, STATUS_DELIVERED).contains(shipment.currentStatus())) {
      throw new ApiException(409, "Cancel or complete the shipment before refunding its prepaid payment");
    }
    String timestamp = now();
    String previousStatus = payment.status();
    int updated = jdbcTemplate.update("""
        UPDATE payments
        SET status = ?, refunded_amount = amount, refund_reference = ?,
            refund_reconciliation_reference = ?, refunded_at = ?, updated_at = ?
        WHERE payment_id = ? AND status IN ('SUCCEEDED', 'REFUND_PENDING')
        """, "REFUNDED", audit.reference(), audit.reconciliationReference(), timestamp, timestamp, paymentId);
    requireTransition(updated, "Payment was changed by another operation");
    PaymentRow refunded = requirePayment(paymentId);
    recordPaymentTransaction(
        refunded, "REFUNDED", previousStatus, "REFUNDED", refunded.amount(), audit.provider(),
        audit.reference(), audit.reconciliationReference(), audit.reason(), currentActor(), normalizedKey,
        requestHash, timestamp);
    return toPaymentIntentResponse(refunded);
  }

  @Transactional
  public PaymentIntentResponse collectPayment(
      String paymentId,
      PaymentActionRequest request,
      String idempotencyKey
  ) {
    String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
    lockPaymentOperationKey(normalizedKey);
    PaymentOperationContext context = lockPaymentAndOrder(paymentId);
    PaymentRow payment = context.payment();
    ActionAudit audit = actionAudit(request, payment, "COD_OPERATIONS", "COLLECT-", "COD collected at delivery");
    String requestHash = normalizedKey == null ? null : hashPaymentAction("COD_COLLECTED", paymentId, audit);
    PaymentIntentResponse replay = findPaymentOperationReplay(
        paymentId, normalizedKey, "COD_COLLECTED", requestHash);
    if (replay != null) {
      return replay;
    }
    if (!"COD".equals(payment.paymentMethod())) {
      throw new ApiException(409, "Only COD payments can be collected at delivery");
    }
    if ("SUCCEEDED".equals(payment.status())) {
      return toPaymentIntentResponse(payment);
    }
    if (!"AWAITING_COLLECTION".equals(payment.status())) {
      throw new ApiException(409, "COD payment cannot be collected from status " + payment.status());
    }

    ShipmentRow shipment = findShipmentForUpdate(context.order().id());
    if (shipment == null || !STATUS_DELIVERED.equals(shipment.currentStatus())) {
      throw new ApiException(409, "COD can only be collected after delivery is confirmed");
    }

    String timestamp = now();
    int updated = jdbcTemplate.update("""
        UPDATE payments
        SET status = ?, collection_stage = ?, provider = ?, provider_reference = ?,
            reconciliation_reference = ?, collected_at = ?, updated_at = ?
        WHERE payment_id = ? AND status = 'AWAITING_COLLECTION'
        """, "SUCCEEDED", "DELIVERY", audit.provider(), audit.reference(), audit.reconciliationReference(),
        timestamp, timestamp, paymentId);
    requireTransition(updated, "Payment was changed by another operation");
    PaymentRow collected = requirePayment(paymentId);
    recordPaymentTransaction(
        collected, "COD_COLLECTED", "AWAITING_COLLECTION", "SUCCEEDED", collected.amount(), audit.provider(),
        audit.reference(), audit.reconciliationReference(), audit.reason(), currentActor(), normalizedKey,
        requestHash, timestamp);
    return toPaymentIntentResponse(collected);
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

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PaymentTransactionHistoryResponse getPaymentTransactions(String paymentId, int limit, int offset) {
    PaymentRow payment = requirePayment(paymentId);
    return paymentTransactionHistory(payment.id(), payment.paymentId(), payment.zippyOrderId(), limit, offset, false);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PaymentTransactionHistoryResponse getOrderPaymentTransactions(String orderId, int limit, int offset) {
    OrderRow order = findOrderByZippyId(orderId);
    return paymentTransactionHistory(order.id(), null, order.zippyOrderId(), limit, offset, true);
  }

  private PaymentTransactionHistoryResponse paymentTransactionHistory(
      long databaseId,
      String paymentId,
      String orderId,
      int limit,
      int offset,
      boolean byOrder
  ) {
    int safeLimit = Math.max(1, Math.min(limit, 100));
    int safeOffset = Math.max(0, offset);
    String column = byOrder ? "t.order_id" : "t.payment_id";
    Long total = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payment_transactions t WHERE " + column + " = ?",
        Long.class,
        databaseId
    );
    List<PaymentTransactionResponse> transactions = jdbcTemplate.query("""
            SELECT t.id, t.transaction_id, t.payment_id AS payment_database_id,
                   p.payment_id AS payment_id, t.order_id AS order_database_id,
                   t.zippy_order_id, t.event_type, t.previous_status, t.resulting_status,
                   t.amount, t.currency, t.provider, t.provider_reference,
                   t.reconciliation_reference, t.reason, t.actor, t.idempotency_key,
                   t.request_hash, t.response_json, t.created_at
            FROM payment_transactions t
            JOIN payments p ON p.id = t.payment_id
            """ + " WHERE " + column + " = ? ORDER BY t.created_at ASC, t.id ASC LIMIT ? OFFSET ?",
        paymentTransactionRowMapper,
        databaseId,
        safeLimit,
        safeOffset
    ).stream().map(this::toPaymentTransactionResponse).toList();
    return new PaymentTransactionHistoryResponse(
        paymentId,
        orderId,
        safeLimit,
        safeOffset,
        total == null ? 0 : total,
        transactions
    );
  }

  private PaymentRow findBlockingPrepaidPaymentForOrder(long orderId) {
    List<PaymentRow> payments = jdbcTemplate.query(
        """
        SELECT * FROM payments
        WHERE order_id = ?
          AND payment_method = 'PREPAID'
          AND status IN ('PENDING', 'SUCCEEDED', 'REFUND_PENDING', 'REFUNDED')
        ORDER BY id DESC
        LIMIT 1
        """,
        paymentRowMapper,
        orderId
    );
    return payments.isEmpty() ? null : payments.getFirst();
  }

  private boolean hasAnyPrepaidPayment(long orderId) {
    Long count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payments WHERE order_id = ? AND payment_method = 'PREPAID'",
        Long.class,
        orderId
    );
    return count != null && count > 0;
  }

  private boolean hasMatchingSucceededPrepaidPayment(long orderId, BigDecimal quotedAmount) {
    Long count = jdbcTemplate.queryForObject("""
        SELECT COUNT(*) FROM payments
        WHERE order_id = ? AND payment_method = 'PREPAID' AND status = 'SUCCEEDED'
          AND currency = 'INR' AND amount = ?
        """, Long.class, orderId, quotedAmount.setScale(2, RoundingMode.HALF_UP));
    return count != null && count > 0;
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

    String paymentId = "COD-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    jdbcTemplate.update("""
        INSERT INTO payments (
          payment_id, order_id, zippy_order_id, amount, currency, status,
          payment_method, collection_stage, provider, provider_reference, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        paymentId, order.id(), order.zippyOrderId(), defaultDecimal(order.codAmount()), "INR",
        "AWAITING_COLLECTION", "COD", "DELIVERY", "COD", paymentId, timestamp, timestamp);
    PaymentRow payment = requirePayment(paymentId);
    recordPaymentTransaction(
        payment, "COD_AWAITING_COLLECTION", null, "AWAITING_COLLECTION", payment.amount(), "COD",
        paymentId, null, "COD payment registered for collection at delivery", "ZIPPY_SYSTEM",
        null, null, timestamp);
  }

  private PaymentRow requirePayment(String paymentId) {
    try {
      return jdbcTemplate.queryForObject(
          "SELECT * FROM payments WHERE payment_id = ?",
          paymentRowMapper,
          paymentId
      );
    } catch (EmptyResultDataAccessException exception) {
      throw new ApiException(404, "Payment not found");
    }
  }

  private PaymentRow requirePaymentForUpdate(String paymentId) {
    try {
      return jdbcTemplate.queryForObject(
          "SELECT * FROM payments WHERE payment_id = ? FOR UPDATE",
          paymentRowMapper,
          paymentId
      );
    } catch (EmptyResultDataAccessException exception) {
      throw new ApiException(404, "Payment not found");
    }
  }

  private PaymentOperationContext lockPaymentAndOrder(String paymentId) {
    PaymentRow snapshot = requirePayment(paymentId);
    OrderRow order = findOrderByIdForUpdate(snapshot.orderId());
    PaymentRow payment = requirePaymentForUpdate(paymentId);
    if (payment.orderId() != order.id()) {
      throw new ApiException(409, "Payment no longer belongs to this order");
    }
    return new PaymentOperationContext(order, payment);
  }

  private void requireTransition(int updatedRows, String message) {
    if (updatedRows != 1) {
      throw new ApiException(409, message);
    }
  }

  private ActionAudit actionAudit(
      PaymentActionRequest request,
      PaymentRow payment,
      String defaultProvider,
      String referencePrefix,
      String defaultReason
  ) {
    String provider = request == null ? null : request.provider();
    if (provider == null || provider.isBlank()) {
      provider = defaultString(payment.provider(), defaultProvider);
    } else {
      provider = provider.trim().toUpperCase();
    }
    String reference = request == null ? null : request.reference();
    if (reference == null || reference.isBlank()) {
      reference = referencePrefix + payment.paymentId();
    } else {
      reference = reference.trim();
    }
    String reconciliation = request == null ? null : request.reconciliationReference();
    if (reconciliation != null) {
      reconciliation = reconciliation.trim();
      if (reconciliation.isEmpty()) {
        reconciliation = null;
      }
    }
    String reason = request == null ? null : request.reason();
    if (reason == null || reason.isBlank()) {
      reason = defaultReason;
    } else {
      reason = reason.trim();
    }
    return new ActionAudit(provider, reference, reconciliation, reason);
  }

  private String defaultString(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private PaymentIntentResponse findPaymentOperationReplay(
      String paymentId,
      String idempotencyKey,
      String eventType,
      String requestHash
  ) {
    if (idempotencyKey == null) {
      return null;
    }
    PaymentTransactionRow existing = findPaymentTransactionByIdempotency(idempotencyKey);
    if (existing == null) {
      return null;
    }
    if (!eventType.equals(existing.eventType())
        || (paymentId != null && !paymentId.equals(existing.paymentId()))
        || !Objects.equals(requestHash, existing.requestHash())) {
      throw new ApiException(409, "Idempotency key reused with a different payment operation");
    }
    if (existing.responseJson() == null || existing.responseJson().isBlank()) {
      return toPaymentIntentResponse(requirePayment(existing.paymentId()));
    }
    try {
      return objectMapper.readValue(existing.responseJson(), PaymentIntentResponse.class);
    } catch (Exception exception) {
      throw new ApiException(500, "Stored payment idempotency response is invalid");
    }
  }

  private PaymentTransactionRow findPaymentTransactionByIdempotency(String key) {
    List<PaymentTransactionRow> transactions = jdbcTemplate.query("""
            SELECT t.id, t.transaction_id, t.payment_id AS payment_database_id,
                   p.payment_id AS payment_id, t.order_id AS order_database_id,
                   t.zippy_order_id, t.event_type, t.previous_status, t.resulting_status,
                   t.amount, t.currency, t.provider, t.provider_reference,
                   t.reconciliation_reference, t.reason, t.actor, t.idempotency_key,
                   t.request_hash, t.response_json, t.created_at
            FROM payment_transactions t
            JOIN payments p ON p.id = t.payment_id
            WHERE t.idempotency_key = ?
            """,
        paymentTransactionRowMapper,
        key
    );
    return transactions.isEmpty() ? null : transactions.getFirst();
  }

  private void recordPaymentTransaction(
      PaymentRow payment,
      String eventType,
      String previousStatus,
      String resultingStatus,
      BigDecimal amount,
      String provider,
      String providerReference,
      String reconciliationReference,
      String reason,
      String actor,
      String idempotencyKey,
      String requestHash,
      String timestamp
  ) {
    String responseJson = idempotencyKey == null ? null : toJson(toPaymentIntentResponse(payment));
    jdbcTemplate.update("""
        INSERT INTO payment_transactions (
          transaction_id, payment_id, order_id, zippy_order_id, event_type,
          previous_status, resulting_status, amount, currency, provider,
          provider_reference, reconciliation_reference, reason, actor,
          idempotency_key, request_hash, response_json, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        "PTX-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
        payment.id(), payment.orderId(), payment.zippyOrderId(), eventType,
        previousStatus, resultingStatus, defaultDecimal(amount), payment.currency(),
        defaultString(provider, "ZIPPY_INTERNAL"), providerReference, reconciliationReference,
        defaultString(reason, "Payment state changed"), defaultString(actor, "ZIPPY_SYSTEM"),
        idempotencyKey, requestHash, responseJson, timestamp);
  }

  private PaymentTransactionResponse toPaymentTransactionResponse(PaymentTransactionRow transaction) {
    return new PaymentTransactionResponse(
        transaction.transactionId(),
        transaction.paymentId(),
        transaction.orderId(),
        transaction.eventType(),
        transaction.previousStatus(),
        transaction.resultingStatus(),
        transaction.amount(),
        transaction.currency(),
        transaction.provider(),
        transaction.providerReference(),
        transaction.reconciliationReference(),
        transaction.reason(),
        transaction.actor(),
        Instant.parse(transaction.createdAt())
    );
  }

  private PaymentIntentResponse toPaymentIntentResponse(PaymentRow payment) {
    return new PaymentIntentResponse(
        payment.paymentId(),
        payment.zippyOrderId(),
        payment.amount(),
        payment.currency(),
        payment.status(),
        payment.paymentMethod(),
        payment.collectionStage(),
        payment.failureCode(),
        payment.failureReason(),
        payment.refundedAmount(),
        payment.provider(),
        payment.providerReference(),
        payment.reconciliationReference(),
        payment.refundReference(),
        payment.refundReconciliationReference(),
        parseOptionalInstant(payment.capturedAt()),
        parseOptionalInstant(payment.collectedAt()),
        parseOptionalInstant(payment.refundedAt()),
        Instant.parse(payment.createdAt()),
        Instant.parse(payment.updatedAt())
    );
  }

  private Instant parseOptionalInstant(String value) {
    return value == null || value.isBlank() ? null : Instant.parse(value);
  }
}
