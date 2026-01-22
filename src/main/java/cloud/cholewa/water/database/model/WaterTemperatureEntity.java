package cloud.cholewa.water.database.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("temperature")
public record WaterTemperatureEntity(
    @Id
    Long id,
    LocalDateTime updatedAt,
    double water,
    double circulation
) {
}
