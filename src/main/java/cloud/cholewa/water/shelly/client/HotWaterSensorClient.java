package cloud.cholewa.water.shelly.client;

import cloud.cholewa.shelly.model.ShellyUniStatusResponse;
import cloud.cholewa.water.shelly.config.HotWaterSensorConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(HotWaterSensorConfiguration.class)
public class HotWaterSensorClient {

    private final HotWaterSensorConfiguration hotWaterSensorConfiguration;
    private final WebClient shellyWebClient;

    public Mono<ShellyUniStatusResponse> getTemperatures() {
        return shellyWebClient
            .get()
            .uri(uriBuilder -> uriBuilder
                .scheme("http")
                .host(hotWaterSensorConfiguration.host())
                .path("status")
                .build())
            .retrieve()
            .onStatus(HttpStatusCode::isError, clientResponse ->  Mono.error(new RuntimeException()))
            .bodyToMono(ShellyUniStatusResponse.class);
    }
}
