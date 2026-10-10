package cloud.cholewa.water.database.repository;

import cloud.cholewa.water.database.model.WaterTemperatureBucket;
import cloud.cholewa.water.database.model.WaterTemperatureEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface WaterTemperatureRepository extends R2dbcRepository<WaterTemperatureEntity, Long> {
    Mono<WaterTemperatureEntity> findFirstByOrderByUpdatedAtDesc();

    //The readings from "from" up to, not including, "to", averaged into buckets of
    //"bucketSeconds", oldest first; a bucket without a reading has no row. The column holds the
    //wall-clock time of the house without a zone, and the epoch of such a value counts from its
    //own midnight - so a bucket that divides a day starts on the clock of the house (00:00,
    //00:05, ...), whatever "from" is: a range that starts inside a bucket gets a first row whose
    //"at" is before it, averaged from the readings within the range. On that clock the hour
    //repeated when the summer time ends falls into the same buckets twice, and the hour skipped
    //when it begins has none. The database does the averaging: a month is some 15 000 rows, read
    //through the index on updated_at (V2) and answered as 372.
    //PostgreSQL only: WaterTemperatureRepositoryTest runs it against a PostgreSQL in Docker.
    @Query("""
        SELECT TIMESTAMP 'epoch'
                   + floor(extract(epoch FROM updated_at) / :bucketSeconds) * :bucketSeconds * INTERVAL '1 second' AS at,
               round(avg(water), 2) AS water,
               round(avg(circulation), 2) AS circulation
        FROM temperature
        WHERE updated_at >= :from
          AND updated_at < :to
        GROUP BY at
        ORDER BY at
        """)
    Flux<WaterTemperatureBucket> findHistory(LocalDateTime from, LocalDateTime to, long bucketSeconds);
}
