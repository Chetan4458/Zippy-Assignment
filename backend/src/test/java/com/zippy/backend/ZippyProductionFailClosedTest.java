package com.zippy.backend;

import com.zippy.backend.security.SecurityStartupValidator;
import com.zippy.backend.security.ZippySecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class ZippyProductionFailClosedTest {
  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(ValidationConfiguration.class)
      .withPropertyValues(
          "spring.profiles.active=prod",
          "zippy.security.api-key.enabled=true",
          "zippy.security.api-key.value=prod-integration-api-key-32-chars-minimum",
          "zippy.security.webhooks.enabled=true",
          "zippy.security.webhooks.secrets.fastship=prod-fastship-secret-32-characters-minimum",
          "zippy.security.webhooks.secrets.quickexpress=prod-quickexpress-secret-32-characters",
          "zippy.security.webhooks.secrets.reliable=prod-reliable-secret-32-characters-minimum"
      );

  @Test
  void refusesToStartProductionWithoutAnApiKey() {
    contextRunner
        .withPropertyValues("zippy.security.api-key.value=")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasStackTraceContaining("API key is required when its security control is enabled");
        });
  }

  @Test
  void refusesToStartProductionWithoutAWebhookSecret() {
    contextRunner
        .withPropertyValues("zippy.security.webhooks.secrets.quickexpress=")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasStackTraceContaining("quickexpress webhook secret is required when its security control is enabled");
        });
  }

  @Test
  void refusesToStartProductionWithATrivialWebhookSecret() {
    contextRunner
        .withPropertyValues("zippy.security.webhooks.secrets.quickexpress=too-short")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasStackTraceContaining("quickexpress webhook secret must contain at least 32 characters in production");
        });
  }

  @Test
  void refusesToStartProductionWithExamplePlaceholders() {
    contextRunner
        .withPropertyValues("zippy.security.api-key.value=replace-with-a-random-api-key-at-least-32-characters")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasStackTraceContaining("API key must not use a placeholder value in production");
        });
  }

  @Test
  void requiresAllProductionCredentialsToBeDistinct() {
    contextRunner
        .withPropertyValues(
            "zippy.security.webhooks.secrets.reliable=prod-fastship-secret-32-characters-minimum"
        )
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasStackTraceContaining("Production API key and webhook secrets must all be distinct");
        });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ZippySecurityProperties.class)
  @Import(SecurityStartupValidator.class)
  static class ValidationConfiguration {}
}
