package cloud.cholewa.water.hot.service;

import cloud.cholewa.water.mapper.WaterMapper;
import cloud.cholewa.water.client.WaterSensorClient;
import cloud.cholewa.water.database.repository.WaterTemperatureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class HotWaterService {

    private final WaterSensorClient waterSensorClient;
    private final WaterMapper waterMapper;
    private final WaterTemperatureRepository waterTemperatureRepository;

    public Mono<Void> handleWaterUpdate() {
        return waterSensorClient.getTemperatures()
            .doOnNext(temperatures ->
                log.info(
                    "Received temperatures, hot water: {}C, circulation: {}C",
                    temperatures.getExtTemperature().get("0").gettC(),
                    temperatures.getExtTemperature().get("1").gettC()
                )
            )
            .flatMap(temperatures -> waterTemperatureRepository.save(waterMapper.toEntity(temperatures)))
            .doOnNext(temperatureEntity -> log.info("Saved temperature entity with ID: {}", temperatureEntity.id()))
            .then()
            ;
    }
}
