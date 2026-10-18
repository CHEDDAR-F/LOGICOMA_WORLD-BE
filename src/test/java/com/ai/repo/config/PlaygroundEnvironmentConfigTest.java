package com.ai.repo.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaygroundEnvironmentConfigTest {
    private StandardEnvironment environment(Map<String, Object> variables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("releaseEnvironment", variables));
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        return environment;
    }

    @Test
    void stakesGateDefaultsOffAndRequiresExplicitDeploymentSwitch() throws IOException {
        assertEquals(false,environment(Map.of()).getProperty("playground.stakes-enabled",Boolean.class));
        assertEquals(true,environment(Map.of("PLAYGROUND_STAKES_ENABLED","true"))
                .getProperty("playground.stakes-enabled",Boolean.class));
    }

    @Test
    void ordersRequireExplicitDeploymentSwitch() throws IOException {
        assertEquals(false,environment(Map.of()).getProperty("playground.orders-enabled",Boolean.class));
        assertEquals(true,environment(Map.of("PLAYGROUND_ORDERS_ENABLED","true")).getProperty("playground.orders-enabled",Boolean.class));
    }
    @Test
    void annualGateDefaultsOff() throws IOException {
        assertEquals(false, environment(Map.of()).getProperty("playground.v6-enabled", Boolean.class));
    }

    @Test
    void annualGateCanBeEnabledByDeploymentEnvironment() throws IOException {
        assertEquals(true, environment(Map.of("PLAYGROUND_V6_ENABLED", "true"))
                .getProperty("playground.v6-enabled", Boolean.class));
    }
}
