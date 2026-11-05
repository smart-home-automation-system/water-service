package cloud.cholewa.water.alarm;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

//The alarms of the water valve (HAS-319); a bare duration is minutes - without the unit it would bind as milliseconds.
//Pinned by AlarmPropertiesTest.
//pressureLowBelow - kPa, the unit of the valve; below it there is no mains water (100 = 1 bar; 10 - 400)
//pressureLowAfter - for this long in every pass before the alert (PT5M; 1 min - 24 h)
//offlineAfter - the valve not heard, by MQTT or by its RPC, for this long (PT5M; 2 min - 24 h - two reads of a minute)
//frostBelow - degrees C of the water; below it the water is close to freezing (3; 0.5 - 10)
//frostAfter - for this long in every pass before the alert (PT5M; 1 min - 24 h)
//reminderEvery - a reminder while an alarm lasts (PT1H; 10 min - 24 h)
@Validated
@ConfigurationProperties(prefix = "water.alarm")
public record AlarmProperties(
    @NotNull @DecimalMin("10") @DecimalMax("400") Double pressureLowBelow,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 1) @DurationMax(hours = 24) Duration pressureLowAfter,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 2) @DurationMax(hours = 24) Duration offlineAfter,
    @NotNull @DecimalMin("0.5") @DecimalMax("10") Double frostBelow,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 1) @DurationMax(hours = 24) Duration frostAfter,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 10) @DurationMax(hours = 24) Duration reminderEvery
) {
}
