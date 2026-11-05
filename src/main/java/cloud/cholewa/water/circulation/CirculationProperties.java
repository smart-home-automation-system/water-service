package cloud.cholewa.water.circulation;

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

//The circulation pump of the hot water (HAS-404), relay 1 of the Shelly Uni; temperatures in degrees C, a bare duration is
//minutes - without the unit it would bind as milliseconds. Pinned by CirculationPropertiesTest.
//returnTarget - a run stops once the return reaches it (35; 20 - 60)
//maxRun - a run stops after it at the latest; also the timer sent with the start, so the relay flips back by itself
//  should the service not stop it (PT3M; 30 s - 10 min)
//tankMin - a start is refused below it: the pump would push cold water round (38; 20 - 80)
//stopOnReturn - whether the return stops a run (true); false leaves the limit alone - for a sensor that turns out not
//  to be on the return (decided at the first real start with the owner)
@Validated
@ConfigurationProperties(prefix = "water.circulation")
public record CirculationProperties(
    @NotNull @DecimalMin("20") @DecimalMax("60") Double returnTarget,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(seconds = 30) @DurationMax(minutes = 10) Duration maxRun,
    @NotNull @DecimalMin("20") @DecimalMax("80") Double tankMin,
    @NotNull Boolean stopOnReturn
) {
}
