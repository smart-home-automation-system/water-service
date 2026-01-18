package cloud.cholewa.water.shelly.client;

import cloud.cholewa.shelly.model.ShellyPro4StatusResponse;
import cloud.cholewa.shelly.model.ShellyProRelayResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class BoilerActorClient {

    private final WebClient webClient;

    public Mono<ShellyPro4StatusResponse> getHotWaterPumpStatus() {
        return webClient
            .get()
            .uri(uriBuilder -> uriBuilder
                .scheme("http")
                .host("10.78.30.20")
                .path("rpc/Switch.GetStatus")
                .queryParam("id", "1")
                .build())
            .retrieve()
            .bodyToMono(ShellyPro4StatusResponse.class);
    }

    public Mono<ShellyProRelayResponse> controlHowWaterPump(final boolean pumpEnabled) {
        return webClient
            .get()
            .uri(uriBuilder -> uriBuilder
                .scheme("http")
                .host("10.78.30.20")
                .path("relay/1")
                .queryParam("turn", pumpEnabled ? "on" : "off")
                .build())
            .retrieve()
            .bodyToMono(ShellyProRelayResponse.class);
    }

    public Mono<ShellyPro4StatusResponse> getFurnaceStatus() {
        return webClient.get()
            .uri(uriBuilder -> uriBuilder.scheme("http").host("10.78.30.20")
                .path("rpc/Switch.GetStatus")
                .queryParam("id", "0")
                .build())
            .retrieve()
            .bodyToMono(ShellyPro4StatusResponse.class);
    }

    public Mono<ShellyProRelayResponse> controlFurnace(final boolean furnaceEnabled) {
        return webClient.get()
            .uri(uriBuilder -> uriBuilder.scheme("http").host("10.78.30.20")
                .path("relay/0")
                .queryParam("turn", furnaceEnabled ? "on" : "off")
                .build())
            .retrieve()
            .bodyToMono(ShellyProRelayResponse.class);
    }
}
