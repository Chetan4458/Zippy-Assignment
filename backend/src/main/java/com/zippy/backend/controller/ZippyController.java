package com.zippy.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.zippy.backend.dto.CarrierSelectionRequest;
import com.zippy.backend.dto.OrderCreateRequest;
import com.zippy.backend.dto.CreatePaymentIntentRequest;
import com.zippy.backend.dto.DailyTrendReportResponse;
import com.zippy.backend.dto.PaymentActionRequest;
import com.zippy.backend.dto.PaymentHistoryResponse;
import com.zippy.backend.dto.PaymentIntentResponse;
import com.zippy.backend.dto.PaymentFailureRequest;
import com.zippy.backend.dto.PaymentTransactionHistoryResponse;
import com.zippy.backend.service.ZippyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping
@Validated
public class ZippyController {
  private final ZippyService zippyService;

  public ZippyController(ZippyService zippyService) {
    this.zippyService = zippyService;
  }

  @PostMapping("/api/orders")
  public ResponseEntity<Map<String, Object>> createOrder(
      @Valid @RequestBody OrderCreateRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) @Size(max = 128) String idempotencyKey
  ) {
    return ResponseEntity.status(HttpStatus.CREATED).body(zippyService.createOrder(request, idempotencyKey));
  }

  @GetMapping("/api/orders/{orderId}")
  public Map<String, Object> getOrder(@PathVariable @NotBlank @Size(max = 64) String orderId) {
    return zippyService.getOrder(orderId);
  }

  @GetMapping("/api/orders/history")
  public Map<String, Object> getOrderHistory(
      @RequestParam(defaultValue = "5") @Min(1) @Max(50) int limit,
      @RequestParam(defaultValue = "0") @PositiveOrZero int offset
  ) {
    return zippyService.getOrderHistory(limit, offset);
  }

  @GetMapping("/api/orders/{orderId}/rates")
  public Map<String, Object> getRates(
      @PathVariable @NotBlank @Size(max = 64) String orderId,
      @RequestParam(defaultValue = "lowest")
      @Pattern(regexp = "(?i)lowest|fastest|carrier", message = "must be lowest, fastest, or carrier") String sortBy
  ) {
    return zippyService.getRates(orderId, sortBy);
  }

  @PostMapping("/api/orders/{orderId}/select-carrier")
  public Map<String, Object> selectCarrier(
      @PathVariable @NotBlank @Size(max = 64) String orderId,
      @Valid @RequestBody CarrierSelectionRequest request
  ) {
    return zippyService.selectCarrier(orderId, request);
  }

  @PostMapping("/api/orders/{orderId}/create-shipment")
  public Map<String, Object> createShipment(@PathVariable @NotBlank @Size(max = 64) String orderId) {
    return zippyService.createShipment(orderId);
  }

  @PostMapping("/api/orders/{orderId}/cancel")
  public Map<String, Object> cancelOrder(@PathVariable @NotBlank @Size(max = 64) String orderId) {
    return zippyService.cancelOrder(orderId);
  }

  @GetMapping("/api/orders/{orderId}/tracking")
  public Map<String, Object> getTracking(@PathVariable @NotBlank @Size(max = 64) String orderId) {
    return zippyService.getTracking(orderId);
  }

  @GetMapping("/api/orders/{orderId}/events")
  public Map<String, Object> getEvents(
      @PathVariable @NotBlank @Size(max = 64) String orderId,
      @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit,
      @RequestParam(defaultValue = "0") @PositiveOrZero int offset
  ) {
    return zippyService.getShipmentEvents(orderId, limit, offset);
  }

  @GetMapping("/api/system/overview")
  public Map<String, Object> getSystemOverview() {
    return zippyService.getSystemOverview();
  }

  @GetMapping("/api/health")
  public Map<String, Object> getHealth() {
    return zippyService.getHealth();
  }

  @PostMapping("/api/webhooks/fastship")
  public Map<String, Object> fastshipWebhook(@RequestBody JsonNode payload) {
    return zippyService.handleWebhook("FASTSHIP", payload);
  }

  @PostMapping("/api/webhooks/quickexpress")
  public Map<String, Object> quickexpressWebhook(@RequestBody JsonNode payload) {
    return zippyService.handleWebhook("QUICKEXPRESS", payload);
  }

  @PostMapping("/api/webhooks/reliable")
  public Map<String, Object> reliableWebhook(@RequestBody JsonNode payload) {
    return zippyService.handleWebhook("RELIABLE", payload);
  }

  @PostMapping("/api/webhooks/{carrier}")
  public Map<String, Object> carrierWebhook(
      @PathVariable @Pattern(regexp = "(?i)fastship|quickexpress|reliable") String carrier,
      @RequestBody JsonNode payload
  ) {
    return zippyService.handleWebhook(carrier, payload);
  }

  @PostMapping("/api/mock-carriers/{orderId}/advance")
  public Map<String, Object> advanceCarrier(@PathVariable @NotBlank @Size(max = 64) String orderId) {
    return zippyService.advanceMockCarrier(orderId);
  }

  @PostMapping("/api/mock-carriers/{orderId}/delivery-failed")
  public Map<String, Object> mockDeliveryFailure(@PathVariable @NotBlank @Size(max = 64) String orderId) {
    return zippyService.mockDeliveryFailure(orderId);
  }

  @PostMapping("/api/mock-carriers/{orderId}/rto")
  public Map<String, Object> mockRto(@PathVariable @NotBlank @Size(max = 64) String orderId) {
    return zippyService.mockRto(orderId);
  }

  @PostMapping("/api/dev/runtime-flags")
  public Map<String, Object> setRuntimeFlags(@RequestBody Map<String, Object> payload) {
    zippyService.setRuntimeFlags(payload);
    return Map.of("ok", true, "runtimeFlags", zippyService.getRuntimeFlags());
  }

  @PostMapping("/api/dev/runtime-flags/reset")
  public Map<String, Object> resetRuntimeFlags() {
    zippyService.resetRuntimeFlags();
    return Map.of("ok", true, "runtimeFlags", zippyService.getRuntimeFlags());
  }

  @PostMapping("/fastship/api/v1/rate")
  public Map<String, Object> fastshipRate(@RequestBody JsonNode payload) {
    return zippyService.mockFastshipRate(payload);
  }

  @PostMapping("/quickexpress/rates/check")
  public Map<String, Object> quickexpressRate(@RequestBody JsonNode payload) {
    return zippyService.mockQuickexpressRate(payload);
  }

  @GetMapping("/reliablecourier/shipping-options")
  public Map<String, Object> reliableOptions(@RequestParam Map<String, String> params) {
    return zippyService.mockReliableOptions(params);
  }

  @PostMapping("/fastship/api/v1/shipments")
  public Map<String, Object> fastshipShipment(@RequestBody JsonNode payload) {
    return zippyService.mockFastshipShipment(payload);
  }

  @PostMapping("/quickexpress/booking/create")
  public Map<String, Object> quickexpressShipment(@RequestBody JsonNode payload) {
    return zippyService.mockQuickexpressShipment(payload);
  }

  @PutMapping("/reliablecourier/orders")
  public Map<String, Object> reliableShipment(@RequestBody JsonNode payload) {
    return zippyService.mockReliableShipment(payload);
  }

  @GetMapping("/api/reports/summary")
  public Map<String, Object> getReportsSummary(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false)
      @Pattern(regexp = "(?i)PENDING|SUCCEEDED|FAILED|CANCELLED|AWAITING_COLLECTION|VOIDED|REFUND_PENDING|REFUNDED") String status,
      @RequestParam(required = false)
      @Pattern(regexp = "(?i)PREPAID|COD") String method,
      @RequestParam(required = false)
      @Pattern(regexp = "(?i)[A-Z0-9_-]{2,32}") String carrier
  ) {
    return zippyService.getReportsSummary(from, to, status, method, carrier);
  }

  @GetMapping("/api/reports/payments")
  public PaymentHistoryResponse getPaymentHistory(
      @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit,
      @RequestParam(defaultValue = "0") @PositiveOrZero int offset,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false)
      @Pattern(regexp = "(?i)PENDING|SUCCEEDED|FAILED|CANCELLED|AWAITING_COLLECTION|VOIDED|REFUND_PENDING|REFUNDED") String status,
      @RequestParam(required = false)
      @Pattern(regexp = "(?i)PREPAID|COD") String method,
      @RequestParam(required = false)
      @Pattern(regexp = "(?i)[A-Z0-9_-]{2,32}") String carrier,
      @RequestParam(required = false) @Size(max = 64) String search
  ) {
    return zippyService.getPaymentHistory(limit, offset, from, to, status, method, carrier, search);
  }

  @GetMapping("/api/reports/daily-trend")
  public DailyTrendReportResponse getDailyTrend(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false)
      @Pattern(regexp = "(?i)PENDING|SUCCEEDED|FAILED|CANCELLED|AWAITING_COLLECTION|VOIDED|REFUND_PENDING|REFUNDED") String status,
      @RequestParam(required = false)
      @Pattern(regexp = "(?i)PREPAID|COD") String method,
      @RequestParam(required = false)
      @Pattern(regexp = "(?i)[A-Z0-9_-]{2,32}") String carrier
  ) {
    return zippyService.getDailyTrend(from, to, status, method, carrier);
  }

  @PostMapping("/api/payments")
  public ResponseEntity<PaymentIntentResponse> createPaymentIntent(
      @Valid @RequestBody CreatePaymentIntentRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) @Size(max = 128) String idempotencyKey
  ) {
    PaymentIntentResponse response = zippyService.createPaymentIntent(request, idempotencyKey);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping("/api/payments/{paymentId}")
  public PaymentIntentResponse getPayment(@PathVariable @NotBlank @Size(max = 64) String paymentId) {
    return zippyService.getPayment(paymentId);
  }

  @PostMapping("/api/payments/{paymentId}/confirm")
  public PaymentIntentResponse confirmPayment(
      @PathVariable @NotBlank @Size(max = 64) String paymentId,
      @Valid @RequestBody(required = false) PaymentActionRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) @Size(max = 128) String idempotencyKey
  ) {
    return zippyService.confirmPayment(paymentId, request, idempotencyKey);
  }

  @PostMapping("/api/payments/{paymentId}/fail")
  public PaymentIntentResponse failPayment(
      @PathVariable @NotBlank @Size(max = 64) String paymentId,
      @Valid @RequestBody(required = false) PaymentFailureRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) @Size(max = 128) String idempotencyKey
  ) {
    return zippyService.failPayment(paymentId, request, idempotencyKey);
  }

  @PostMapping("/api/payments/{paymentId}/cancel")
  public PaymentIntentResponse cancelPayment(
      @PathVariable @NotBlank @Size(max = 64) String paymentId,
      @Valid @RequestBody(required = false) PaymentActionRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) @Size(max = 128) String idempotencyKey
  ) {
    return zippyService.cancelPayment(paymentId, request, idempotencyKey);
  }

  @PostMapping("/api/payments/{paymentId}/refund")
  public PaymentIntentResponse refundPayment(
      @PathVariable @NotBlank @Size(max = 64) String paymentId,
      @Valid @RequestBody(required = false) PaymentActionRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) @Size(max = 128) String idempotencyKey
  ) {
    return zippyService.refundPayment(paymentId, request, idempotencyKey);
  }

  @PostMapping("/api/payments/{paymentId}/collect")
  public PaymentIntentResponse collectPayment(
      @PathVariable @NotBlank @Size(max = 64) String paymentId,
      @Valid @RequestBody(required = false) PaymentActionRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) @Size(max = 128) String idempotencyKey
  ) {
    return zippyService.collectPayment(paymentId, request, idempotencyKey);
  }

  @GetMapping("/api/orders/{orderId}/payments")
  public List<PaymentIntentResponse> getOrderPayments(@PathVariable @NotBlank @Size(max = 64) String orderId) {
    return zippyService.getPaymentsForOrder(orderId);
  }

  @GetMapping("/api/payments/{paymentId}/transactions")
  public PaymentTransactionHistoryResponse getPaymentTransactions(
      @PathVariable @NotBlank @Size(max = 64) String paymentId,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @PositiveOrZero int offset
  ) {
    return zippyService.getPaymentTransactions(paymentId, limit, offset);
  }

  @GetMapping("/api/orders/{orderId}/payment-transactions")
  public PaymentTransactionHistoryResponse getOrderPaymentTransactions(
      @PathVariable @NotBlank @Size(max = 64) String orderId,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @PositiveOrZero int offset
  ) {
    return zippyService.getOrderPaymentTransactions(orderId, limit, offset);
  }
}
