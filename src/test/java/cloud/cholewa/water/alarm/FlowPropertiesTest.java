package cloud.cholewa.water.alarm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

//application.yaml as the home profile reads it (HAS-320): the flow alarms with the defaults the owner accepted on
//2026-10-23, their units and their limits
class FlowPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class);

    @Test
    void should_take_the_defaults_of_the_owner() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> assertThat(context.getBean(FlowProperties.class)).isEqualTo(new FlowProperties(25.0,
                Duration.ofMinutes(3), Duration.ofMinutes(1), Duration.ofMinutes(30), 0.5, Duration.ofMinutes(2), false)));
    }

    //without the unit a bare number would bind as milliseconds: an alarm at the first drop
    @Test
    void should_read_a_bare_duration_as_minutes() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "water.flow.high-after=4", "water.flow.continuous-pause=2",
                "water.flow.continuous-after=45", "water.flow.empty-house-after=10")
            .run(context -> {
                final FlowProperties properties = context.getBean(FlowProperties.class);
                assertThat(properties.highAfter()).isEqualTo(Duration.ofMinutes(4));
                assertThat(properties.continuousPause()).isEqualTo(Duration.ofMinutes(2));
                assertThat(properties.continuousAfter()).isEqualTo(Duration.ofMinutes(45));
                assertThat(properties.emptyHouseAfter()).isEqualTo(Duration.ofMinutes(10));
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"water.flow.high-above=0.9", "water.flow.high-above=76", "water.flow.high-after=PT30S",
        "water.flow.high-after=PT25H", "water.flow.continuous-pause=PT30S", "water.flow.continuous-pause=31",
        "water.flow.continuous-after=4", "water.flow.continuous-after=PT25H", "water.flow.empty-house-above=0.09",
        "water.flow.empty-house-above=11", "water.flow.empty-house-after=PT30S", "water.flow.empty-house-after=PT25H"})
    void should_not_start_with_a_value_out_of_bounds(final String value) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", value)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FlowProperties.class)
    static class Properties {
    }
}
