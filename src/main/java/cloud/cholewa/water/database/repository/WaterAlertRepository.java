package cloud.cholewa.water.database.repository;

import cloud.cholewa.water.database.model.WaterAlertEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

//The episodes of the alarms of the water valve (HAS-319, V5)
public interface WaterAlertRepository extends ReactiveCrudRepository<WaterAlertEntity, Long> {

    //the episodes that last - at most one a kind (the partial unique index)
    Flux<WaterAlertEntity> findByClosedAtIsNull();
}
