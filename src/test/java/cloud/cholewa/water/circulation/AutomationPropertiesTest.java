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

//application.yaml as the home profile reads it (HAS-405): the automation of the circulation - the values of the task and
//the owner's decision of 2026-11-06 (the rise), their units and their limits
class AutomationPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class);

    @Test
    void should_take_the_values_of_the_task() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> assertThat(context.getBean(AutomationProperties.class))
                .isEqualTo(AutomationRulesTest.AUTOMATION));
    }

    //without the unit a bare number would bind as milliseconds: a pause of 20 ms is none
    @Test
    void should_read_a_bare_pause_as_minutes() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "water.circulation.automation.pause-after=30")
            .run(context -> assertThat(context.getBean(AutomationProperties.class).pauseAfter())
                .isEqualTo(Duration.ofMinutes(30)));
    }

    @Test
    void should_switch_the_automation_off_without_a_release() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "water.circulation.automation.enabled=false")
            .run(context -> assertThat(context.getBean(AutomationProperties.class).enabled()).isFalse());
    }

    @ParameterizedTest
    @ValueSource(strings = {"water.circulation.automation.start-below=14", "water.circulation.automation.start-below=61",
        "water.circulation.automation.pause-after=121", "water.circulation.automation.min-rise=0.4",
        "water.circulation.automation.min-rise=11", "water.circulation.automation.no-rise-runs=1",
        "water.circulation.automation.no-rise-runs=11", "water.circulation.automation.quiet-from=",
        "water.circulation.automation.rooms=", "water.circulation.automation.comfort-windows[0].to=",
        "water.circulation.automation.quiet-to=23:30", "water.circulation.automation.comfort-windows[1].to=19:00"})
    void should_not_start_with_a_value_out_of_bounds(final String value) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", value)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AutomationProperties.class)
    static class Properties {
    }
}
