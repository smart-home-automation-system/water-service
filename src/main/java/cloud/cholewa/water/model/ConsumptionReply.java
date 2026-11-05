package cloud.cholewa.water.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The water used over a range, in buckets (HAS-317) - the contract of the water page of the dashboard.<br>
 * {@code range} - day, week, month or year; {@code from}, {@code to} - the range in house time, its start included and
 * its end not.<br>
 * {@code total} - the water of the range, {@code previousTotal} - of the same range one period earlier; each left out
 * when the counter was not watched in its range (missing is never 0).<br>
 * {@code buckets} - one per bucket in which the counter was watched, oldest first: a day in hours, a week and a month in
 * days, a year in months. A bucket without a point is a gap in the readings, a bucket without water has 0.<br>
 * Volumes in m3 with 3 decimals. Every date-time is wall-clock time of the house: the hour skipped in spring is a bucket
 * of 0 (the counter was watched across it), the hour repeated in autumn is one bucket with the water of both.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsumptionReply(
    String range,
    LocalDateTime from,
    LocalDateTime to,
    String unit,
    Double total,
    Double previousTotal,
    List<Bucket> buckets
) {

    public record Bucket(LocalDateTime at, double volume) {
    }
}
