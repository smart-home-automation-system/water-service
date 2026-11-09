package cloud.cholewa.water.service;

import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.infrastructure.error.WaterError;
import cloud.cholewa.water.model.SoftenerLiveReply;
import cloud.cholewa.water.softener.IquaException;
import cloud.cholewa.water.softener.SoftenerLive;
import cloud.cholewa.water.softener.SoftenerReader;
import cloud.cholewa.water.softener.SoftenerSamples;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The live stream for the water page (HAS-401): until when on the clock of the house, 503 before the softener is known,
//429 LIVE_THROTTLED for the throttle of the cloud, 502 for any other failure
class SoftenerLiveServiceTest {

    private final SoftenerSamples.MovableClock clock = new SoftenerSamples.MovableClock();
    private final SoftenerReader reader = mock(SoftenerReader.class);
    private final SoftenerLive live = mock(SoftenerLive.class);
    private SoftenerLiveService sut;

    @BeforeEach
    void setUp() {
        when(reader.deviceId()).thenReturn(Optional.of("dev-1"));
        sut = new SoftenerLiveService(reader, live, clock);
    }

    @Test
    void should_answer_until_when_the_stream_is_open() {
        when(live.open("dev-1")).thenReturn(Mono.just(SoftenerSamples.START.plus(Duration.ofMinutes(5))));

        sut.open().as(StepVerifier::create)
            .expectNext(new SoftenerLiveReply(true, LocalDateTime.of(2026, 11, 9, 10, 5)))
            .verifyComplete();
    }

    @Test
    void should_be_unavailable_before_the_softener_was_found() {
        when(reader.deviceId()).thenReturn(Optional.empty());

        sut.open().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(((ValveException) failure).getError())
                .isEqualTo(WaterError.SOFTENER_UNAVAILABLE))
            .verify();
        verify(live, never()).open(anyString());
    }

    @Test
    void should_answer_the_throttle_of_the_cloud() {
        when(live.open("dev-1")).thenReturn(Mono.error(new IquaException("The iQua cloud answered HTTP 429 for the live "
            + "stream", 429)));

        sut.open().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(((ValveException) failure).getError())
                .isEqualTo(WaterError.LIVE_THROTTLED))
            .verify();
    }

    @Test
    void should_answer_another_failure_with_its_status_alone() {
        when(live.open("dev-1")).thenReturn(Mono.error(new IquaException("The iQua cloud answered HTTP 500 for the live "
            + "stream", 500)));

        sut.open().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> {
                assertThat(((ValveException) failure).getError()).isEqualTo(WaterError.DEVICE_FAILED);
                assertThat(failure).hasMessage("The iQua cloud refused the live stream of the softener with 500");
            })
            .verify();
    }

    @Test
    void should_answer_a_socket_that_did_not_open() {
        when(live.open("dev-1")).thenReturn(Mono.error(new IquaException("The live stream of the softener did not open")));

        sut.open().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).hasMessage("The live stream of the softener did not open"))
            .verify();
    }

    @Test
    void should_close_the_stream() {
        sut.close().as(StepVerifier::create).verifyComplete();

        verify(live).close();
    }
}
