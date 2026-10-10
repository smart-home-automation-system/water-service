package cloud.cholewa.water.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The temperatures of the hot water and the circulation over a range, averaged into buckets of
 * one width.<br>
 * {@code from}, {@code to} - the range as it was asked for; it includes its start and not its end.<br>
 * {@code bucketSeconds} - the width of a bucket, chosen by the service from the length of the range.<br>
 * {@code points} - one per bucket that has a reading, oldest first. A bucket without a reading has
 * no point, so two points further apart than {@code bucketSeconds} are a gap in the readings.<br>
 * Every date-time is wall-clock time of the house, as the readings are stored. So on the night the
 * summer time begins the history has a gap of an hour that no sensor caused, and on the night it
 * ends the readings of the hour that happens twice are averaged into the same buckets.
 */
public record TemperatureHistoryReply(
    LocalDateTime from,
    LocalDateTime to,
    long bucketSeconds,
    List<Point> points
) {

    /**
     * {@code at} - the start of the bucket, aligned to the clock of the house, not to the range:
     * when {@code from} lies inside a bucket, the first point starts before it.<br>
     * {@code water}, {@code circulation} - the averages of the readings of the bucket that lie
     * within the range, in °C, rounded to 2 decimals. A reading is one row with both, so a point
     * always carries both.
     */
    public record Point(LocalDateTime at, double water, double circulation) {
    }
}
