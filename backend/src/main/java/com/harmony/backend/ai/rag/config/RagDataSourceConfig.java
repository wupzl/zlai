package com.harmony.backend.ai.rag.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class RagDataSourceConfig {

    private DataSource buildRagDataSource(RagProperties properties) {
        RagProperties.Datasource ds = properties.getDatasource();
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(ds.getUrl());
        dataSource.setUsername(ds.getUsername());
        dataSource.setPassword(ds.getPassword());
        dataSource.setDriverClassName(ds.getDriverClassName());
        return dataSource;
    }

    @Bean(name = "ragJdbcTemplate")
    @ConditionalOnProperty(prefix = "app.rag.datasource", name = "url")
    public JdbcTemplate ragJdbcTemplate(RagProperties properties) {
        return new JdbcTemplate(buildRagDataSource(properties));
    }

    @Bean(name = "ragTransactionManager")
    @ConditionalOnProperty(prefix = "app.rag.datasource", name = "url")
    public DataSourceTransactionManager ragTransactionManager(RagProperties properties) {
        return new DataSourceTransactionManager(buildRagDataSource(properties));
    }
}
