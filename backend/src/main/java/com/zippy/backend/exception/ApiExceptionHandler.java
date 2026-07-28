package com.zippy.backend.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Map<String, Object>> handle(ApiException exception) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("message", exception.getMessage());
    body.put("details", exception.getDetails());
    return ResponseEntity.status(exception.getStatusCode()).body(body);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleUnexpected(Exception exception) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("message", exception.getMessage() == null ? "Unexpected error" : exception.getMessage());
    body.put("details", null);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
  }
}
