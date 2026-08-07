package com.zippy.backend.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class WebhookSignatureFilter extends OncePerRequestFilter {
  private static final Pattern SAFE_EVENT_ID = Pattern.compile("[A-Za-z0-9._:-]{1,120}");
  private static final Pattern SIGNATURE = Pattern.compile("sha256=[0-9A-Fa-f]{64}");

  private final ZippySecurityProperties securityProperties;
  private final SecurityErrorWriter errorWriter;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  @Autowired
  public WebhookSignatureFilter(
      ZippySecurityProperties securityProperties,
      SecurityErrorWriter errorWriter,
      ObjectMapper objectMapper
  ) {
    this(securityProperties, errorWriter, objectMapper, Clock.systemUTC());
  }

  WebhookSignatureFilter(
      ZippySecurityProperties securityProperties,
      SecurityErrorWriter errorWriter,
      ObjectMapper objectMapper,
      Clock clock
  ) {
    this.securityProperties = securityProperties;
    this.errorWriter = errorWriter;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!securityProperties.getWebhooks().isEnabled()) {
      return true;
    }
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return !HttpMethod.POST.matches(request.getMethod()) || !path.startsWith("/api/webhooks/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain
  ) throws ServletException, IOException {
    int maximumBodySize = securityProperties.getWebhooks().getMaxBodyBytes();
    byte[] body = request.getInputStream().readNBytes(maximumBodySize + 1);
    if (body.length > maximumBodySize) {
      errorWriter.write(request, response, HttpStatus.PAYLOAD_TOO_LARGE, "Webhook payload is too large");
      return;
    }

    String timestamp = request.getHeader(SecurityHeaders.WEBHOOK_TIMESTAMP);
    String eventId = request.getHeader(SecurityHeaders.WEBHOOK_EVENT_ID);
    String signature = request.getHeader(SecurityHeaders.WEBHOOK_SIGNATURE);
    String carrier = webhookCarrier(request);
    String secret = securityProperties.getWebhooks().secretFor(carrier);

    if (!validTimestamp(timestamp)
        || eventId == null
        || !SAFE_EVENT_ID.matcher(eventId).matches()
        || signature == null
        || !SIGNATURE.matcher(signature).matches()
        || secret == null
        || !validSignature(secret, timestamp, eventId, body, signature)
        || !eventIdMatchesBody(carrier, eventId, body)) {
      errorWriter.write(request, response, HttpStatus.UNAUTHORIZED, "Invalid or stale webhook signature");
      return;
    }

    filterChain.doFilter(new CachedBodyHttpServletRequest(request, body), response);
  }

  private String webhookCarrier(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    String carrier = path.substring("/api/webhooks/".length());
    int slash = carrier.indexOf('/');
    if (slash >= 0) {
      carrier = carrier.substring(0, slash);
    }
    return carrier.toLowerCase(Locale.ROOT);
  }

  private boolean validTimestamp(String value) {
    if (value == null || !value.matches("[0-9]{1,12}")) {
      return false;
    }
    try {
      Instant signedAt = Instant.ofEpochSecond(Long.parseLong(value));
      Duration age = Duration.between(signedAt, clock.instant()).abs();
      return age.compareTo(Duration.ofSeconds(securityProperties.getWebhooks().getMaxAgeSeconds())) <= 0;
    } catch (NumberFormatException | DateTimeException | ArithmeticException exception) {
      return false;
    }
  }

  private boolean validSignature(
      String secret,
      String timestamp,
      String eventId,
      byte[] body,
      String suppliedSignature
  ) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update((timestamp + "." + eventId + ".").getBytes(StandardCharsets.UTF_8));
      byte[] expected = mac.doFinal(body);
      byte[] supplied = HexFormat.of().parseHex(suppliedSignature.substring("sha256=".length()));
      return MessageDigest.isEqual(expected, supplied);
    } catch (GeneralSecurityException | IllegalArgumentException exception) {
      return false;
    }
  }

  private boolean eventIdMatchesBody(String carrier, String signedEventId, byte[] body) {
    try {
      JsonNode payload = objectMapper.readTree(body);
      if (payload == null || !payload.isObject()) {
        return false;
      }
      JsonNode eventIdNode = payload.path("event_id");
      if (!eventIdNode.isTextual()) {
        eventIdNode = payload.path("eventId");
      }
      if (!eventIdNode.isTextual() && "quickexpress".equals(carrier)) {
        eventIdNode = payload.path("event").path("id");
      }
      return eventIdNode.isTextual() && signedEventId.equals(eventIdNode.asText().trim());
    } catch (IOException exception) {
      return false;
    }
  }
}
