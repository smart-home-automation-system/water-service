package cloud.cholewa.water.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.web.util.UriBuilder;

@ConfigurationProperties("shelly.sensor.uni.hot-water")
public record WaterSensorConfig(String host, int port) {

    //the address of the Uni for shelly-client (HAS-404): host:port, as a device of the registry has it
    public String address() {
        return host + ":" + port;
    }

    public UriBuilder getUriBuilder(final UriBuilder uriBuilder) {
        return uriBuilder.scheme("http").host(host).port(port).path("status");
    }
}
