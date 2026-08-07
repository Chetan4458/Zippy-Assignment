package com.zippy.backend.security;

public final class SecurityHeaders {
  public static final String API_KEY = "X-API-Key";
  public static final String WEBHOOK_TIMESTAMP = "X-Zippy-Webhook-Timestamp";
  public static final String WEBHOOK_EVENT_ID = "X-Zippy-Webhook-Event-Id";
  public static final String WEBHOOK_SIGNATURE = "X-Zippy-Webhook-Signature";

  private SecurityHeaders() {}
}
