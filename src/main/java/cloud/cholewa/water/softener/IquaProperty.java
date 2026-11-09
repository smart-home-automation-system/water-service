package cloud.cholewa.water.softener;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

//One property of an iQua device as it answers it (HAS-397) - DevicesGetPropertyResponseItem of its OpenAPI. value is in the
//unit of the device (gallons, gpm, tenths); convertedValue in convertedUnits, the units of the account (litres, l/min).
//Either may be a number, a string, a boolean or missing - read by SoftenerReading
@JsonIgnoreProperties(ignoreUnknown = true)
public record IquaProperty(
    String name,
    JsonNode value,
    @JsonProperty("converted_value") JsonNode convertedValue,
    @JsonProperty("converted_units") String convertedUnits,
    @JsonProperty("updated_at") String updatedAt
) {
}
