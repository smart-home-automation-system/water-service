package cloud.cholewa.water.softener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

//application.yaml as the home profile reads it (HAS-397): the cloud of the softener, its read every five minutes - the
//unit and the bounds of the interval, which decide how often the account calls the cloud - and the account itself,
//without which nothing starts
class SoftenerPropertiesTest {

    private static final String[] ACCOUNT = {"iqua-email=owner@example.com", "iqua-password=s3cr3t"};

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class)
        .withPropertyValues("spring.profiles.active=home")
        .withPropertyValues(ACCOUNT);

    @Test
    void should_read_the_softener_every_five_minutes_from_the_iqua_cloud() {
        contextRunner.run(context -> {
            final SoftenerProperties properties = context.getBean(SoftenerProperties.class);
            assertThat(properties.serialNumber()).isNull();
            assertThat(properties.cloud().baseUrl()).isEqualTo(URI.create("https://api.myiquaapp.com/v1"));
            assertThat(properties.cloud().email()).isEqualTo("owner@example.com");
            assertThat(properties.cloud().interval()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.offlineAfter()).isEqualTo(Duration.ofMinutes(30));
            assertThat(properties.liveFor()).isEqualTo(Duration.ofMinutes(5));
        });
    }

    //without the unit a bare number would bind as milliseconds - a call to the cloud every few milliseconds
    @Test
    void should_read_a_bare_number_as_minutes() {
        contextRunner
            .withPropertyValues("water.softener.cloud.interval=15")
            .run(context -> assertThat(context.getBean(SoftenerProperties.class).cloud().interval())
                .isEqualTo(Duration.ofMinutes(15)));
    }

    //HAS-399: a bare number of the offline alarm is minutes too
    @Test
    void should_read_a_bare_number_of_the_offline_alarm_as_minutes() {
        contextRunner
            .withPropertyValues("water.softener.offline-after=45")
            .run(context -> assertThat(context.getBean(SoftenerProperties.class).offlineAfter())
                .isEqualTo(Duration.ofMinutes(45)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"water.softener.cloud.interval=PT30S", "water.softener.cloud.interval=PT61M",
        "water.softener.cloud.email=", "water.softener.cloud.password=", "water.softener.offline-after=PT4M",
        "water.softener.offline-after=PT25H", "water.softener.live-for=PT30S", "water.softener.live-for=PT11M"})
    void should_not_start_with_a_value_out_of_bounds(final String value) {
        contextRunner
            .withPropertyValues(value)
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void should_never_print_the_password() {
        contextRunner.run(context -> assertThat(context.getBean(SoftenerProperties.class).toString())
            .doesNotContain("s3cr3t"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SoftenerProperties.class)
    static class Properties {
    }
}
