package cloud.cholewa.water.valve;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

//The water valve (HAS-316): a value not reported for staleAfter - by MQTT or by the read of every minute - is left out of
//GET /valve and is not stored. A bare number is minutes - without the unit it would bind as milliseconds and every value
//would be stale at once; at least two reads of a minute, at most an hour. Pinned by ValvePropertiesTest
@Validated
@ConfigurationProperties(prefix = "water.valve")
public record ValveProperties(
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 2) @DurationMax(hours = 1) Duration staleAfter
) {
}
