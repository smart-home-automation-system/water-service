package cloud.cholewa.water.valve;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

//How long the readings of the counter of the water valve are kept (HAS-316) - older rows are deleted every night, never
//the latest. A bare number is days - without the unit it would bind as milliseconds and the first night would delete the
//whole history, hence the lower bound of a year (the statistics of the house compare years); the upper bound of ten years
//keeps a value meant in seconds from keeping everything forever. Pinned by MeterRetentionPropertiesTest
@Validated
@ConfigurationProperties(prefix = "water.meter")
public record MeterRetentionProperties(
    @NotNull @DurationUnit(ChronoUnit.DAYS) @DurationMin(days = 366) @DurationMax(days = 3650) Duration retention
) {
}
