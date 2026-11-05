package cloud.cholewa.water.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * The water valve now (HAS-316, {@code GET /home/water/valve}). A value not reported within the staleness of the service
 * is left out - never a default: missing is never off.<br>
 * {@code state} - {@code open} or {@code closed}.<br>
 * {@code flowRate} - l/min (the device reports m³/min).<br>
 * {@code pressure} - bar, two decimals (the device reports kPa).<br>
 * {@code temperature} - the water, °C.<br>
 * {@code counter} - the consumption counter of the valve.<br>
 * {@code measuredAt} - the newest report of what is shown, on the clock of the house, without an offset, to the second.<br>
 * {@code lastCommandConfirmed} - the last command of the valve answered by its report (HAS-321): {@code false} from its
 * sending until the report arrives and when none did within 60 s; left out until a command was sent since the start of
 * the service.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValveReply(
    String state,
    Double flowRate,
    Double pressure,
    Double temperature,
    CounterReply counter,
    LocalDateTime measuredAt,
    Boolean lastCommandConfirmed
) {
}
