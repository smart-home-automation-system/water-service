package cloud.cholewa.water.database.repository;

import cloud.cholewa.water.database.model.WaterTemperatureEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

public interface WaterTemperatureRepository extends R2dbcRepository<WaterTemperatureEntity, Long> {
}
