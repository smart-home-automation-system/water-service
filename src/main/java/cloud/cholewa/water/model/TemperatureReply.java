package cloud.cholewa.water.model;

import lombok.Builder;

import java.time.LocalDateTime;

/**
 * The last stored reading.<br>
 * {@code measuredAt} - when the sensor was read, on the clock of the house, without an offset. The
 * service answers its last row for as long as the sensor is silent, so this is what tells a
 * current reading from an old one.
 */
@Builder
public record TemperatureReply(
    LocalDateTime measuredAt,
    HotWaterStatus water,
    CirculationStatus circulation
) {
}
