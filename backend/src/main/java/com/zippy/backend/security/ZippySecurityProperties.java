package com.zippy.backend.security;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "zippy.security")
public class ZippySecurityProperties {
  private final ApiKey apiKey = new ApiKey();
  private final Webhooks webhooks = new Webhooks();
  private final Cors cors = new Cors();

  public ApiKey getApiKey() {
    return apiKey;
  }

  public Webhooks getWebhooks() {
    return webhooks;
  }

  public Cors getCors() {
    return cors;
  }

  public static class ApiKey {
    private boolean enabled = true;
    private String header = SecurityHeaders.API_KEY;
    private String value;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getHeader() {
      return header;
    }

    public void setHeader(String header) {
      this.header = header;
    }

    public String getValue() {
      return value;
    }

    public void setValue(String value) {
      this.value = value;
    }
  }

  public static class Webhooks {
    private boolean enabled = true;
    private long maxAgeSeconds = 300;
    private int maxBodyBytes = 1_048_576;
    private Map<String, String> secrets = new HashMap<>();

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public long getMaxAgeSeconds() {
      return maxAgeSeconds;
    }

    public void setMaxAgeSeconds(long maxAgeSeconds) {
      this.maxAgeSeconds = maxAgeSeconds;
    }

    public int getMaxBodyBytes() {
      return maxBodyBytes;
    }

    public void setMaxBodyBytes(int maxBodyBytes) {
      this.maxBodyBytes = maxBodyBytes;
    }

    public Map<String, String> getSecrets() {
      return secrets;
    }

    public void setSecrets(Map<String, String> secrets) {
      this.secrets = secrets == null ? new HashMap<>() : new HashMap<>(secrets);
    }

    public String secretFor(String carrier) {
      if (carrier == null) {
        return null;
      }
      return secrets.get(carrier.toLowerCase());
    }
  }

  public static class Cors {
    private List<String> allowedOriginPatterns = new ArrayList<>();

    public List<String> getAllowedOriginPatterns() {
      return allowedOriginPatterns;
    }

    public void setAllowedOriginPatterns(List<String> allowedOriginPatterns) {
      this.allowedOriginPatterns = allowedOriginPatterns == null
          ? new ArrayList<>()
          : allowedOriginPatterns.stream().filter(value -> value != null && !value.isBlank()).toList();
    }
  }
}
