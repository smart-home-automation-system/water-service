package cloud.cholewa.water.api;

import cloud.cholewa.water.model.TemperatureHistoryReply;
import cloud.cholewa.water.service.TemperatureHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Slf4j
@RestController
@RequiredArgsConstructor
public class TemperatureHistoryController {

    static final String LOCAL_DATE_TIME = "yyyy-MM-dd'T'HH:mm:ss";

    private final TemperatureHistoryService temperatureHistoryService;

    //from and to are local date-times without an offset (2026-10-09T00:00:00), read by the clock of
    //the house - the rules of the temperature history of heating-service. A pattern instead of
    //ISO.DATE_TIME, which takes an offset as well and silently drops it. Spring still falls back to
    //LocalDateTime.parse, so a value without seconds or with fractions is read too - both are local
    //date-times; what matters is that an offset or a zone is a 400
    @GetMapping("temperature/history")
    Mono<ResponseEntity<TemperatureHistoryReply>> queryTemperatureHistory(
        @RequestParam @DateTimeFormat(pattern = LOCAL_DATE_TIME) final LocalDateTime from,
        @RequestParam @DateTimeFormat(pattern = LOCAL_DATE_TIME) final LocalDateTime to
    ) {
        return temperatureHistoryService.queryHistory(from, to)
            .doOnSubscribe(subscription -> log.info("Querying the temperature history from: {} to: {}", from, to))
            .map(ResponseEntity::ok);
    }
}
