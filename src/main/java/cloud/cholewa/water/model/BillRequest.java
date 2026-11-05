package cloud.cholewa.water.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A bill of the waterworks as the owner enters it (HAS-318) - the whole bill, for a POST and a PUT alike (no partial
 * update, the rule of the registries).<br>
 * {@code periodFrom}, {@code periodTo} - the period billed, whole days, both included; {@code billedVolume} - m3, 3
 * decimals; all three required.<br>
 * {@code meterReading} - the reading of the meter of the waterworks, m3; {@code amount} - PLN, 2 decimals;
 * {@code note} - at most 500 characters; each optional.
 */
public record BillRequest(
    LocalDate periodFrom,
    LocalDate periodTo,
    BigDecimal billedVolume,
    BigDecimal meterReading,
    BigDecimal amount,
    String note
) {
}
