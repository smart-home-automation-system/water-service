package cloud.cholewa.water.database.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

//The water used in one bucket of the consumption (HAS-317): its start in house time and the volume in m3, not rounded
public record ConsumptionBucket(LocalDateTime at, BigDecimal volume) {
}
