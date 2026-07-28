package com.zippy.backend.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AsyncConfig {
  @Bean(destroyMethod = "shutdownNow")
  public ExecutorService carrierExecutor() {
    return Executors.newCachedThreadPool();
  }
}
