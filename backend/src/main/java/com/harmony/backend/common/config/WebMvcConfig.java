package com.harmony.backend.common.config;

import com.harmony.backend.common.resolver.CurrentUserArgumentResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUserArgumentResolver;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(
                        "http://localhost:5173",
                        "http://127.0.0.1:5173",
                        "http://localhost:8080",
                        "http://127.0.0.1:8080"
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD")
                .allowedHeaders(
                        "Origin", "Content-Type", "Accept", "Authorization",
                        "X-Requested-With", "X-Auth-Token",
                        "Access-Control-Request-Method", "Access-Control-Request-Headers"
                )
                .exposedHeaders(
                        "Authorization", "Content-Disposition", "Content-Type",
                        "X-Total-Count", "X-Page-Size", "X-Current-Page",
                        "X-Access-Token", "X-Refresh-Token"
                )
                .allowCredentials(true)
                .maxAge(1800);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }
}