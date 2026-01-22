package cloud.cholewa.water.cron;

import cloud.cholewa.water.service.WaterService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaterSensorCronTest {

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private WaterService waterService;

    @InjectMocks
    private WaterSensorCron sut;

    @Test
    void should_update_hot_water_temperature() {
        when(waterService.handleWaterUpdate()).thenReturn(Mono.empty());

        sut.updateHotWaterTemperature()
            .as(StepVerifier::create)
            .verifyComplete();
    }
}
