package cloud.cholewa.water.api;

import cloud.cholewa.water.model.ConsumptionReply;
import cloud.cholewa.water.service.ConsumptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

//The consumption of water (HAS-317), routed by the gateway under /home/water/** - no gateway release. Both parameters are
//read as text, so a missing or a wrong one is refused by the service with the code INVALID_RANGE, never by WebFlux
//without one
@Slf4j
@RestController
@RequiredArgsConstructor
public class ConsumptionController {

    private final ConsumptionService consumptionService;

    @GetMapping("consumption")
    Mono<ResponseEntity<ConsumptionReply>> getConsumption(
        @RequestParam(required = false) final String range,
        @RequestParam(required = false) final String at
    ) {
        return consumptionService.consumption(range, at)
            .doOnSubscribe(subscription -> log.info("Querying the consumption of water, range: {}, at: {}", range, at))
            .map(ResponseEntity::ok);
    }
}
