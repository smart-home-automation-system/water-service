package cloud.cholewa.water.valve;

import cloud.cholewa.shelly.model.BooleanStatus;
import cloud.cholewa.shelly.model.NumberStatus;
import cloud.cholewa.shelly.model.ObjectStatus;
import cloud.cholewa.water.database.repository.WaterMeterRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//The snapshot of every minute (HAS-316): only fresh values are stored, each table on its own
class ValveSnapshotTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final Instant REPORTED = Instant.parse("2026-11-04T18:51:20Z");
    private static final LocalDateTime REPORTED_AT = LocalDateTime.of(2026, 11, 4, 19, 51, 20);

    private final ValveTracker tracker = new ValveTracker(Clock.fixed(REPORTED, HOUSE));
    private final WaterMeterRepository repository = mock(WaterMeterRepository.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(REPORTED.plusSeconds(30));
    private final ValveSnapshot sut = new ValveSnapshot(tracker, new ValveProperties(Duration.ofMinutes(5)), repository,
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
    void should_store_the_counter_and_the_valve() {
        report();
        when(repository.recordCounter(any(), any())).thenReturn(Mono.just(true));
        when(repository.recordStatus(any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        sut.snapshot().as(StepVerifier::create).verifyComplete();

        verify(repository).recordCounter(new BigDecimal("61.665843064"), REPORTED_AT);
        verify(repository).recordStatus(true, 0.012, 495.04377, 12.9, REPORTED_AT);
    }

    //a valve gone silent stores nothing: the tables keep the time it was last heard. "Now" is read at every pass
    @Test
    void should_store_nothing_that_is_stale() {
        report();
        now.set(REPORTED.plusSeconds(301));

        final Mono<Void> pass = sut.snapshot();
        pass.as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(repository);

        now.set(REPORTED.plusSeconds(30));
        when(repository.recordCounter(any(), any())).thenReturn(Mono.just(false));
        when(repository.recordStatus(any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        pass.as(StepVerifier::create).verifyComplete();

        verify(repository).recordCounter(new BigDecimal("61.665843064"), REPORTED_AT);
    }

    //the counter alone is no status of the valve
    @Test
    void should_store_the_counter_without_a_status_of_the_valve() {
        tracker.report(200, ObjectStatus.builder().value(Map.of("counter", Map.of("total", 61.665843064))).build());
        when(repository.recordCounter(any(), any())).thenReturn(Mono.just(true));

        sut.snapshot().as(StepVerifier::create).verifyComplete();

        verify(repository, never()).recordStatus(any(), any(), any(), any(), any());
    }

    //the valve reports eleven decimals, the column keeps nine: rounded first, or the same total would never equal the row
    @Test
    void should_store_the_total_to_the_nine_decimals_of_the_column() {
        tracker.report(200, ObjectStatus.builder().value(Map.of("counter", Map.of("total", 61.72186713199))).build());
        when(repository.recordCounter(any(), any())).thenReturn(Mono.just(true));

        sut.snapshot().as(StepVerifier::create).verifyComplete();

        verify(repository).recordCounter(new BigDecimal("61.721867132"), REPORTED_AT);
    }

    //a failed table is a WARN and leaves the other
    @Test
    void should_store_the_valve_when_the_counter_cannot_be_stored() {
        report();
        when(repository.recordCounter(any(), any())).thenReturn(Mono.error(new IllegalStateException("database down")));
        when(repository.recordStatus(any(), any(), any(), any(), any()))
            .thenReturn(Mono.error(new IllegalStateException("database down")));

        sut.snapshot().as(StepVerifier::create).verifyComplete();

        verify(repository).recordStatus(true, 0.012, 495.04377, 12.9, REPORTED_AT);
    }

    //a write that hangs: the pass ends at its bound, below the minute between two
    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        report();
        when(repository.recordCounter(any(), any())).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(sut::snapshot)
            .expectSubscription()
            .expectNoEvent(ValveSnapshot.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }

    private void report() {
        tracker.report(200, BooleanStatus.builder().value(true).build());
        tracker.report(200, NumberStatus.builder().value(0.012).build());
        tracker.report(201, NumberStatus.builder().value(495.04377).build());
        tracker.report(202, NumberStatus.builder().value(12.9).build());
        tracker.report(200, ObjectStatus.builder().value(Map.of("counter", Map.of("total", 61.665843064))).build());
    }
}
