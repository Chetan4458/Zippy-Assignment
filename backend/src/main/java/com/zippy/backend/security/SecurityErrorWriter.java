package com.zippy.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zippy.backend.exception.ApiExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
public class SecurityErrorWriter {
  private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

  private final ObjectMapper objectMapper;

  public SecurityErrorWriter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public void write(
      HttpServletRequest request,
      HttpServletResponse response,
      HttpStatus status,
      String message
  ) throws IOException {
    if (response.isCommitted()) {
      return;
    }

    String requestId = requestId(request);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("timestamp", Instant.now().toString());
    body.put("status", status.value());
    body.put("message", message);
    body.put("details", List.of());
    body.put("requestId", requestId);

    response.resetBuffer();
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.setHeader(ApiExceptionHandler.REQUEST_ID_HEADER, requestId);
    objectMapper.writeValue(response.getOutputStream(), body);
  }

  private String requestId(HttpServletRequest request) {
    String supplied = request.getHeader(ApiExceptionHandler.REQUEST_ID_HEADER);
    if (supplied != null && SAFE_REQUEST_ID.matcher(supplied).matches()) {
      return supplied;
    }
    return UUID.randomUUID().toString();
  }
}
