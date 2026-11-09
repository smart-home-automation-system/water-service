package cloud.cholewa.water.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One day of {@code GET /home/water/softener/days} (HAS-398): the litres that went through the softener on a day of the
 * house, from the deltas of its counter. A day the softener was not read on has no entry - missing is never 0. Beside the
 * day of the water meter ({@code GET /home/water/consumption}) the difference is the water that did not go through the
 * softener (the bypass, the garden).
 */
public record SoftenerDayReply(LocalDate day, BigDecimal litres) {
}
