package cloud.cholewa.water.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A bill of the waterworks with what the house measured over its period (HAS-318) - the contract of the bills view of
 * the dashboard.<br>
 * {@code measuredVolume} - the water of the house from {@code periodFrom} 00:00 to {@code periodTo} 24:00, m3;
 * {@code difference} - measured less billed, m3; {@code differencePercent} - of the billed volume, one decimal. All three
 * are left out (missing is never 0) when a day of the period has neither readings nor an imported value - a period
 * before the history of the house, or a gap of a day in it; the percent also when nothing was billed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BillReply(
    long id,
    LocalDate periodFrom,
    LocalDate periodTo,
    BigDecimal billedVolume,
    BigDecimal meterReading,
    BigDecimal amount,
    String note,
    BigDecimal measuredVolume,
    BigDecimal difference,
    BigDecimal differencePercent,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
