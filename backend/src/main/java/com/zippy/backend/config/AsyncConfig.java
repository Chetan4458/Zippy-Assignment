package com.zippy.backend.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AsyncConfig {
  @Bean(name = "carrierExecutor", destroyMethod = "shutdown")
  public ExecutorService carrierExecutor(
      @Value("${zippy.carrier-worker-threads:6}") int workerThreads,
      @Value("${zippy.carrier-queue-capacity:100}") int queueCapacity
  ) {
    int safeWorkers = Math.max(1, Math.min(workerThreads, 32));
    int safeQueueCapacity = Math.max(1, Math.min(queueCapacity, 10_000));
    return new ThreadPoolExecutor(
        safeWorkers,
        safeWorkers,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(safeQueueCapacity),
        namedThreadFactory("zippy-carrier-"),
        new ThreadPoolExecutor.AbortPolicy()
    );
  }

  @Bean(name = "automationScheduler", destroyMethod = "shutdown")
  public ScheduledExecutorService automationScheduler(
      @Value("${zippy.automation-worker-threads:2}") int workerThreads
  ) {
    int safeWorkers = Math.max(1, Math.min(workerThreads, 8));
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(
        safeWorkers,
        namedThreadFactory("zippy-automation-")
    );
    scheduler.setRemoveOnCancelPolicy(true);
    scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    return scheduler;
  }

  private ThreadFactory namedThreadFactory(String prefix) {
    AtomicInteger sequence = new AtomicInteger();
    return task -> {
      Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
      thread.setDaemon(false);
      return thread;
    };
  }
}
