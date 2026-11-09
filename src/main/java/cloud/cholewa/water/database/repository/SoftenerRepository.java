package cloud.cholewa.water.database.repository;

import cloud.cholewa.water.model.SoftenerHistoryReply;
import cloud.cholewa.water.softener.SoftenerMetric;
import cloud.cholewa.water.softener.SoftenerReading;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

//The tables of the water softener (HAS-398, V9): its readings and its regenerations. PostgreSQL only -
//SoftenerRepositoryTest runs them against a PostgreSQL in Docker. A statement each, so a write or a read holds a connection
//of the pool of 2 for one round trip at a time
@Repository
@RequiredArgsConstructor
public class SoftenerRepository {

    //A row of softener_reading only when a value changed: the latest row moves its last_checked_at while every value is
    //the same, a new one is inserted otherwise - restart-safe, the comparison is with the table, not with the memory. The
    //latest is the highest id, never the latest measured_at (a house time goes back an hour in October). The values are
    //cast to the types of the columns, so that a NULL compares as NULL and a 102.0 as the 102.0 stored
    private static final String RECORD_READING = """
        WITH latest AS (
            SELECT * FROM softener_reading ORDER BY id DESC LIMIT 1
        ), incoming AS (
            SELECT CAST(:total AS NUMERIC(12, 1)) AS total_litres,
                   CAST(:usedToday AS NUMERIC(9, 1)) AS used_today_litres,
                   CAST(:treated AS NUMERIC(9, 1)) AS treated_available_litres,
                   CAST(:flow AS NUMERIC(6, 1)) AS flow_litres_per_minute,
                   CAST(:salt AS NUMERIC(4, 1)) AS salt_level_percent,
                   CAST(:saltDays AS INTEGER) AS salt_days_left,
                   CAST(:hardness AS NUMERIC(5, 1)) AS hardness_gpg,
                   CAST(:regenerations AS INTEGER) AS total_regenerations,
                   CAST(:lowSalt AS BOOLEAN) AS low_salt,
                   CAST(:leak AS BOOLEAN) AS leak,
                   CAST(:excessive AS BOOLEAN) AS excessive_water_use,
                   CAST(:shutoff AS BOOLEAN) AS shutoff_valve,
                   CAST(:resin AS BOOLEAN) AS resin,
                   CAST(:error AS BOOLEAN) AS error
        ), checked AS (
            UPDATE softener_reading reading SET last_checked_at = :at
            FROM latest, incoming
            WHERE reading.id = latest.id
              AND (latest.total_litres, latest.used_today_litres, latest.treated_available_litres,
                   latest.flow_litres_per_minute, latest.salt_level_percent, latest.salt_days_left, latest.hardness_gpg,
                   latest.total_regenerations, latest.low_salt, latest.leak, latest.excessive_water_use,
                   latest.shutoff_valve, latest.resin, latest.error)
                  IS NOT DISTINCT FROM
                  (incoming.total_litres, incoming.used_today_litres, incoming.treated_available_litres,
                   incoming.flow_litres_per_minute, incoming.salt_level_percent, incoming.salt_days_left,
                   incoming.hardness_gpg, incoming.total_regenerations, incoming.low_salt, incoming.leak,
                   incoming.excessive_water_use, incoming.shutoff_valve, incoming.resin, incoming.error)
            RETURNING reading.id
        )
        INSERT INTO softener_reading (total_litres, used_today_litres, treated_available_litres, flow_litres_per_minute,
                                      salt_level_percent, salt_days_left, hardness_gpg, total_regenerations, low_salt,
                                      leak, excessive_water_use, shutoff_valve, resin, error, measured_at,
                                      last_checked_at)
        SELECT total_litres, used_today_litres, treated_available_litres, flow_litres_per_minute, salt_level_percent,
               salt_days_left, hardness_gpg, total_regenerations, low_salt, leak, excessive_water_use, shutoff_valve,
               resin, error, :at, :at
        FROM incoming
        WHERE NOT EXISTS (SELECT 1 FROM checked)
        """;

    //One metric of softener_reading from "from" up to, not including, "to", in buckets of "bucketSeconds", oldest first -
    //the query of the weather and the hot-water histories. measured_at is the wall-clock time of the house without a zone,
    //and the epoch of such a value counts from its own midnight, so a bucket that divides a day starts on the clock of the
    //house. A row is stored on a change only: a bucket without a change has no point, the dashboard draws through a short
    //gap. The column comes from SoftenerMetric only, never from the request
    private static final String HISTORY = """
        SELECT TIMESTAMP 'epoch'
                   + floor(extract(epoch FROM measured_at) / :bucketSeconds) * :bucketSeconds * INTERVAL '1 second' AS at,
               round(avg(%1$s), 2) AS avg,
               min(%1$s) AS min,
               max(%1$s) AS max
        FROM softener_reading
        WHERE measured_at >= :from
          AND measured_at < :to
          AND %1$s IS NOT NULL
        GROUP BY at
        ORDER BY at
        """;

