package cloud.cholewa.water.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.web.util.UriBuilder;

@ConfigurationProperties("shelly.sensor.uni.hot-water")
public record WaterSensorConfig(String host, int port) {

    public UriBuilder getUriBuilder(final UriBuilder uriBuilder) {
        return uriBuilder.scheme("http").host(host).port(port).path("status");
    }
}
