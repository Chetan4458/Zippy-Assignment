package com.zippy.backend.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
  public static final String REQUEST_ID_HEADER = "X-Request-Id";

  private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);
  private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Map<String, Object>> handleApiException(
      ApiException exception,
      HttpServletRequest request
  ) {
    return response(
        request,
        HttpStatusCode.valueOf(exception.getStatusCode()),
        exception.getMessage(),
        exception.getDetails()
    );
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> handleBeanValidation(
      MethodArgumentNotValidException exception,
      HttpServletRequest request
  ) {
    List<String> details = exception.getBindingResult().getFieldErrors().stream()
        .map(error -> error.getField() + ": " + error.getDefaultMessage())
        .distinct()
        .toList();
    return response(request, HttpStatus.BAD_REQUEST, "Validation failed", details);
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<Map<String, Object>> handleMethodValidation(
      HandlerMethodValidationException exception,
      HttpServletRequest request
  ) {
    List<String> details = exception.getAllErrors().stream()
        .map(MessageSourceResolvable::getDefaultMessage)
        .distinct()
        .toList();
    return response(request, HttpStatus.BAD_REQUEST, "Validation failed", details);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<Map<String, Object>> handleConstraintValidation(
      ConstraintViolationException exception,
      HttpServletRequest request
  ) {
    List<String> details = exception.getConstraintViolations().stream()
        .map(this::constraintMessage)
        .sorted()
        .toList();
    return response(request, HttpStatus.BAD_REQUEST, "Validation failed", details);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, Object>> handleMalformedJson(
      HttpMessageNotReadableException exception,
      HttpServletRequest request
  ) {
    return response(request, HttpStatus.BAD_REQUEST, "Malformed JSON request", List.of());
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<Map<String, Object>> handleMissingParameter(
      MissingServletRequestParameterException exception,
      HttpServletRequest request
  ) {
    return response(
        request,
        HttpStatus.BAD_REQUEST,
        "Missing request parameter",
        List.of(exception.getParameterName() + " is required")
    );
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Map<String, Object>> handleTypeMismatch(
      MethodArgumentTypeMismatchException exception,
      HttpServletRequest request
  ) {
    return response(
        request,
        HttpStatus.BAD_REQUEST,
        "Invalid request parameter",
        List.of(exception.getName() + " has an invalid value")
    );
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<Map<String, Object>> handleDataConflict(
      DataIntegrityViolationException exception,
      HttpServletRequest request
  ) {
    String requestId = requestId(request);
    LOGGER.info("Data conflict [{}] {} {}", requestId, request.getMethod(), request.getRequestURI());
    return response(
        request,
        HttpStatus.CONFLICT,
        "Request conflicts with existing data",
        List.of(),
        requestId
    );
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<Map<String, Object>> handleNotFound(
      NoResourceFoundException exception,
      HttpServletRequest request
  ) {
    return response(request, HttpStatus.NOT_FOUND, "Resource not found", List.of());
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<Map<String, Object>> handleMethodNotAllowed(
      HttpRequestMethodNotSupportedException exception,
      HttpServletRequest request
  ) {
    return response(request, HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed", List.of());
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<Map<String, Object>> handleUnsupportedMediaType(
      HttpMediaTypeNotSupportedException exception,
      HttpServletRequest request
  ) {
    return response(request, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type", List.of());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleUnexpected(
      Exception exception,
      HttpServletRequest request
  ) {
    String requestId = requestId(request);
    LOGGER.error(
        "Unexpected API error [{}] {} {}",
        requestId,
        request.getMethod(),
        request.getRequestURI(),
        exception
    );
    return response(request, HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", List.of(), requestId);
  }

  private String constraintMessage(ConstraintViolation<?> violation) {
    String path = violation.getPropertyPath() == null ? "request" : violation.getPropertyPath().toString();
    int separator = path.lastIndexOf('.');
    String parameter = separator >= 0 ? path.substring(separator + 1) : path;
    return parameter + ": " + violation.getMessage();
  }

  private ResponseEntity<Map<String, Object>> response(
      HttpServletRequest request,
      HttpStatusCode status,
      String message,
      List<String> details
  ) {
    return response(request, status, message, details, requestId(request));
  }

  private ResponseEntity<Map<String, Object>> response(
      HttpServletRequest request,
      HttpStatusCode status,
      String message,
      List<String> details,
      String requestId
  ) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("timestamp", Instant.now().toString());
    body.put("status", status.value());
    body.put("message", message);
    body.put("details", details == null ? List.of() : details);
    body.put("requestId", requestId);

    HttpHeaders headers = new HttpHeaders();
    headers.set(REQUEST_ID_HEADER, requestId);
    return new ResponseEntity<>(body, headers, status);
  }

  private String requestId(HttpServletRequest request) {
    String supplied = request.getHeader(REQUEST_ID_HEADER);
    if (supplied != null && SAFE_REQUEST_ID.matcher(supplied).matches()) {
      return supplied;
    }
    return UUID.randomUUID().toString();
  }
}
