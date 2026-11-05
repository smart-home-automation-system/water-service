package cloud.cholewa.water.model;

import java.time.LocalDateTime;

/**
 * The consumption counter of the water valve (HAS-316).<br>
 * {@code total} - m³ since the counter was last zeroed, three decimals.<br>
 * {@code measuredAt} - when it was last reported, on the clock of the house, without an offset, to the second.
 */
public record CounterReply(Double total, LocalDateTime measuredAt) {
}
