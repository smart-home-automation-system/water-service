package cloud.cholewa.water.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code GET /home/water/softener/history} (HAS-398): one metric of the softener over a range, in buckets of one width -
 * the shape of the weather and the hot-water histories.<br>
 * {@code metric} - the name of the metric as it was asked for.<br>
 * {@code from}, {@code to} - the range as it was asked for; it includes its start and not its end.<br>
 * {@code bucketSeconds} - the width of a bucket, chosen from the length of the range (5 min up to 2 days, 30 min up to 8,
 * 2 h up to 31, a day up to 366).<br>
 * {@code points} - one per bucket with a stored reading, oldest first. A reading is stored on a change only, so a bucket
 * without a change has no point. Every date-time is wall-clock time of the house.
 */
public record SoftenerHistoryReply(
    String metric,
    LocalDateTime from,
    LocalDateTime to,
    long bucketSeconds,
    List<Point> points
) {

    /**
     * {@code at} - the start of the bucket, aligned to the clock of the house.<br>
     * {@code avg}, {@code min}, {@code max} - over the stored readings of the bucket, the average rounded to 2 decimals.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Point(LocalDateTime at, BigDecimal avg, BigDecimal min, BigDecimal max) {
    }
}
