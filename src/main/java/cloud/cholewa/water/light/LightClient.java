package cloud.cholewa.water.light;

import cloud.cholewa.commons.error.DownstreamErrors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

//A room of light-service over k8s DNS - not through the gateway (HAS-405): its lights and its occupancy. What a failing
//light-service says goes to the log; no body is a failed call, never a room without lights or motion
@Slf4j
@Component
public class LightClient {

    private final WebClient lightServiceWebClient;

    public LightClient(@Qualifier("lightServiceWebClient") final WebClient lightServiceWebClient) {
        this.lightServiceWebClient = lightServiceWebClient;
    }

    public Mono<LightRoom> room(final String room) {
        return lightServiceWebClient
            .get()
            .uri("/home/light/rooms/{room}", room)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> DownstreamErrors.read(response)
                .map(error -> {
                    log.warn("light-service answered {} to the read of the room {}: {}", error.status().value(), room,
                        error.errors());
                    return new IllegalStateException("light-service answered " + error.status().value());
                }))
            .bodyToMono(LightRoom.class)
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("light-service answered without the room " + room)));
    }
}
