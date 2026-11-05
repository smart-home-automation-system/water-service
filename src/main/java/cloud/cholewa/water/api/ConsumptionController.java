package cloud.cholewa.water.api;

import cloud.cholewa.water.model.ConsumptionReply;
import cloud.cholewa.water.model.ImportedDay;
import cloud.cholewa.water.service.ConsumptionImportService;
import cloud.cholewa.water.service.ConsumptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

//The consumption of water (HAS-317), routed by the gateway under /home/water/** - no gateway release. Both parameters are
//read as text, so a missing or a wrong one is refused by the service with the code INVALID_RANGE, never by WebFlux
//without one
@Slf4j
@RestController
@RequiredArgsConstructor
public class ConsumptionController {

    private final ConsumptionService consumptionService;
    private final ConsumptionImportService consumptionImportService;

    @GetMapping("consumption")
    Mono<ResponseEntity<ConsumptionReply>> getConsumption(
        @RequestParam(required = false) final String range,
        @RequestParam(required = false) final String at
    ) {
        return consumptionService.consumption(range, at)
            .doOnSubscribe(subscription -> log.info("Querying the consumption of water, range: {}, at: {}", range, at))
            .map(ResponseEntity::ok);
    }

    //the daily consumption imported from the Shelly cloud (HAS-318), oldest first
    @GetMapping("consumption/imported")
    Flux<ImportedDay> getImported() {
        return consumptionImportService.all()
            .doOnSubscribe(subscription -> log.info("Querying the imported consumption"));
    }

    //the whole import replaced by the body - sent again, it fixes what was sent before
    @PutMapping("consumption/imported")
    Mono<ResponseEntity<Void>> putImported(@RequestBody final List<ImportedDay> days) {
        return consumptionImportService.replace(days)
            .doOnSubscribe(subscription -> log.info("Importing the consumption of {} days", days.size()))
            .thenReturn(ResponseEntity.noContent().build());
    }
}
