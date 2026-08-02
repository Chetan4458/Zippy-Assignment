package com.zippy.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.zippy.backend.dto.CarrierSelectionRequest;
import com.zippy.backend.dto.OrderCreateRequest;
import com.zippy.backend.dto.CreatePaymentIntentRequest;
import com.zippy.backend.dto.PaymentIntentResponse;
import com.zippy.backend.dto.PaymentFailureRequest;
import com.zippy.backend.service.ZippyService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class ZippyController {
  private final ZippyService zippyService;

  public ZippyController(ZippyService zippyService) {
    this.zippyService = zippyService;
  }

  @PostMapping("/api/orders")
  public ResponseEntity<Map<String, Object>> createOrder(
      @Valid @RequestBody OrderCreateRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
  ) {
    return ResponseEntity.status(HttpStatus.CREATED).body(zippyService.createOrder(request, idempotencyKey));
  }

  @GetMapping("/api/orders/{orderId}")
  public Map<String, Object> getOrder(@PathVariable String orderId) {
    return zippyService.getOrder(orderId);
  }

  @GetMapping("/api/orders/history")
  public Map<String, Object> getOrderHistory(
      @RequestParam(defaultValue = "5") int limit,
      @RequestParam(defaultValue = "0") int offset
  ) {
    return zippyService.getOrderHistory(limit, offset);
  }

  @GetMapping("/api/orders/{orderId}/rates")
  public Map<String, Object> getRates(@PathVariable String orderId, @RequestParam(defaultValue = "lowest") String sortBy) {
    return zippyService.getRates(orderId, sortBy);
  }

  @PostMapping("/api/orders/{orderId}/select-carrier")
  public Map<String, Object> selectCarrier(@PathVariable String orderId, @Valid @RequestBody CarrierSelectionRequest request) {
    return zippyService.selectCarrier(orderId, request);
  }

  @PostMapping("/api/orders/{orderId}/create-shipment")
  public Map<String, Object> createShipment(@PathVariable String orderId) {
    return zippyService.createShipment(orderId);
  }

  @PostMapping("/api/orders/{orderId}/cancel")
  public Map<String, Object> cancelOrder(@PathVariable String orderId) {
    return zippyService.cancelOrder(orderId);
  }

  @GetMapping("/api/orders/{orderId}/tracking")
  public Map<String, Object> getTracking(@PathVariable String orderId) {
    return zippyService.getTracking(orderId);
  }

  @GetMapping("/api/orders/{orderId}/events")
  public Map<String, Object> getEvents(
      @PathVariable String orderId,
      @RequestParam(defaultValue = "10") int limit,
      @RequestParam(defaultValue = "0") int offset
  ) {
    return zippyService.getShipmentEvents(orderId, limit, offset);
  }

  @GetMapping("/api/system/overview")
  public Map<String, Object> getSystemOverview() {
    return zippyService.getSystemOverview();
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
      @PathVariable String carrier,
      @RequestBody JsonNode payload
  ) {
    return zippyService.handleWebhook(carrier, payload);
  }

  @PostMapping("/api/mock-carriers/{orderId}/advance")
  public Map<String, Object> advanceCarrier(@PathVariable String orderId) {
    return zippyService.advanceMockCarrier(orderId);
  }

  @PostMapping("/api/mock-carriers/{orderId}/delivery-failed")
  public Map<String, Object> mockDeliveryFailure(@PathVariable String orderId) {
    return zippyService.mockDeliveryFailure(orderId);
  }

  @PostMapping("/api/mock-carriers/{orderId}/rto")
  public Map<String, Object> mockRto(@PathVariable String orderId) {
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
  public Map<String, Object> getReportsSummary() {
    return zippyService.getReportsSummary();
  }

  @GetMapping("/api/reports/payments")
  public Map<String, Object> getPaymentHistory(
      @RequestParam(defaultValue = "10") int limit,
      @RequestParam(defaultValue = "0") int offset
  ) {
    return zippyService.getPaymentHistory(limit, offset);
  }

  @PostMapping("/api/payments")
  public ResponseEntity<PaymentIntentResponse> createPaymentIntent(
      @Valid @RequestBody CreatePaymentIntentRequest request
  ) {
    PaymentIntentResponse response = zippyService.createPaymentIntent(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping("/api/payments/{paymentId}")
  public PaymentIntentResponse getPayment(@PathVariable String paymentId) {
    return zippyService.getPayment(paymentId);
  }

  @PostMapping("/api/payments/{paymentId}/confirm")
  public PaymentIntentResponse confirmPayment(@PathVariable String paymentId) {
    return zippyService.confirmPayment(paymentId);
  }

  @PostMapping("/api/payments/{paymentId}/fail")
  public PaymentIntentResponse failPayment(
      @PathVariable String paymentId,
      @RequestBody(required = false) PaymentFailureRequest request
  ) {
    return zippyService.failPayment(paymentId, request);
  }

  @PostMapping("/api/payments/{paymentId}/cancel")
  public PaymentIntentResponse cancelPayment(@PathVariable String paymentId) {
    return zippyService.cancelPayment(paymentId);
  }

  @PostMapping("/api/payments/{paymentId}/refund")
  public PaymentIntentResponse refundPayment(@PathVariable String paymentId) {
    return zippyService.refundPayment(paymentId);
  }

  @PostMapping("/api/payments/{paymentId}/collect")
  public PaymentIntentResponse collectPayment(@PathVariable String paymentId) {
    return zippyService.collectPayment(paymentId);
  }

  @GetMapping("/api/orders/{orderId}/payments")
  public List<PaymentIntentResponse> getOrderPayments(@PathVariable String orderId) {
    return zippyService.getPaymentsForOrder(orderId);
  }
}
