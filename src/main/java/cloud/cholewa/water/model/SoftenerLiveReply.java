package cloud.cholewa.water.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//PUT /home/water/softener/live (HAS-401): the live stream is open until liveUntil, on the clock of the house
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SoftenerLiveReply(Boolean live, LocalDateTime liveUntil) {
}
