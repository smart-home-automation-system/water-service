package cloud.cholewa.water.softener;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import java.util.Map;

//A device of the iQua cloud as it answers it (HAS-397) - DeviceObject of its OpenAPI, the fields the service reads: the
//summary of GET /devices and the device of GET /devices/{id}/detail-or-summary. properties - by the name of each property
//(gallons_used_today, total_regens, ...); null when the answer has none. enrichedData - what the cloud derives for its
//application (water_treatment.salt_level_percent, the salt level the application shows). Kept apart from SoftenerReading
@JsonIgnoreProperties(ignoreUnknown = true)
public record IquaDevice(
    String id,
    @JsonProperty("serial_number") String serialNumber,
    String nickname,
    @JsonProperty("system_type") String systemType,
    @JsonProperty("thing_name") String thingName,
    @JsonProperty("is_online") Boolean online,
    Map<String, IquaProperty> properties,
    @JsonProperty("enriched_data") JsonNode enrichedData
) {
}
