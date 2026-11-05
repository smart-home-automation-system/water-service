package cloud.cholewa.water.database.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

//A row of water_alert (V5, HAS-319): one episode of an alarm of the water valve. lastToldAt null while its alert has not
//got through; closedAt null while it lasts
@Table("water_alert")
public record WaterAlertEntity(
    @Id Long id,
    String kind,
    LocalDateTime openedAt,
    LocalDateTime lastToldAt,
    LocalDateTime closedAt
) {

    public WaterAlertEntity told(final LocalDateTime at) {
        return new WaterAlertEntity(id, kind, openedAt, at, closedAt);
    }

    public WaterAlertEntity closed(final LocalDateTime at) {
        return new WaterAlertEntity(id, kind, openedAt, lastToldAt, at);
    }

    public WaterAlertEntity withId(final Long value) {
        return new WaterAlertEntity(value, kind, openedAt, lastToldAt, closedAt);
    }
}
