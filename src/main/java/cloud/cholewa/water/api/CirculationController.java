package cloud.cholewa.water.api;

import cloud.cholewa.water.circulation.CirculationService;
import cloud.cholewa.water.model.CirculationReply;
import cloud.cholewa.water.model.CirculationRunReply;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

import static cloud.cholewa.water.api.TemperatureHistoryController.LOCAL_DATE_TIME;

//The circulation pump of the hot water (HAS-404), routed by the gateway under /home/water/** - no gateway release
@Slf4j
@RestController
@RequiredArgsConstructor
public class CirculationController {

    private final CirculationService circulationService;

    @GetMapping("circulation")
    Mono<ResponseEntity<CirculationReply>> getCirculation() {
        return circulationService.current()
            .doOnSubscribe(subscription -> log.info("Querying the circulation pump"))
            .map(ResponseEntity::ok);
    }

    //source read as text, so that a missing or wrong one is INVALID_COMMAND with its code; 202 - started is not warm
    @PostMapping("circulation")
    Mono<ResponseEntity<CirculationRunReply>> start(@RequestParam(required = false) final String source) {
        return circulationService.start(source)
            .doOnSubscribe(subscription -> log.info("Starting the circulation pump by {}", source))
            .map(reply -> ResponseEntity.status(HttpStatus.ACCEPTED).body(reply));
    }

    //from and to are local date-times without an offset, the rules of the temperature history
    @GetMapping("circulation/runs")
    Mono<ResponseEntity<List<CirculationRunReply>>> getRuns(
        @RequestParam @DateTimeFormat(pattern = LOCAL_DATE_TIME) final LocalDateTime from,
        @RequestParam @DateTimeFormat(pattern = LOCAL_DATE_TIME) final LocalDateTime to
    ) {
        return circulationService.runs(from, to)
            .doOnSubscribe(subscription -> log.info("Querying the circulation runs from: {} to: {}", from, to))
            .map(ResponseEntity::ok);
    }
}
