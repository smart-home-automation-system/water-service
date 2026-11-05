package cloud.cholewa.water.database.repository;

import cloud.cholewa.water.database.model.ConsumptionBucket;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;

//The tables of the water valve (HAS-316, V3) and the consumption read from them (HAS-317). PostgreSQL only -
//WaterMeterRepositoryTest runs them against a PostgreSQL in Docker. A statement each, so a snapshot or a read holds a
//connection of the pool of 2 for one round trip at a time
@Repository
@RequiredArgsConstructor
public class WaterMeterRepository {

    //A row of water_meter_reading only when the counter changed: the latest row moves its last_checked_at while the total
    //is the same, a new one is inserted otherwise - restart-safe, the comparison is with the table, not with the memory.
    //The latest is the highest id, never the latest measured_at: a house time without a zone goes back an hour in October
    private static final String RECORD_COUNTER = """
        WITH latest AS (
            SELECT id, total_m3 FROM water_meter_reading ORDER BY id DESC LIMIT 1
        ), checked AS (
            UPDATE water_meter_reading reading SET last_checked_at = :at
            FROM latest WHERE reading.id = latest.id AND latest.total_m3 = :total
            RETURNING reading.id
        )
        INSERT INTO water_meter_reading (total_m3, measured_at, last_checked_at)
        SELECT :total, :at, :at WHERE NOT EXISTS (SELECT 1 FROM checked)
        """;

    //The one current row of water_valve_status: its values and measured_at move only when a value changed, last_checked_at
    //with every snapshot
    private static final String RECORD_STATUS = """
        INSERT INTO water_valve_status (id, open, flow_rate, pressure, temperature, measured_at, last_checked_at)
        VALUES (1, :open, :flowRate, :pressure, :temperature, :at, :at)
        ON CONFLICT (id) DO UPDATE SET
            open = EXCLUDED.open,
            flow_rate = EXCLUDED.flow_rate,
            pressure = EXCLUDED.pressure,
            temperature = EXCLUDED.temperature,
            measured_at = CASE
                WHEN (water_valve_status.open, water_valve_status.flow_rate, water_valve_status.pressure,
                      water_valve_status.temperature)
                     IS DISTINCT FROM (EXCLUDED.open, EXCLUDED.flow_rate, EXCLUDED.pressure, EXCLUDED.temperature)
                THEN EXCLUDED.measured_at
                ELSE water_valve_status.measured_at
            END,
            last_checked_at = EXCLUDED.last_checked_at
        """;

    //the readings measured before a time, never the latest row - the counter as it is now
    private static final String DELETE_BEFORE = """
        DELETE FROM water_meter_reading
        WHERE measured_at < :before
          AND id <> (SELECT max(id) FROM water_meter_reading)
        """;

    //The consumption of a range in buckets of one unit (HAS-317), one statement through the index on measured_at. A row
    //of the counter is the moment its total was first seen, so the water of a row is its total less the total of the row
    //before (by id - the order the snapshot wrote them), counted in the bucket its measured_at falls in; the row before
    //the range is read too, or the first change of the range would have nothing to compare with - found by the index on
    //measured_at (by id it would walk the whole key back for a range in the past). A delta below zero is a reset of the
    //counter (Service.ResetCounters, a replaced device) and counts as nothing - a new series, never a negative volume.
    //A bucket has a point only when the counter was watched in it: a row was measured in it, or the row live at its start
    //was still checked after it began - so an hour without water is a 0, an hour the service was down has no point. The
    //deltas are summed once by date_trunc and the watch is one index read per bucket: a year of 438 000 rows (600 changes
    //a day) answered in 0.15 s on a local PostgreSQL, where a join of every bucket with every row took 0.6 s
    private static final String CONSUMPTION = """
        WITH series AS (
            SELECT bucket_start
            FROM generate_series(CAST(:from AS timestamp), CAST(:to AS timestamp) - CAST('1 ' || :unit AS interval),
                                 CAST('1 ' || :unit AS interval)) AS bucket_start
        ), readings AS (
            SELECT id, total_m3, measured_at FROM water_meter_reading
            WHERE measured_at >= :from AND measured_at < :to
            UNION ALL
            (SELECT id, total_m3, measured_at FROM water_meter_reading
             WHERE measured_at < :from ORDER BY measured_at DESC, id DESC LIMIT 1)
        ), deltas AS (
            SELECT measured_at, GREATEST(total_m3 - LAG(total_m3) OVER (ORDER BY id), 0) AS delta
            FROM readings
        ), volumes AS (
            SELECT date_trunc(:unit, measured_at) AS bucket_start, SUM(delta) AS volume
            FROM deltas
            WHERE measured_at >= :from
            GROUP BY 1
        )
        SELECT series.bucket_start AS at, COALESCE(volumes.volume, 0) AS volume
        FROM series
        LEFT JOIN volumes ON volumes.bucket_start = series.bucket_start
        WHERE volumes.bucket_start IS NOT NULL
           OR (SELECT live.last_checked_at FROM water_meter_reading live
               WHERE live.measured_at < series.bucket_start
               ORDER BY live.measured_at DESC, live.id DESC LIMIT 1) >= series.bucket_start
        ORDER BY series.bucket_start
        """;

    private final DatabaseClient databaseClient;

    //the total of the counter (m3) as reported at a time; answers whether a row was inserted - false when the latest row
    //had the same total and only its last_checked_at moved
    public Mono<Boolean> recordCounter(final BigDecimal totalM3, final LocalDateTime at) {
        return databaseClient.sql(RECORD_COUNTER)
            .bind("total", totalM3)
            .bind("at", at)
            .fetch()
            .rowsUpdated()
            .map(inserted -> inserted > 0);
    }

    //the valve as reported at a time, in the units of the device; a value not known is NULL
    public Mono<Void> recordStatus(final Boolean open, final Double flowRate, final Double pressure,
                                   final Double temperature, final LocalDateTime at) {
        return bindNullable(bindNullable(bindNullable(bindNullable(databaseClient.sql(RECORD_STATUS),
            "open", open, Boolean.class), "flowRate", flowRate, Double.class), "pressure", pressure, Double.class),
            "temperature", temperature, Double.class)
            .bind("at", at)
            .fetch()
            .rowsUpdated()
            .then();
    }

    //the number of readings deleted
    public Mono<Long> deleteReadingsBefore(final LocalDateTime before) {
        return databaseClient.sql(DELETE_BEFORE)
            .bind("before", before)
            .fetch()
            .rowsUpdated();
    }

    //the buckets of [from, to) that have a point, oldest first; unit is a field of date_trunc ("hour", "day", "month")
    //that divides the range
    public Flux<ConsumptionBucket> consumption(final LocalDateTime from, final LocalDateTime to, final String unit) {
        return databaseClient.sql(CONSUMPTION)
            .bind("from", from)
            .bind("to", to)
            .bind("unit", unit)
            .map(row -> new ConsumptionBucket(row.get("at", LocalDateTime.class), row.get("volume", BigDecimal.class)))
            .all();
    }

    private static <T> DatabaseClient.GenericExecuteSpec bindNullable(final DatabaseClient.GenericExecuteSpec spec,
                                                                   final String name, final T value,
                                                                   final Class<T> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }
}
