package cloud.cholewa.water.service;

import cloud.cholewa.home.model.SystemActiveReply;
import cloud.cholewa.water.mapper.WaterMapper;
import cloud.cholewa.water.client.WaterSensorClient;
import cloud.cholewa.water.database.repository.WaterTemperatureRepository;
import cloud.cholewa.water.model.TemperatureReply;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class WaterService {

    private final WaterSensorClient waterSensorClient;
    private final WaterMapper mapper;
    private final WaterTemperatureRepository repository;

    private boolean waterHeatingEnabled = false;

    public Mono<SystemActiveReply> queryWaterSystemActive() {
        return Mono.just(SystemActiveReply.builder().active(waterHeatingEnabled).build());
    }

    public Mono<Void> handleWaterUpdate() {
        return waterSensorClient.getTemperatures()
            .doOnNext(temperatures ->
                log.info(
                    "Received temperatures, hot water: {}C, circulation: {}C",
                    temperatures.getExtTemperature().get("0").gettC(),
                    temperatures.getExtTemperature().get("1").gettC()
                )
            )
            .flatMap(temperatures -> repository.save(mapper.toEntity(temperatures)))
            .doOnNext(temperatureEntity -> updateWaterHeatingStatus(temperatureEntity.water()))
            .then();
    }

    private void updateWaterHeatingStatus(final double temperature) {
        if (temperature > 42 && waterHeatingEnabled) {
            waterHeatingEnabled = false;
            log.info("Water heating disabled, current temperature: {}C", temperature);
        }
        if (temperature < 38 && !waterHeatingEnabled) {
            waterHeatingEnabled = true;
            log.info("Water heating enabled, current temperature: {}C", temperature);
        }
    }

    public Mono<TemperatureReply> queryWaterSystemTemperature() {
        return repository.findFirstByOrderByUpdatedAtDesc()
            .map(mapper::toReply);
    }
}
