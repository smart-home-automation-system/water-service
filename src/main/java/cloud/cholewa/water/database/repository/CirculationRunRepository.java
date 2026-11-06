package cloud.cholewa.water.database.repository;

import cloud.cholewa.water.database.model.CirculationRunEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;

//The runs of the circulation pump (HAS-404, V8)
public interface CirculationRunRepository extends ReactiveCrudRepository<CirculationRunEntity, Long> {

    //the end of a run; a run ended already keeps its first end
    @Modifying
    @Query("""
        UPDATE circulation_run SET ended_at = :endedAt, stopped_by = :stoppedBy, return_at_end = :returnAtEnd
        WHERE id = :id AND ended_at IS NULL""")
    Mono<Integer> end(Long id, LocalDateTime endedAt, String stoppedBy, BigDecimal returnAtEnd);

    //the runs of an earlier pod that never ended, but the one this pod holds (keep): ended as failure at the end of their
    //timer (HAS-404)
    @Modifying
    @Query("""
        UPDATE circulation_run SET ended_at = started_at + make_interval(secs => :maxRunSeconds), stopped_by = 'failure'
        WHERE ended_at IS NULL AND started_at <> :keep""")
    Mono<Integer> endOrphans(LocalDateTime keep, long maxRunSeconds);

    //the runs started in [from, to), newest first - by the index on started_at
    @Query("""
        SELECT * FROM circulation_run WHERE started_at >= :from AND started_at < :to
        ORDER BY started_at DESC, id DESC""")
    Flux<CirculationRunEntity> findStartedIn(LocalDateTime from, LocalDateTime to);

    //the newest run - by id, never started_at: a house time goes back an hour in October
    Mono<CirculationRunEntity> findFirstByOrderByIdDesc();

    //the end of the newest run stopped by the temperature or the limit - the pause of the automation (HAS-405); by id,
    //for the same reason
    @Query("""
        SELECT ended_at FROM circulation_run WHERE stopped_by IN ('temperature', 'limit') AND ended_at IS NOT NULL
        ORDER BY id DESC LIMIT 1""")
    Mono<LocalDateTime> findLastSuccessfulEnd();

    @Modifying
    @Query("DELETE FROM circulation_run WHERE started_at < :before")
    Mono<Integer> deleteStartedBefore(LocalDateTime before);
}