    //The litres through the softener per day of [from, to), from the deltas of total_litres - the rule of the consumption
    //of the water meter (WaterMeterRepository.CONSUMPTION, HAS-317): a row is the moment its total was first seen, its
    //water the total less the row before by id, counted in the day of its measured_at; the row before the range is read
    //too. A delta below zero is a reset of the counter and counts as nothing. A day has a point only when the softener was
    //watched in it: a row measured in it, or the row live at its start still checked after the day began
    private static final String DAYS = """
        WITH series AS (
            SELECT CAST(day AS date) AS day
            FROM generate_series(CAST(:from AS timestamp), CAST(:to AS timestamp) - INTERVAL '1 day',
                                 INTERVAL '1 day') AS day
        ), readings AS (
            SELECT id, total_litres, measured_at FROM softener_reading
            WHERE total_litres IS NOT NULL AND measured_at >= :from AND measured_at < :to
            UNION ALL
            (SELECT id, total_litres, measured_at FROM softener_reading
             WHERE total_litres IS NOT NULL AND measured_at < :from ORDER BY measured_at DESC, id DESC LIMIT 1)
        ), deltas AS (
            SELECT measured_at, GREATEST(total_litres - LAG(total_litres) OVER (ORDER BY id), 0) AS delta
            FROM readings
        ), volumes AS (
            SELECT CAST(measured_at AS date) AS day, SUM(delta) AS litres
            FROM deltas
            WHERE measured_at >= :from
            GROUP BY 1
        )
        SELECT series.day, COALESCE(volumes.litres, 0) AS litres
        FROM series
        LEFT JOIN volumes ON volumes.day = series.day
        WHERE volumes.day IS NOT NULL
           OR (SELECT live.last_checked_at FROM softener_reading live
               WHERE live.measured_at < series.day
               ORDER BY live.measured_at DESC, live.id DESC LIMIT 1) >= series.day
        ORDER BY series.day
        """;

    //the readings measured before a time, never the latest row - the softener as it is now
    private static final String DELETE_READINGS_BEFORE = """
        DELETE FROM softener_reading
        WHERE measured_at < :before
          AND id <> (SELECT max(id) FROM softener_reading)
        """;

    //which of these regenerations of the cloud are stored, and whether each has ended
    private static final String KNOWN_REGENERATIONS = """
        SELECT cloud_id, ended_at IS NOT NULL AS ended FROM softener_regeneration WHERE cloud_id = ANY(:ids)
        """;

    //A regeneration of the cloud: a new one inserted, a stored one that goes on completed once the cloud has its end -
    //an end once stored never changes. Answers 1 when a row was inserted or completed, 0 when nothing changed
    private static final String RECORD_REGENERATION = """
        INSERT INTO softener_regeneration (cloud_id, started_at, ended_at, device_started_at)
        VALUES (:cloudId, :startedAt, :endedAt, :deviceStartedAt)
        ON CONFLICT (cloud_id) DO UPDATE SET ended_at = EXCLUDED.ended_at
        WHERE softener_regeneration.ended_at IS NULL AND EXCLUDED.ended_at IS NOT NULL
        """;

    private static final String REGENERATIONS = """
        SELECT started_at, ended_at, device_started_at FROM softener_regeneration
        WHERE started_at >= :from AND started_at < :to
        ORDER BY started_at DESC, id DESC
        """;

    private static final String DELETE_REGENERATIONS_BEFORE = """
        DELETE FROM softener_regeneration WHERE started_at < :before
        """;

    private final DatabaseClient databaseClient;

