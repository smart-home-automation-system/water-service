package cloud.cholewa.water.database.repository;

import cloud.cholewa.water.database.model.WaterValveCommandEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

//The commands of the water valve (HAS-321, V7)
public interface WaterValveCommandRepository extends ReactiveCrudRepository<WaterValveCommandEntity, Long> {

    //the report that answered the command; a command confirmed already keeps its first confirmation
    @Modifying
    @Query("UPDATE water_valve_command SET confirmed_at = :at WHERE id = :id AND confirmed_at IS NULL")
    Mono<Integer> confirm(Long id, LocalDateTime at);
}
