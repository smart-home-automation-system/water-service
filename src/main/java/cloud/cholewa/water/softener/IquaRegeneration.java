package cloud.cholewa.water.softener;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

//A regeneration event of the iQua cloud as it answers it (HAS-398) - RegenerationEventObject of its OpenAPI. The times are
//text as the cloud gives them (date-times, each may be null): endTime null while the regeneration goes on. Kept apart from
//the rows of softener_regeneration
@JsonIgnoreProperties(ignoreUnknown = true)
public record IquaRegeneration(
    String id,
    @JsonProperty("start_time") String startTime,
    @JsonProperty("end_time") String endTime,
    @JsonProperty("device_start_time") String deviceStartTime
) {
}
