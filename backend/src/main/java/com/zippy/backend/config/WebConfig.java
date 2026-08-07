package com.zippy.backend.config;

import com.zippy.backend.security.SecurityHeaders;
import com.zippy.backend.security.ZippySecurityProperties;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final ZippySecurityProperties securityProperties;

  public WebConfig(ZippySecurityProperties securityProperties) {
    this.securityProperties = securityProperties;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    List<String> allowedOrigins = securityProperties.getCors().getAllowedOriginPatterns();
    if (allowedOrigins.isEmpty()) {
      return;
    }
    registry.addMapping("/**")
        .allowedOriginPatterns(allowedOrigins.toArray(String[]::new))
        .allowedMethods("GET", "POST", "PUT", "OPTIONS")
        .allowedHeaders(
            "Content-Type",
            "Idempotency-Key",
            "X-Request-Id",
            securityProperties.getApiKey().getHeader(),
            SecurityHeaders.WEBHOOK_TIMESTAMP,
            SecurityHeaders.WEBHOOK_EVENT_ID,
            SecurityHeaders.WEBHOOK_SIGNATURE
        )
        .exposedHeaders("X-Request-Id")
        .maxAge(3600);
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry.addResourceHandler("/assets/**")
        .addResourceLocations("file:dist/assets/", "file:public/assets/");
    registry.addResourceHandler("/**")
        .addResourceLocations("file:dist/", "file:public/");
  }

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    registry.addViewController("/").setViewName("forward:/index.html");
    registry.addViewController("/{path:(?!api$)[^\\.]*}").setViewName("forward:/index.html");
  }
}
