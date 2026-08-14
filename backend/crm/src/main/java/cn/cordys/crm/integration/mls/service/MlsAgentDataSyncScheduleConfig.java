package cn.cordys.crm.integration.mls.service;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Configuration(proxyBeanMethods = false)
public class MlsAgentDataSyncScheduleConfig {

    static final String TIME_PROPERTY = "crm.mls-sync.time";
    static final String RESOLVED_CRON_PROPERTY = "crm.mls-sync.resolved-cron";
    static final String DEFAULT_TIME = "00:00";

    private static final String PROPERTY_SOURCE_NAME = "mlsAgentDataSyncSchedule";
    private static final Pattern DAILY_TIME_PATTERN = Pattern.compile("^(?:[01]\\d|2[0-3]):[0-5]\\d$");

    @Bean
    static BeanFactoryPostProcessor mlsAgentDataSyncSchedulePropertyConfigurer(
            ConfigurableEnvironment environment) {
        return beanFactory -> {
            String configuredTime = environment.getProperty(TIME_PROPERTY, DEFAULT_TIME);
            String resolvedCron = toDailyCron(configuredTime);
            environment.getPropertySources().addFirst(new MapPropertySource(
                    PROPERTY_SOURCE_NAME,
                    Map.of(RESOLVED_CRON_PROPERTY, resolvedCron)
            ));
        };
    }

    static String toDailyCron(String configuredTime) {
        String normalizedTime = configuredTime == null ? "" : configuredTime.trim();
        Matcher matcher = DAILY_TIME_PATTERN.matcher(normalizedTime);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    TIME_PROPERTY + " must use HH:mm (00:00-23:59), but was: " + configuredTime
            );
        }

        int separator = normalizedTime.indexOf(':');
        int hour = Integer.parseInt(normalizedTime.substring(0, separator));
        int minute = Integer.parseInt(normalizedTime.substring(separator + 1));
        return "0 %d %d * * ?".formatted(minute, hour);
    }
}
