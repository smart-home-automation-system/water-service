package cloud.cholewa.water.database.repository;

import cloud.cholewa.water.database.model.WaterBillEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

//The bills of the waterworks (HAS-318, V4)
public interface WaterBillRepository extends R2dbcRepository<WaterBillEntity, Long> {

    Flux<WaterBillEntity> findAllByOrderByPeriodFromDesc();

    //the id of a bill whose period shares a day with [from, to], other than the bill itself (0 for a new one)
    @Query("""
        SELECT id FROM water_bill
        WHERE period_from <= :to AND period_to >= :from AND id <> :self
        ORDER BY period_from
        LIMIT 1
        """)
    Mono<Long> findOverlapping(LocalDate from, LocalDate to, long self);
}
