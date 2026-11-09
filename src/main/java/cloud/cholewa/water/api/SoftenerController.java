package cloud.cholewa.water.api;

import cloud.cholewa.water.model.SoftenerDayReply;
import cloud.cholewa.water.model.SoftenerHistoryReply;
import cloud.cholewa.water.model.SoftenerRegenerationReply;
import cloud.cholewa.water.model.SoftenerReply;
import cloud.cholewa.water.service.SoftenerHistoryService;
import cloud.cholewa.water.service.SoftenerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

//The water softener from the iQua cloud (HAS-397) and what the house keeps of it (HAS-398), routed by the gateway under
///home/water/** - no gateway release. The parameters are read as text, so that a missing or wrong one is INVALID_RANGE /
//UNKNOWN_METRIC with its code - a typed parameter would be refused by WebFlux without one
@Slf4j
@RestController
@RequiredArgsConstructor
public class SoftenerController {

    private final SoftenerService softenerService;
    private final SoftenerHistoryService softenerHistoryService;

    @GetMapping("softener")
    Mono<ResponseEntity<SoftenerReply>> getSoftener() {
        return softenerService.current()
            .doOnSubscribe(subscription -> log.info("Querying the water softener"))
            .map(ResponseEntity::ok);
    }

    @GetMapping("softener/history")
    Mono<ResponseEntity<SoftenerHistoryReply>> getHistory(
        @RequestParam(required = false) final String metric,
        @RequestParam(required = false) final String from,
        @RequestParam(required = false) final String to
    ) {
        return softenerHistoryService.history(metric, from, to)
            .doOnSubscribe(subscription -> log.info("Querying the history of the softener: {} from {} to {}", metric, from,
                to))
            .map(ResponseEntity::ok);
    }

    @GetMapping("softener/days")
    Flux<SoftenerDayReply> getDays(
        @RequestParam(required = false) final String from,
        @RequestParam(required = false) final String to
    ) {
        return softenerHistoryService.days(from, to)
            .doOnSubscribe(subscription -> log.info("Querying the days of the softener from {} to {}", from, to));
    }

    @GetMapping("softener/regenerations")
    Flux<SoftenerRegenerationReply> getRegenerations(
        @RequestParam(required = false) final String from,
        @RequestParam(required = false) final String to
    ) {
        return softenerHistoryService.regenerations(from, to)
            .doOnSubscribe(subscription -> log.info("Querying the regenerations of the softener from {} to {}", from, to));
    }
}
