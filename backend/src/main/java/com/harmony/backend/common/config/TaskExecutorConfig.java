package com.harmony.backend.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class TaskExecutorConfig {

    @Value("${app.agents.executor.core-pool-size:10}")
    private int corePoolSize;

    @Value("${app.agents.executor.max-pool-size:50}")
    private int maxPoolSize;

    @Value("${app.agents.executor.queue-capacity:100}")
    private int queueCapacity;

    @Value("${app.agents.executor.await-termination-seconds:30}")
    private int awaitTerminationSeconds;

    @Value("${app.webmvc.async.core-pool-size:4}")
    private int webMvcCorePoolSize;

    @Value("${app.webmvc.async.max-pool-size:16}")
    private int webMvcMaxPoolSize;

    @Value("${app.webmvc.async.queue-capacity:200}")
    private int webMvcQueueCapacity;

    @Bean(name = "agentExecutor")
    public Executor agentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("agent-pool-");
        // Rejection policy: CallerRunsPolicy is good for ensuring requests aren't just dropped
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
        executor.initialize();
        return executor;
    }

    @Bean(name = "webMvcTaskExecutor")
    public ThreadPoolTaskExecutor webMvcTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(webMvcCorePoolSize);
        executor.setMaxPoolSize(webMvcMaxPoolSize);
        executor.setQueueCapacity(webMvcQueueCapacity);
        executor.setThreadNamePrefix("webmvc-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "taskExecutor")
    public Executor taskExecutor(ThreadPoolTaskExecutor webMvcTaskExecutor) {
        return webMvcTaskExecutor;
    }
}
