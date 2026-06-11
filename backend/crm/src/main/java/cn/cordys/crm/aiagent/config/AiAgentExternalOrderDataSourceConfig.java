package cn.cordys.crm.aiagent.config;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(AiAgentExternalOrderProperties.class)
public class AiAgentExternalOrderDataSourceConfig {

    @Bean(name = "aiAgentExternalOrderDataSource")
    @ConditionalOnProperty(prefix = "crm.ai-agent.external-order", name = "enabled", havingValue = "true")
    public DataSource aiAgentExternalOrderDataSource(AiAgentExternalOrderProperties properties) {
        if (StringUtils.isAnyBlank(properties.getUrl(), properties.getUsername())) {
            throw new IllegalStateException("crm.ai-agent.external-order.url and username must be configured when enabled");
        }
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(properties.getUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(StringUtils.defaultString(properties.getPassword()));
        dataSource.setReadOnly(true);
        dataSource.setMaximumPoolSize(properties.getMaximumPoolSize());
        dataSource.setMinimumIdle(0);
        dataSource.setConnectionTimeout(properties.getConnectionTimeout());
        dataSource.setPoolName("AiAgentExternalOrderHikariCP");
        return dataSource;
    }

    @Bean(name = "aiAgentExternalOrderJdbcTemplate")
    @ConditionalOnBean(name = "aiAgentExternalOrderDataSource")
    public NamedParameterJdbcTemplate aiAgentExternalOrderJdbcTemplate(
            @Qualifier("aiAgentExternalOrderDataSource") DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }
}
