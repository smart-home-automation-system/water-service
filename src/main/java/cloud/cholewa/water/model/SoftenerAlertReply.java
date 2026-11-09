package cloud.cholewa.water.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//GET /home/water/softener/alerts (HAS-399): one alert of the iQua cloud, as the application lists it - at in the time of
//the house; a field the cloud left out is left out
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SoftenerAlertReply(
    String id,
    String type,
    String title,
    String message,
    String level,
    LocalDateTime at,
    Boolean read
) {
}
