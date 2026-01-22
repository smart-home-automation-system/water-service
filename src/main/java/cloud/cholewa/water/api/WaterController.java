package cloud.cholewa.water.api;

import cloud.cholewa.home.model.SystemActiveReply;
import cloud.cholewa.water.model.TemperatureReply;
import cloud.cholewa.water.service.WaterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequiredArgsConstructor
public class WaterController {

    private final WaterService waterService;

    @GetMapping("status/active")
    Mono<ResponseEntity<SystemActiveReply>> querySystemActive() {
        return waterService.queryWaterSystemActive()
            .doOnSubscribe(subscription -> log.info("Querying system active status"))
            .map(ResponseEntity::ok);
    }

    @GetMapping("status/temperature")
    Mono<ResponseEntity<TemperatureReply>> getSystemTemperature() {
        return waterService.queryWaterSystemTemperature()
            .doOnSubscribe(subscription -> log.info("Querying system temperatures related to water heating"))
            .map(ResponseEntity::ok);
    }
}
