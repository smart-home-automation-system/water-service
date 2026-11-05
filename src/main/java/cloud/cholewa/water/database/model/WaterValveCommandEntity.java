package cloud.cholewa.water.database.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

//A row of water_valve_command (V7, HAS-321): a command the valve took - open or close - by its source, confirmedAt null
//until the report of the valve in that state answered it
@Table("water_valve_command")
public record WaterValveCommandEntity(
    @Id Long id,
    LocalDateTime commandedAt,
    String state,
    String source,
    LocalDateTime confirmedAt
) {
}
