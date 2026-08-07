package com.zippy.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
  private final ZippySecurityProperties securityProperties;
  private final SecurityErrorWriter errorWriter;

  public ApiKeyAuthenticationFilter(
      ZippySecurityProperties securityProperties,
      SecurityErrorWriter errorWriter
  ) {
    this.securityProperties = securityProperties;
    this.errorWriter = errorWriter;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!securityProperties.getApiKey().isEnabled()) {
      return true;
    }
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (isPublicHealth(request, path) || path.startsWith("/api/webhooks/")) {
      return true;
    }
    return !(path.equals("/api")
        || path.startsWith("/api/")
        || path.equals("/actuator")
        || path.startsWith("/actuator/"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain
  ) throws ServletException, IOException {
    String configuredKey = securityProperties.getApiKey().getValue();
    String suppliedKey = request.getHeader(securityProperties.getApiKey().getHeader());
    if (!constantTimeEquals(configuredKey, suppliedKey)) {
      errorWriter.write(request, response, HttpStatus.UNAUTHORIZED, "Missing or invalid API key");
      return;
    }

    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(new UsernamePasswordAuthenticationToken(
        "zippy-api-key",
        null,
        List.of(new SimpleGrantedAuthority("ROLE_API"))
    ));
    SecurityContextHolder.setContext(context);
    try {
      filterChain.doFilter(request, response);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private boolean isPublicHealth(HttpServletRequest request, String path) {
    return HttpMethod.GET.matches(request.getMethod())
        && ("/api/health".equals(path) || "/actuator/health".equals(path));
  }

  private boolean constantTimeEquals(String expected, String supplied) {
    if (expected == null || supplied == null) {
      return false;
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] expectedDigest = digest.digest(expected.getBytes(StandardCharsets.UTF_8));
      byte[] suppliedDigest = digest.digest(supplied.getBytes(StandardCharsets.UTF_8));
      return MessageDigest.isEqual(expectedDigest, suppliedDigest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
