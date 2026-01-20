package cloud.cholewa.water.hot.cron;

import cloud.cholewa.water.hot.service.HotWaterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class HotWaterSensorCron {

    private final HotWaterService hotWaterService;

    @Scheduled(fixedRateString = "PT1m", initialDelayString = "PT10s")
    Mono<Void> updateHotWaterTemperature() {
        return hotWaterService.handleWaterUpdate();
    }
}
