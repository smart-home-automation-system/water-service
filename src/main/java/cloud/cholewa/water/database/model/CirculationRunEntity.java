package cloud.cholewa.water.database.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

//A row of circulation_run (V8, HAS-404): a run of the circulation pump the service started; endedAt and stoppedBy null
//while it goes on, a temperature null when it was not read
@Table("circulation_run")
public record CirculationRunEntity(
    @Id Long id,
    LocalDateTime startedAt,
    LocalDateTime endedAt,
    String trigger,
    String source,
    String stoppedBy,
    BigDecimal returnAtStart,
    BigDecimal returnAtEnd
) {
}
