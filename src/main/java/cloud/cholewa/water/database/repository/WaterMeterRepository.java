package cloud.cholewa.water.database.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;

//The tables of the water valve (HAS-316, V3). PostgreSQL only - WaterMeterRepositoryTest runs them against a PostgreSQL
//in Docker. A statement each, so a snapshot holds a connection of the pool of 2 for one round trip at a time
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

    private static <T> DatabaseClient.GenericExecuteSpec bindNullable(final DatabaseClient.GenericExecuteSpec spec,
                                                                   final String name, final T value,
                                                                   final Class<T> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }
}
