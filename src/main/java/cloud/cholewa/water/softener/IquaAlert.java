package cloud.cholewa.water.softener;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

//One alert of the softener as the iQua cloud keeps it (HAS-399) - AlertGetResponseItem of its OpenAPI, the list the iQua
//application shows. timestamp - an instant (ISO 8601); level - as the cloud words it
@JsonIgnoreProperties(ignoreUnknown = true)
public record IquaAlert(
    String id,
    String type,
    String title,
    String message,
    String level,
    String timestamp,
    @JsonProperty("is_read") Boolean read
) {
}
