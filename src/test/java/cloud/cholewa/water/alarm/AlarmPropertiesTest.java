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

//application.yaml as the home profile reads it (HAS-319): the bounds of the alarms of the water valve - their units and
//their limits, values that decide when the household is woken
class AlarmPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class);

    @Test
    void should_alarm_below_one_bar_and_three_degrees_after_five_minutes_and_remind_every_hour() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> assertThat(context.getBean(AlarmProperties.class)).isEqualTo(new AlarmProperties(100.0,
                Duration.ofMinutes(5), Duration.ofMinutes(5), 3.0, Duration.ofMinutes(5), Duration.ofHours(1))));
    }

    //without the unit a bare number would bind as milliseconds: every alarm at its first low reading
    @Test
    void should_read_a_bare_duration_as_minutes() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "water.alarm.pressure-low-after=10",
                "water.alarm.offline-after=15", "water.alarm.frost-after=20", "water.alarm.reminder-every=120")
            .run(context -> {
                final AlarmProperties properties = context.getBean(AlarmProperties.class);
                assertThat(properties.pressureLowAfter()).isEqualTo(Duration.ofMinutes(10));
                assertThat(properties.offlineAfter()).isEqualTo(Duration.ofMinutes(15));
                assertThat(properties.frostAfter()).isEqualTo(Duration.ofMinutes(20));
                assertThat(properties.reminderEvery()).isEqualTo(Duration.ofHours(2));
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"water.alarm.pressure-low-below=9", "water.alarm.pressure-low-below=401",
        "water.alarm.pressure-low-after=PT30S", "water.alarm.offline-after=1", "water.alarm.frost-below=0.4",
        "water.alarm.frost-below=11", "water.alarm.frost-after=PT25H", "water.alarm.reminder-every=9"})
    void should_not_start_with_a_value_out_of_bounds(final String value) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", value)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AlarmProperties.class)
    static class Properties {
    }
}
