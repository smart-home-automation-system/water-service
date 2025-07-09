package cloud.cholewa.water.shelly.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("shelly.sensor.uni.hot-water")
public record HotWaterSensorConfiguration(String host) {
}
