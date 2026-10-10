package cloud.cholewa.water.database.model;

import java.time.LocalDateTime;

/**
 * The readings within one bucket of time, as the history query answers them: {@code at} is the
 * start of the bucket, {@code water} and {@code circulation} the averages, rounded to 2 decimals.
 */
public record WaterTemperatureBucket(
    LocalDateTime at,
    Double water,
    Double circulation
) {
}
