package cloud.cholewa.water.valve;

import cloud.cholewa.water.database.repository.WaterMeterRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The nightly delete of the old readings of the counter (HAS-316)
class MeterRetentionTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final Instant NIGHT = Instant.parse("2026-11-05T02:25:00Z");

    private final WaterMeterRepository repository = mock(WaterMeterRepository.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(NIGHT);
    private final MeterRetention sut = new MeterRetention(repository, new MeterRetentionProperties(Duration.ofDays(730)),
        new Clock() {
            @Override
            public ZoneId getZone() {
                return HOUSE;
            }

            @Override
            public Clock withZone(final ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        });

    @Test
    void should_delete_the_readings_older_than_the_retention() {
        when(repository.deleteReadingsBefore(any())).thenReturn(Mono.just(3L));

        sut.purge().as(StepVerifier::create).verifyComplete();

        verify(repository).deleteReadingsBefore(LocalDateTime.of(2024, 11, 5, 3, 25));
    }

    //Spring subscribes again to the same Mono every night: the cutoff is read at every pass, not at the start of the pod
    @Test
    void should_move_the_cutoff_with_every_night() {
        when(repository.deleteReadingsBefore(any())).thenReturn(Mono.just(0L));
        final Mono<Void> pass = sut.purge();

        pass.as(StepVerifier::create).verifyComplete();
        now.set(NIGHT.plus(Duration.ofDays(1)));
        pass.as(StepVerifier::create).verifyComplete();

        verify(repository).deleteReadingsBefore(LocalDateTime.of(2024, 11, 5, 3, 25));
        verify(repository).deleteReadingsBefore(LocalDateTime.of(2024, 11, 6, 3, 25));
    }

    //a failed delete is a WARN, tried again the next night
    @Test
    void should_complete_when_the_delete_fails() {
        when(repository.deleteReadingsBefore(any())).thenReturn(Mono.error(new IllegalStateException("database down")));

        sut.purge().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        when(repository.deleteReadingsBefore(any())).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(sut::purge)
            .expectSubscription()
            .expectNoEvent(MeterRetention.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }
}
