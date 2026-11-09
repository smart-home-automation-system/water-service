package cloud.cholewa.water.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The water softener now (HAS-397, {@code GET /home/water/softener}), from the last read of the iQua cloud. A value the
 * cloud left out is left out here too - never a default: missing is never off.<br>
 * {@code reachable} - the last reads of the cloud brought a reading: {@code false} after two missed reads in a row (or at
 * once when the account has no softener); left out until the first read ended.<br>
 * {@code online} - the softener is connected to the cloud, as the cloud says.<br>
 * {@code measuredAt} - the read of the reading, on the clock of the house, without an offset, to the second.<br>
 * {@code stale} - the reading is older than two intervals of the read.<br>
 * {@code water} - litres and l/min; the flow is the value the cloud last refreshed (it moves only while an application
 * keeps a live session - HAS-401).<br>
 * {@code salt} - the level in per cent as the softener counts it down from what was entered on its panel (it is not
 * measured), the days it estimates are left and the day that falls on.<br>
 * {@code regeneration} - days since the last one, its day, and the regenerations since the softener was installed.<br>
 * {@code hardnessGpg} - the hardness set on the softener in grains per gallon; {@code hardnessMgCaco3} the same in mg/l
 * of CaCO3.<br>
 * {@code alerts} - the flags of the softener; {@code leak} is its own watch of a continuous flow.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SoftenerReply(
    Boolean reachable,
    Boolean online,
    LocalDateTime measuredAt,
    Boolean stale,
    Water water,
    Salt salt,
    Regeneration regeneration,
    Double hardnessGpg,
    Double hardnessMgCaco3,
    Integer daysInOperation,
    Integer signalDbm,
    Alerts alerts
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Water(
        Double usedTodayLitres,
        Double averageDailyLitres,
        Double treatedAvailableLitres,
        Double totalLitres,
        Double flowLitresPerMinute
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Salt(Double levelPercent, Integer daysLeft, LocalDate outOfSaltDate) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Regeneration(Integer daysSinceLast, LocalDate lastAt, Integer total) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Alerts(
        Boolean lowSalt,
        Boolean leak,
        Boolean excessiveWaterUse,
        Boolean shutoffValve,
        Boolean resin,
        Boolean error
    ) {
    }
}
