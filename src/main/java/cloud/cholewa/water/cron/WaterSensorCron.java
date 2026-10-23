package cloud.cholewa.water.cron;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.water.service.WaterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class WaterSensorCron {

    //Bounded (HAS-307): one read of the Shelly Uni and one insert - the client has a connect timeout but
    //none for the answer, so a device that takes the connection and never answers would hold the pass for
    //good; at a fixed rate Spring does not wait for it, and every 3 minutes another hung pass would join.
    //Ended here, below the rate; the next reading comes on time
    static final Duration LIMIT = Duration.ofMinutes(2);

    private final WaterService waterService;

    @Scheduled(fixedRateString = "PT3m", initialDelayString = "PT10s")
    Mono<Void> updateHotWaterTemperature() {
        return ScheduledPass.bounded("hot water reading", LIMIT, waterService::handleWaterUpdate);
    }
}
