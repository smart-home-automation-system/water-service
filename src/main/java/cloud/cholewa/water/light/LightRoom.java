package cloud.cholewa.water.light;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;

//A room as light-service answers it (GET /home/light/rooms/{room}, HAS-405) - only what the automation of the circulation
//reads: anyOn - a light of the room is on (null when not known), occupancy.lastMotionAt - the last motion a sensor of the
//room reported (left out while none since the start of light-service, or for a room without a motion sensor)
@JsonIgnoreProperties(ignoreUnknown = true)
public record LightRoom(String room, Boolean anyOn, Occupancy occupancy) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Occupancy(LocalDateTime lastMotionAt) {
    }

    public LocalDateTime lastMotionAt() {
        return occupancy == null ? null : occupancy.lastMotionAt();
    }
}
