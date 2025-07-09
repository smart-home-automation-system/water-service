package cloud.cholewa.water.hot.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class HotWaterStatus {
    private double temperature;
    private boolean pumpEnabled;
    private boolean furnaceEnabled;
}
