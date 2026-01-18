package cloud.cholewa.water.hot.service;

import cloud.cholewa.shelly.model.ShellyProRelayResponse;
import cloud.cholewa.water.hot.model.CirculationStatus;
import cloud.cholewa.water.hot.model.HotWaterStatus;
import cloud.cholewa.water.shelly.client.BoilerActorClient;
import cloud.cholewa.water.shelly.client.HotWaterSensorClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class HotWaterService {

    private final HotWaterSensorClient hotWaterSensorClient;
    private final BoilerActorClient boilerActorClient;
    private final HotWaterStatus hotWaterStatus = HotWaterStatus.builder().build();
    private final CirculationStatus circulationStatus = new CirculationStatus();

    public Mono<Void> handleHotWaterUpdate() {
        return Mono.just(hotWaterStatus)
            .flatMap(this::storeTemperatures)
            .flatMap(this::updatePumpStatus)
            .flatMap(this::controlHotWaterPump)
            .flatMap(this::updateFurnaceStatus)
            .flatMap(this::controlFurnaceStatus);
    }

    private Mono<Void> controlFurnaceStatus(final HotWaterStatus hotWaterStatus) {
        if (hotWaterStatus.isPumpEnabled() && !hotWaterStatus.isFurnaceEnabled()) {
            return boilerActorClient.controlFurnace(true)
                .doOnNext(response -> {
                    hotWaterStatus.setFurnaceEnabled(Boolean.TRUE.equals(response.getIson()));
                    logFurnaceStatus(response);
                })
                .then();
        } else if (hotWaterStatus.isFurnaceEnabled() && !hotWaterStatus.isPumpEnabled()) {
            return boilerActorClient.controlFurnace(false)
                .doOnNext(response -> {
                    hotWaterStatus.setFurnaceEnabled(Boolean.TRUE.equals(response.getIson()));
                    logFurnaceStatus(response);
                })
                .then();
        }
        log.info("Furnace status change not required");
        return Mono.empty();
    }

    private static void logFurnaceStatus(ShellyProRelayResponse response) {
        log.info("Furnace status update, pump is: {}", response.getIson());
    }

    private Mono<HotWaterStatus> updateFurnaceStatus(final HotWaterStatus hotWaterStatus) {
        return boilerActorClient.getFurnaceStatus()
            .flatMap(response -> {
                hotWaterStatus.setFurnaceEnabled(Boolean.TRUE.equals(response.getOutput()));
                return Mono.just(hotWaterStatus);
            })
            .doOnNext(status -> log.info("Furnace status is: {}", status.isFurnaceEnabled()));
    }

    private Mono<HotWaterStatus> controlHotWaterPump(final HotWaterStatus hotWaterStatus) {
        if (hotWaterStatus.getTemperature() < 38 && !hotWaterStatus.isPumpEnabled()) {
            return boilerActorClient.controlHowWaterPump(true)
                .doOnNext(response -> hotWaterStatus
                    .setPumpEnabled(Boolean.TRUE.equals(response.getIson())))
                .doOnNext(HotWaterService::logPumpStatus)
                .flatMap(response -> Mono.just(hotWaterStatus));
        } else if (hotWaterStatus.getTemperature() > 42 && hotWaterStatus.isPumpEnabled()) {
            return boilerActorClient.controlHowWaterPump(false)
                .doOnNext(response -> hotWaterStatus
                    .setPumpEnabled(Boolean.TRUE.equals(response.getIson())))
                .doOnNext(HotWaterService::logPumpStatus)
                .flatMap(response -> Mono.just(hotWaterStatus));
        }
        log.info("Pump status change not required");
        return Mono.empty();
    }

    private static void logPumpStatus(ShellyProRelayResponse response) {
        log.info("Pump status update, pump is: {}", response.getIson());
    }

    private Mono<HotWaterStatus> updatePumpStatus(final HotWaterStatus hotWaterStatus) {
        return boilerActorClient.getHotWaterPumpStatus()
            .flatMap(response -> {
                hotWaterStatus.setPumpEnabled(Boolean.TRUE.equals(response.getOutput()));
                return Mono.just(hotWaterStatus);
            })
            .doOnNext(status -> log.info("Hot water pump status is: {}", status.isPumpEnabled()));
    }

    private Mono<HotWaterStatus> storeTemperatures(final HotWaterStatus hotWaterStatus) {
        return hotWaterSensorClient.getTemperatures()
            .doOnNext(response -> {
                hotWaterStatus.setTemperature(response.getExtTemperature().get("0").gettC());
                circulationStatus.setTemperature(response.getExtTemperature().get("1").gettC());
                log.info(
                    "Hot water temperature: {}\u00B0C, and circulation temperature: {}\u00B0C",
                    hotWaterStatus.getTemperature(),
                    circulationStatus.getTemperature()

                );
            })
            .flatMap(response -> Mono.just(hotWaterStatus));

    }
}
