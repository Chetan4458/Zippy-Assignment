package com.zippy.backend.security;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class SecurityStartupValidator implements InitializingBean {
  private static final List<String> REQUIRED_CARRIERS = List.of("fastship", "quickexpress", "reliable");
  private static final List<String> PLACEHOLDER_MARKERS = List.of(
      "replace-with",
      "change-me",
      "changeme",
      "placeholder",
      "your-api-key",
      "your-secret"
  );

  private final ZippySecurityProperties securityProperties;
  private final Environment environment;

  public SecurityStartupValidator(
      ZippySecurityProperties securityProperties,
      Environment environment
  ) {
    this.securityProperties = securityProperties;
    this.environment = environment;
  }

  @Override
  public void afterPropertiesSet() {
    boolean production = environment.acceptsProfiles(Profiles.of("prod"));
    boolean localOrTest = environment.acceptsProfiles(Profiles.of("local", "test"));

    if (production && !securityProperties.getApiKey().isEnabled()) {
      throw new IllegalStateException("Production API-key authentication cannot be disabled");
    }
    if (!securityProperties.getWebhooks().isEnabled() && !localOrTest) {
      throw new IllegalStateException("Webhook verification can only be disabled in local or test profiles");
    }

    if (securityProperties.getApiKey().isEnabled()) {
      requireNonBlank(securityProperties.getApiKey().getValue(), "API key");
      requireNonBlank(securityProperties.getApiKey().getHeader(), "API-key header name");
      if (production) {
        requireMinimumLength(securityProperties.getApiKey().getValue(), "API key");
        rejectPlaceholder(securityProperties.getApiKey().getValue(), "API key");
      }
    }

    if (securityProperties.getWebhooks().isEnabled()) {
      long maxAge = securityProperties.getWebhooks().getMaxAgeSeconds();
      if (maxAge < 1 || maxAge > 3_600) {
        throw new IllegalStateException("Webhook replay window must be between 1 and 3600 seconds");
      }
      int maxBodyBytes = securityProperties.getWebhooks().getMaxBodyBytes();
      if (maxBodyBytes < 1 || maxBodyBytes > 10_485_760) {
        throw new IllegalStateException("Webhook body limit must be between 1 byte and 10 MiB");
      }
      for (String carrier : REQUIRED_CARRIERS) {
        String secret = securityProperties.getWebhooks().secretFor(carrier);
        requireNonBlank(secret, carrier + " webhook secret");
        if (production) {
          requireMinimumLength(secret, carrier + " webhook secret");
          rejectPlaceholder(secret, carrier + " webhook secret");
        }
      }
    }

    if (production) {
      requireDistinctProductionCredentials();
    }
  }

  private void requireNonBlank(String value, String description) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(description + " is required when its security control is enabled");
    }
  }

  private void requireMinimumLength(String value, String description) {
    if (value.length() < 32) {
      throw new IllegalStateException(description + " must contain at least 32 characters in production");
    }
  }

  private void rejectPlaceholder(String value, String description) {
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    if (PLACEHOLDER_MARKERS.stream().anyMatch(normalized::contains)) {
      throw new IllegalStateException(description + " must not use a placeholder value in production");
    }
  }

  private void requireDistinctProductionCredentials() {
    Set<String> credentials = new HashSet<>();
    credentials.add(securityProperties.getApiKey().getValue());
    for (String carrier : REQUIRED_CARRIERS) {
      credentials.add(securityProperties.getWebhooks().secretFor(carrier));
    }
    if (credentials.size() != REQUIRED_CARRIERS.size() + 1) {
      throw new IllegalStateException("Production API key and webhook secrets must all be distinct");
    }
  }
}
