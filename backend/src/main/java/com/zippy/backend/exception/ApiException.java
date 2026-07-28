package com.zippy.backend.exception;

import java.util.List;

public class ApiException extends RuntimeException {
  private final int statusCode;
  private final List<String> details;

  public ApiException(int statusCode, String message) {
    this(statusCode, message, null);
  }

  public ApiException(int statusCode, String message, List<String> details) {
    super(message);
    this.statusCode = statusCode;
    this.details = details;
  }

  public int getStatusCode() {
    return statusCode;
  }

  public List<String> getDetails() {
    return details;
  }
}
