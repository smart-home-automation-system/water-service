package cloud.cholewa.water.cron;

import cloud.cholewa.water.service.WaterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class WaterSensorCron {

    private final WaterService waterService;

    @Scheduled(fixedRateString = "PT3m", initialDelayString = "PT10s")
    Mono<Void> updateHotWaterTemperature() {
        return waterService.handleWaterUpdate();
    }
}
