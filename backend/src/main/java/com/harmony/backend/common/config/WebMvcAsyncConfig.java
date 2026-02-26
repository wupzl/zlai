package com.harmony.backend.common.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcAsyncConfig implements WebMvcConfigurer {

    private final AsyncTaskExecutor webMvcTaskExecutor;

    public WebMvcAsyncConfig(@Qualifier("webMvcTaskExecutor") AsyncTaskExecutor webMvcTaskExecutor) {
        this.webMvcTaskExecutor = webMvcTaskExecutor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(webMvcTaskExecutor);
    }
}
