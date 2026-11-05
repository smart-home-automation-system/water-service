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

//The flow alarms of the water valve (HAS-320, defaults accepted by the owner 2026-10-23); a bare duration is minutes -
//without the unit it would bind as milliseconds. Flows in l/min (the valve measures up to 75). Pinned by
//FlowPropertiesTest.
//highAbove - a flow above it is a high flow (25; 1 - 75)
//highAfter - for this long before the alert (PT3M; 1 min - 24 h)
//continuousPause - a flow that stops for longer has stopped; a shorter pause is the same flow (PT1M; 1 - 30 min - not
//  below the minute of the pass: a flow that stopped between two passes is still seen by the second)
//continuousAfter - a flow above zero for this long is a continuous flow (PT30M; 5 min - 24 h)
//emptyHouseAbove - a flow above it while nobody is at home - against the noise of the sensor (0.5; 0.1 - 10)
//emptyHouseAfter - the house empty for this long before such a flow is an alarm (PT2M; 1 min - 24 h)
//closeOnAlarm - the continuous flow and the empty house close the valve at their alert (false - the choice of the owner,
//  HAS-321); the high flow never does - a bath
@Validated
@ConfigurationProperties(prefix = "water.flow")
public record FlowProperties(
    @NotNull @DecimalMin("1") @DecimalMax("75") Double highAbove,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 1) @DurationMax(hours = 24) Duration highAfter,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 1) @DurationMax(minutes = 30) Duration continuousPause,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 5) @DurationMax(hours = 24) Duration continuousAfter,
    @NotNull @DecimalMin("0.1") @DecimalMax("10") Double emptyHouseAbove,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 1) @DurationMax(hours = 24) Duration emptyHouseAfter,
    @NotNull Boolean closeOnAlarm
) {
}
