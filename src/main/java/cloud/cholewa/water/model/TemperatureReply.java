package cloud.cholewa.water.model;

import lombok.Builder;

@Builder
public record TemperatureReply(
    HotWaterStatus water,
    CirculationStatus circulation
) {
}
