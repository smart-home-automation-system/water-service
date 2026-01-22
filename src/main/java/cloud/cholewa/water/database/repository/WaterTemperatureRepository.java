package cloud.cholewa.water.database.repository;

import cloud.cholewa.water.database.model.WaterTemperatureEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;

public interface WaterTemperatureRepository extends R2dbcRepository<WaterTemperatureEntity, Long> {
    Mono<WaterTemperatureEntity> findFirstByOrderByUpdatedAtDesc();
}
