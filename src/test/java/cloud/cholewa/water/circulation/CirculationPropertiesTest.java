package cloud.cholewa.water.circulation;

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

//application.yaml as the home profile reads it (HAS-404): the circulation pump - the values of the task, their units
//and their limits
class CirculationPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class);

    @Test
    void should_take_the_values_of_the_task() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> assertThat(context.getBean(CirculationProperties.class))
                .isEqualTo(new CirculationProperties(35.0, Duration.ofMinutes(3), 38.0, true)));
    }

    //without the unit a bare number would bind as milliseconds: a run of 3 ms, a timer the client refuses
    @Test
    void should_read_a_bare_duration_as_minutes() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "water.circulation.max-run=2")
            .run(context -> assertThat(context.getBean(CirculationProperties.class).maxRun())
                .isEqualTo(Duration.ofMinutes(2)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"water.circulation.return-target=19.9", "water.circulation.return-target=60.1",
        "water.circulation.max-run=PT29S", "water.circulation.max-run=11", "water.circulation.tank-min=19",
        "water.circulation.tank-min=81", "water.circulation.stop-on-return="})
    void should_not_start_with_a_value_out_of_bounds(final String value) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", value)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CirculationProperties.class)
    static class Properties {
    }
}
