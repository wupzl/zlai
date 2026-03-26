package com.harmony.backend.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@Slf4j
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

    @Value("${app.webmvc.async.await-termination-seconds:30}")
    private int webMvcAwaitTerminationSeconds;

    @Value("${app.async.background.core-pool-size:4}")
    private int backgroundCorePoolSize;

    @Value("${app.async.background.max-pool-size:8}")
    private int backgroundMaxPoolSize;

    @Value("${app.async.background.queue-capacity:120}")
    private int backgroundQueueCapacity;

    @Value("${app.async.background.await-termination-seconds:30}")
    private int backgroundAwaitTerminationSeconds;

    @Value("${app.rag.async.core-pool-size:2}")
    private int ragCorePoolSize;

    @Value("${app.rag.async.max-pool-size:6}")
    private int ragMaxPoolSize;

    @Value("${app.rag.async.queue-capacity:40}")
    private int ragQueueCapacity;

    @Value("${app.rag.async.await-termination-seconds:30}")
    private int ragAwaitTerminationSeconds;

    @Value("${app.user.activity.async.core-pool-size:1}")
    private int userActivityCorePoolSize;

    @Value("${app.user.activity.async.max-pool-size:2}")
    private int userActivityMaxPoolSize;

    @Value("${app.user.activity.async.queue-capacity:200}")
    private int userActivityQueueCapacity;

    @Value("${app.user.activity.async.await-termination-seconds:10}")
    private int userActivityAwaitTerminationSeconds;

    @Value("${app.tools.search.executor.pool-size:12}")
    private int webSearchPoolSize;

    @Bean(name = "agentExecutor")
    public Executor agentExecutor() {
        return buildExecutor(
                "agent-pool-",
                corePoolSize,
                maxPoolSize,
                queueCapacity,
                awaitTerminationSeconds,
                "agentExecutor"
        );
    }

    @Bean(name = "webMvcTaskExecutor")
    public ThreadPoolTaskExecutor webMvcTaskExecutor() {
        return buildExecutor(
                "webmvc-",
                webMvcCorePoolSize,
                webMvcMaxPoolSize,
                webMvcQueueCapacity,
                webMvcAwaitTerminationSeconds,
                "webMvcTaskExecutor"
        );
    }

    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        return buildExecutor(
                "async-bg-",
                backgroundCorePoolSize,
                backgroundMaxPoolSize,
                backgroundQueueCapacity,
                backgroundAwaitTerminationSeconds,
                "taskExecutor"
        );
    }

    @Bean(name = "ragTaskExecutor")
    public ThreadPoolTaskExecutor ragTaskExecutor() {
        return buildExecutor(
                "rag-async-",
                ragCorePoolSize,
                ragMaxPoolSize,
                ragQueueCapacity,
                ragAwaitTerminationSeconds,
                "ragTaskExecutor"
        );
    }

    @Bean(name = "userActivityExecutor")
    public ThreadPoolTaskExecutor userActivityExecutor() {
        return buildExecutor(
                "user-activity-",
                userActivityCorePoolSize,
                userActivityMaxPoolSize,
                userActivityQueueCapacity,
                userActivityAwaitTerminationSeconds,
                "userActivityExecutor"
        );
    }

    @Bean(name = "webSearchExecutor", destroyMethod = "shutdown")
    public ExecutorService webSearchExecutor() {
        return Executors.newFixedThreadPool(Math.max(2, webSearchPoolSize));
    }

    private ThreadPoolTaskExecutor buildExecutor(String threadNamePrefix,
                                                 int corePoolSize,
                                                 int maxPoolSize,
                                                 int queueCapacity,
                                                 int awaitTerminationSeconds,
                                                 String executorName) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new LoggingAbortPolicy(executorName));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
        executor.initialize();
        return executor;
    }

    private static final class LoggingAbortPolicy implements RejectedExecutionHandler {
        private final String executorName;

        private LoggingAbortPolicy(String executorName) {
            this.executorName = executorName;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            int queueSize = executor.getQueue() == null ? -1 : executor.getQueue().size();
            log.warn("Executor saturated: executorName={}, poolSize={}, activeCount={}, queueSize={}",
                    executorName,
                    executor.getPoolSize(),
                    executor.getActiveCount(),
                    queueSize);
            throw new RejectedExecutionException("Executor is saturated: " + executorName);
        }
    }
}
