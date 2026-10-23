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

import java.time.Duration;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

    //HAS-307: a Shelly that takes the connection and never answers ends the pass at its bound, below the
    //rate - the next reading comes on time
    @Test
    void should_end_a_reading_that_never_completes_at_its_bound() {
        when(waterService.handleWaterUpdate()).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(sut::updateHotWaterTemperature)
            .expectSubscription()
            .expectNoEvent(WaterSensorCron.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }

    //Spring calls the method once and subscribes to the same Mono for every run
    @Test
    void should_read_anew_for_every_run() {
        when(waterService.handleWaterUpdate()).thenReturn(Mono.empty());

        final Mono<Void> scheduled = sut.updateHotWaterTemperature();
        scheduled.as(StepVerifier::create).verifyComplete();
        scheduled.as(StepVerifier::create).verifyComplete();

        verify(waterService, times(2)).handleWaterUpdate();
    }
}
