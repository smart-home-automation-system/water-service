package cloud.cholewa.water.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * The circulation pump of the hot water (HAS-404, {@code GET /home/water/circulation}). A field not known is left out -
 * never read as off or 0: the relay and the temperatures come from the newest read of the Shelly Uni (every 15 s, every
 * 5 s while a run goes on), left out when that read is older than a minute.<br>
 * {@code running} - the relay of the pump is on.<br>
 * {@code since} - since when it is on, house time; left out while it is off.<br>
 * {@code trigger}, {@code source} - of the run the service started, while it goes on; left out for a pump switched on by
 * something else (the scene of the Shelly cloud until HAS-405).<br>
 * {@code returnTemperature}, {@code tankTemperature} - degrees C.<br>
 * {@code measuredAt} - the time of that read, house time.<br>
 * {@code lastRun} - the newest run the service started, the one going on included.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CirculationReply(
    Boolean running,
    LocalDateTime since,
    String trigger,
    String source,
    Double returnTemperature,
    Double tankTemperature,
    LocalDateTime measuredAt,
    CirculationRunReply lastRun
) {
}
