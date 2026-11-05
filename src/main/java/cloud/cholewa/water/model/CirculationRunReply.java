package cloud.cholewa.water.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A run of the circulation pump the service started (HAS-404) - the answer of {@code POST /home/water/circulation}
 * (202), an entry of {@code GET /home/water/circulation/runs} and {@code lastRun} of {@code GET /home/water/circulation}.
 * A field not known is left out.<br>
 * {@code startedAt}, {@code endedAt} - house time without an offset, to the second; {@code endedAt} left out while the
 * run goes on.<br>
 * {@code trigger} - {@code manual} (and {@code motion}, {@code light}, {@code window} with the automation).<br>
 * {@code source} - who asked.<br>
 * {@code stoppedBy} - {@code temperature}, {@code limit}, {@code device} or {@code failure}; left out while it goes on.<br>
 * {@code returnAtStart}, {@code returnAtEnd} - the return in degrees C.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CirculationRunReply(
    LocalDateTime startedAt,
    LocalDateTime endedAt,
    String trigger,
    String source,
    String stoppedBy,
    BigDecimal returnAtStart,
    BigDecimal returnAtEnd
) {
}
