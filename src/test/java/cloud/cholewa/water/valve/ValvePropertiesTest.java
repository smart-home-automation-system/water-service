package cloud.cholewa.water.valve;

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

//application.yaml as the home profile reads it (HAS-316): the staleness of the valve and the retention of its counter -
//their units and their bounds, values that decide what is shown and what is deleted
class ValvePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class);

    @Test
    void should_leave_out_a_value_after_five_minutes_and_keep_the_counter_for_two_years() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> {
                assertThat(context.getBean(ValveProperties.class).staleAfter()).isEqualTo(Duration.ofMinutes(5));
                assertThat(context.getBean(MeterRetentionProperties.class).retention()).isEqualTo(Duration.ofDays(730));
            });
    }

    //without the unit a bare number would bind as milliseconds: every value stale at once, everything deleted the first
    //night
    @Test
    void should_read_a_bare_number_as_minutes_and_days() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "water.valve.stale-after=10", "water.meter.retention=400")
            .run(context -> {
                assertThat(context.getBean(ValveProperties.class).staleAfter()).isEqualTo(Duration.ofMinutes(10));
                assertThat(context.getBean(MeterRetentionProperties.class).retention()).isEqualTo(Duration.ofDays(400));
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"water.valve.stale-after=1", "water.valve.stale-after=PT61M", "water.meter.retention=365",
        "water.meter.retention=PT12H", "water.meter.retention=3651"})
    void should_not_start_with_a_value_out_of_bounds(final String value) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", value)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ValveProperties.class, MeterRetentionProperties.class})
    static class Properties {
    }
}
