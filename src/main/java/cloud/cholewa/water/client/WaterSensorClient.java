package cloud.cholewa.water.client;

import cloud.cholewa.shelly.model.ShellyUniStatusResponse;
import cloud.cholewa.water.config.WaterSensorConfig;
import cloud.cholewa.water.infrastructure.error.WaterException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(WaterSensorConfig.class)
public class WaterSensorClient {

    private final WaterSensorConfig waterSensorConfig;
    private final WebClient shellyWebClient;

    public Mono<ShellyUniStatusResponse> getTemperatures() {
        return shellyWebClient
            .get()
            .uri(uriBuilder -> waterSensorConfig.getUriBuilder(uriBuilder).build())
            .retrieve()
            .bodyToMono(ShellyUniStatusResponse.class)
            .doOnError(e -> log.error("Error fetching water sensor temperatures: {}", e.getMessage()))
            .onErrorMap(throwable -> new WaterException(throwable.getLocalizedMessage()));
    }
}
