package cloud.cholewa.water.service;

import cloud.cholewa.water.database.model.ConsumptionBucket;
import cloud.cholewa.water.database.repository.WaterMeterRepository;
import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.infrastructure.error.WaterError;
import cloud.cholewa.water.model.ConsumptionReply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The consumption of water (HAS-317): the range asked for, the one before it, the totals and the rounding
class ConsumptionServiceTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    //2026-11-05 00:30 in the house, still 2026-11-04 in UTC
    private static final Instant NOW = Instant.parse("2026-11-04T23:30:00Z");
    private static final LocalDateTime TODAY = LocalDateTime.of(2026, 11, 5, 0, 0);

    private final WaterMeterRepository repository = mock(WaterMeterRepository.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final ConsumptionService sut = new ConsumptionService(repository, new Clock() {
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
    void should_answer_the_buckets_and_both_totals() {
        when(repository.consumption(TODAY, TODAY.plusDays(1), "hour")).thenReturn(Flux.just(
            new ConsumptionBucket(TODAY, new BigDecimal("0.0004")),
            new ConsumptionBucket(TODAY.plusHours(7), new BigDecimal("0.1234"))));
        when(repository.consumption(TODAY.minusDays(1), TODAY, "hour")).thenReturn(Flux.just(
            new ConsumptionBucket(TODAY.minusDays(1), new BigDecimal("0.2"))));

        sut.consumption("day", "2026-11-05").as(StepVerifier::create)
            .expectNext(new ConsumptionReply("day", TODAY, TODAY.plusDays(1), "m3", 0.124, 0.2, List.of(
                new ConsumptionReply.Bucket(TODAY, 0.0),
                new ConsumptionReply.Bucket(TODAY.plusHours(7), 0.123))))
            .verifyComplete();
    }

    //the total is the sum of the buckets before they are rounded: 0.0004 + 0.1234 is 0.124, not 0.123
    @Test
    void should_round_the_total_after_the_sum() {
        when(repository.consumption(any(), any(), anyString())).thenReturn(Flux.just(
            new ConsumptionBucket(TODAY, new BigDecimal("0.0006")),
            new ConsumptionBucket(TODAY.plusHours(1), new BigDecimal("0.0006"))));

        sut.consumption("day", null).as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.total()).isEqualTo(0.001);
                assertThat(reply.buckets()).extracting(ConsumptionReply.Bucket::volume).containsExactly(0.001, 0.001);
            })
            .verifyComplete();
    }

    //missing is never 0: a range in which the counter was not watched has no total
    @Test
    void should_leave_out_a_total_nobody_watched() {
        when(repository.consumption(any(), any(), anyString())).thenReturn(Flux.empty());

        sut.consumption("year", "2026-11-05").as(StepVerifier::create)
            .expectNext(new ConsumptionReply("year", LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2027, 1, 1, 0, 0),
                "m3", null, null, List.of()))
            .verifyComplete();
        verify(repository).consumption(LocalDateTime.of(2025, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 1, 0, 0), "month");
    }

    //today is the day of the house, and it is read at every call, not at the start of the pod
    @Test
    void should_read_today_by_the_clock_of_the_house_at_every_call() {
        when(repository.consumption(any(), any(), anyString())).thenReturn(Flux.empty());
        final Mono<ConsumptionReply> call = sut.consumption("week", null);

        call.map(ConsumptionReply::from).as(StepVerifier::create)
            .expectNext(LocalDateTime.of(2026, 11, 2, 0, 0))
            .verifyComplete();

        now.set(NOW.plus(Duration.ofDays(4)));

        call.map(ConsumptionReply::from).as(StepVerifier::create)
            .expectNext(LocalDateTime.of(2026, 11, 9, 0, 0))
            .verifyComplete();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-5", "05.11.2026", "2026-02-30", "today", "2026-11-05T00:00:00", "", "0000-01-01",
        "1999-12-31", "3000-01-01", "9999-12-31"})
    void should_refuse_a_date_that_is_not_one(final String at) {
        sut.consumption("day", at).as(StepVerifier::create)
            .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(ValveException.class,
                exception -> assertThat(exception.getError()).isEqualTo(WaterError.INVALID_RANGE)))
            .verify();

        verify(repository, never()).consumption(any(), any(), anyString());
    }

    @Test
    void should_refuse_a_range_that_is_not_one() {
        sut.consumption("hour", null).as(StepVerifier::create)
            .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(ValveException.class,
                exception -> assertThat(exception.getError()).isEqualTo(WaterError.INVALID_RANGE)))
            .verify();
    }
}
