package com.zippy.backend.security;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
  private final ZippySecurityProperties securityProperties;
  private final ApiKeyAuthenticationFilter apiKeyFilter;
  private final WebhookSignatureFilter webhookSignatureFilter;
  private final SecurityErrorWriter errorWriter;
  private final boolean production;

  public SecurityConfig(
      ZippySecurityProperties securityProperties,
      ApiKeyAuthenticationFilter apiKeyFilter,
      WebhookSignatureFilter webhookSignatureFilter,
      SecurityErrorWriter errorWriter,
      Environment environment
  ) {
    this.securityProperties = securityProperties;
    this.apiKeyFilter = apiKeyFilter;
    this.webhookSignatureFilter = webhookSignatureFilter;
    this.errorWriter = errorWriter;
    this.production = environment.acceptsProfiles(Profiles.of("prod"));
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .csrf(AbstractHttpConfigurer::disable)
        .cors(Customizer.withDefaults())
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(exceptions -> exceptions
            .authenticationEntryPoint((request, response, exception) ->
                errorWriter.write(request, response, HttpStatus.UNAUTHORIZED, "Authentication required"))
            .accessDeniedHandler((request, response, exception) ->
                errorWriter.write(request, response, HttpStatus.FORBIDDEN, "Access denied")))
        .authorizeHttpRequests(authorize -> {
          authorize.requestMatchers(HttpMethod.GET, "/api/health").permitAll();
          authorize.requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll();
          authorize.requestMatchers("/api/webhooks/**").permitAll();

          if (production) {
            authorize.requestMatchers("/api/dev/**", "/api/mock-carriers/**").denyAll();
            authorize.requestMatchers("/fastship/**", "/quickexpress/**", "/reliablecourier/**").denyAll();
          }

          if (securityProperties.getApiKey().isEnabled()) {
            authorize.requestMatchers("/api/**").authenticated();
            authorize.requestMatchers(EndpointRequest.toAnyEndpoint()).authenticated();
          } else {
            authorize.requestMatchers("/api/**").permitAll();
            authorize.requestMatchers(EndpointRequest.toAnyEndpoint()).denyAll();
          }
          authorize.anyRequest().permitAll();
        })
        .addFilterBefore(webhookSignatureFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  public UserDetailsService userDetailsService() {
    return new InMemoryUserDetailsManager();
  }

  @Bean
  public FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyFilterRegistration() {
    FilterRegistrationBean<ApiKeyAuthenticationFilter> registration =
        new FilterRegistrationBean<>(apiKeyFilter);
    registration.setEnabled(false);
    return registration;
  }

  @Bean
  public FilterRegistrationBean<WebhookSignatureFilter> webhookFilterRegistration() {
    FilterRegistrationBean<WebhookSignatureFilter> registration =
        new FilterRegistrationBean<>(webhookSignatureFilter);
    registration.setEnabled(false);
    return registration;
  }
}