    //the values of a reading of a time; answers whether a row was inserted - false when the latest row had the same
    //values and only its last_checked_at moved
    public Mono<Boolean> recordReading(final SoftenerReading reading, final LocalDateTime at) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(RECORD_READING).bind("at", at);
        spec = bind(spec, "total", decimal(reading.totalLitres()), BigDecimal.class);
        spec = bind(spec, "usedToday", decimal(reading.usedTodayLitres()), BigDecimal.class);
        spec = bind(spec, "treated", decimal(reading.treatedAvailableLitres()), BigDecimal.class);
        spec = bind(spec, "flow", decimal(reading.flowLitresPerMinute()), BigDecimal.class);
        spec = bind(spec, "salt", decimal(reading.saltLevelPercent()), BigDecimal.class);
        spec = bind(spec, "saltDays", reading.saltDaysLeft(), Integer.class);
        spec = bind(spec, "hardness", decimal(reading.hardnessGpg()), BigDecimal.class);
        spec = bind(spec, "regenerations", reading.totalRegenerations(), Integer.class);
        final SoftenerReading.Alerts alerts = reading.alerts();
        spec = bind(spec, "lowSalt", alerts.lowSalt(), Boolean.class);
        spec = bind(spec, "leak", alerts.leak(), Boolean.class);
        spec = bind(spec, "excessive", alerts.excessiveWaterUse(), Boolean.class);
        spec = bind(spec, "shutoff", alerts.shutoffValve(), Boolean.class);
        spec = bind(spec, "resin", alerts.resin(), Boolean.class);
        spec = bind(spec, "error", alerts.error(), Boolean.class);
        return spec.fetch().rowsUpdated().map(inserted -> inserted > 0);
    }

    //the buckets of [from, to) that have a reading of the metric, oldest first
    public Flux<SoftenerHistoryReply.Point> history(final SoftenerMetric metric, final LocalDateTime from,
                                                    final LocalDateTime to, final long bucketSeconds) {
        return databaseClient.sql(HISTORY.formatted(metric.column()))
            .bind("bucketSeconds", bucketSeconds)
            .bind("from", from)
            .bind("to", to)
            .map(row -> new SoftenerHistoryReply.Point(row.get("at", LocalDateTime.class), row.get("avg", BigDecimal.class),
                row.get("min", BigDecimal.class), row.get("max", BigDecimal.class)))
            .all();
    }

    //the days of [from, to) the softener was watched in, with the litres through it, oldest first
    public Flux<SoftenerDay> days(final LocalDate from, final LocalDate to) {
        return databaseClient.sql(DAYS)
            .bind("from", from.atStartOfDay())
            .bind("to", to.atStartOfDay())
            .map(row -> new SoftenerDay(row.get("day", LocalDate.class), row.get("litres", BigDecimal.class)))
            .all();
    }

    public record SoftenerDay(LocalDate day, BigDecimal litres) {
    }

    public Mono<Long> deleteReadingsBefore(final LocalDateTime before) {
        return databaseClient.sql(DELETE_READINGS_BEFORE).bind("before", before).fetch().rowsUpdated();
    }

    //by the id of the cloud: true for a regeneration that has ended, false for one that goes on; an id not stored is not
    //in the map
    public Mono<Map<String, Boolean>> knownRegenerations(final Collection<String> cloudIds) {
        return databaseClient.sql(KNOWN_REGENERATIONS)
            .bind("ids", cloudIds.toArray(String[]::new))
            .map(row -> Map.entry(row.get("cloud_id", String.class), Boolean.TRUE.equals(row.get("ended", Boolean.class))))
            .all()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    //whether a row was inserted or completed
    public Mono<Boolean> recordRegeneration(final String cloudId, final LocalDateTime startedAt, final LocalDateTime endedAt,
                                            final LocalDateTime deviceStartedAt) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(RECORD_REGENERATION)
            .bind("cloudId", cloudId)
            .bind("startedAt", startedAt);
        spec = bind(spec, "endedAt", endedAt, LocalDateTime.class);
        spec = bind(spec, "deviceStartedAt", deviceStartedAt, LocalDateTime.class);
        return spec.fetch().rowsUpdated().map(changed -> changed > 0);
    }

    //the regenerations started within [from, to), newest first
    public Flux<StoredRegeneration> regenerations(final LocalDateTime from, final LocalDateTime to) {
        return databaseClient.sql(REGENERATIONS)
            .bind("from", from)
            .bind("to", to)
            .map(row -> new StoredRegeneration(row.get("started_at", LocalDateTime.class),
                row.get("ended_at", LocalDateTime.class), row.get("device_started_at", LocalDateTime.class)))
            .all();
    }

    public record StoredRegeneration(LocalDateTime startedAt, LocalDateTime endedAt, LocalDateTime deviceStartedAt) {
    }

    public Mono<Long> deleteRegenerationsBefore(final LocalDateTime before) {
        return databaseClient.sql(DELETE_REGENERATIONS_BEFORE).bind("before", before).fetch().rowsUpdated();
    }

    private static BigDecimal decimal(final Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static <T> DatabaseClient.GenericExecuteSpec bind(final DatabaseClient.GenericExecuteSpec spec,
                                                           final String name, final T value, final Class<T> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }
}
