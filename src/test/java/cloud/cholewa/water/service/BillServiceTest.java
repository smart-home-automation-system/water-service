package cloud.cholewa.water.service;

import cloud.cholewa.water.database.model.ConsumptionBucket;
import cloud.cholewa.water.database.model.WaterBillEntity;
import cloud.cholewa.water.database.repository.WaterBillRepository;
import cloud.cholewa.water.database.repository.WaterMeterRepository;
import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.infrastructure.error.WaterError;
import cloud.cholewa.water.model.BillReply;
import cloud.cholewa.water.model.BillRequest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The bills of the waterworks (HAS-318): what the house measured over a period, and the writes
class BillServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 29);
    private static final LocalDate TO = LocalDate.of(2026, 10, 20);
    //2026-11-05 10:00 in the house, a fraction of a second past - stored to the second
    private static final Instant NOW = Instant.parse("2026-11-05T09:00:00.123456789Z");
    private static final LocalDateTime NOW_AT_HOME = LocalDateTime.of(2026, 11, 5, 10, 0);
    private static final LocalDateTime ENTERED = LocalDateTime.of(2026, 11, 1, 12, 0);
    private static final WaterBillEntity STORED = new WaterBillEntity(7L, FROM, TO, new BigDecimal("11.000"), null,
        new BigDecimal("98.76"), "Waterworks", ENTERED, ENTERED);

    private final WaterBillRepository billRepository = mock(WaterBillRepository.class);
    private final WaterMeterRepository meterRepository = mock(WaterMeterRepository.class);
    private final BillService sut = new BillService(billRepository, meterRepository,
        Clock.fixed(NOW, ZoneId.of("Europe/Warsaw")));

    //22 days from 29.09 to 20.10, both included: 500.9 L a day is 11.020 m3, 0.020 m3 over the bill - 0.2 %
    @Test
    void should_answer_what_the_house_measured_over_the_period() {
        when(billRepository.findById(7L)).thenReturn(Mono.just(STORED));
        when(meterRepository.consumption(FROM.atStartOfDay(), TO.plusDays(1).atStartOfDay(), "day"))
            .thenReturn(days(22, "0.5009"));

        sut.one(7L).as(StepVerifier::create)
            .expectNext(new BillReply(7L, FROM, TO, new BigDecimal("11.000"), null, new BigDecimal("98.76"), "Waterworks",
                new BigDecimal("11.020"), new BigDecimal("0.020"), new BigDecimal("0.2"), ENTERED, ENTERED))
            .verifyComplete();
    }

    //missing is never 0: a day of the period nobody watched leaves the measurement out
    @Test
    void should_leave_out_the_measurement_when_a_day_was_not_watched() {
        when(billRepository.findById(7L)).thenReturn(Mono.just(STORED));
        when(meterRepository.consumption(any(), any(), any())).thenReturn(days(21, "0.5"));

        sut.one(7L).as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.measuredVolume()).isNull();
                assertThat(reply.difference()).isNull();
                assertThat(reply.differencePercent()).isNull();
            })
            .verifyComplete();
    }

    //nothing billed: the difference is there, a percent of nothing is not
    @Test
    void should_leave_out_the_percent_of_nothing_billed() {
        when(billRepository.findById(7L)).thenReturn(Mono.just(STORED.withBilledVolume(BigDecimal.ZERO)));
        when(meterRepository.consumption(any(), any(), any())).thenReturn(days(22, "0"));

        sut.one(7L).as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.difference()).isEqualByComparingTo("0");
                assertThat(reply.differencePercent()).isNull();
            })
            .verifyComplete();
    }

    @Test
    void should_answer_not_found_for_a_bill_that_is_not_there() {
        when(billRepository.findById(8L)).thenReturn(Mono.empty());

        sut.one(8L).as(StepVerifier::create)
            .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(ValveException.class,
                exception -> assertThat(exception.getError()).isEqualTo(WaterError.NOT_FOUND_BILL)))
            .verify();

        sut.delete(8L).as(StepVerifier::create).expectError(ValveException.class).verify();
        verify(billRepository, never()).delete(any());
    }

    @Test
    void should_enter_a_bill_at_the_time_of_the_house() {
        when(billRepository.findOverlapping(FROM, TO, 0L)).thenReturn(Mono.empty());
        when(billRepository.save(any())).thenAnswer(call -> Mono.just(call.<WaterBillEntity>getArgument(0).withId(9L)));
        when(meterRepository.consumption(any(), any(), any())).thenReturn(Flux.empty());

        sut.create(new BillRequest(FROM, TO, new BigDecimal("11"), null, null, null)).as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.id()).isEqualTo(9L);
                assertThat(reply.createdAt()).isEqualTo(NOW_AT_HOME);
                assertThat(reply.updatedAt()).isEqualTo(NOW_AT_HOME);
                assertThat(reply.measuredVolume()).isNull();
            })
            .verifyComplete();
    }

    @Test
    void should_refuse_a_period_that_overlaps_another_bill() {
        when(billRepository.findOverlapping(FROM, TO, 0L)).thenReturn(Mono.just(3L));

        sut.create(new BillRequest(FROM, TO, new BigDecimal("11"), null, null, null)).as(StepVerifier::create)
            .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(ValveException.class, exception -> {
                assertThat(exception.getError()).isEqualTo(WaterError.INVALID_BILL);
                assertThat(exception.getMessage()).isEqualTo("The period overlaps the bill 3");
            }))
            .verify();
        verify(billRepository, never()).save(any());
    }

    //a bill that breaks the rules never reaches the table
    @Test
    void should_refuse_a_bill_before_asking_the_table() {
        sut.create(new BillRequest(TO, FROM, new BigDecimal("11"), null, null, null)).as(StepVerifier::create)
            .expectError(ValveException.class)
            .verify();
        verify(billRepository, never()).findOverlapping(any(), any(), anyLong());
    }

    //the whole bill replaced: its id and the time it was entered stay, it is not compared with itself
    @Test
    void should_replace_a_bill_whole() {
        when(billRepository.findById(7L)).thenReturn(Mono.just(STORED));
        when(billRepository.findOverlapping(FROM, TO, 7L)).thenReturn(Mono.empty());
        when(billRepository.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(meterRepository.consumption(any(), any(), any())).thenReturn(Flux.empty());

        sut.update(7L, new BillRequest(FROM, TO, new BigDecimal("12"), null, null, null)).as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.id()).isEqualTo(7L);
                assertThat(reply.billedVolume()).isEqualTo(new BigDecimal("12.000"));
                assertThat(reply.amount()).isNull();
                assertThat(reply.note()).isNull();
                assertThat(reply.createdAt()).isEqualTo(ENTERED);
                assertThat(reply.updatedAt()).isEqualTo(NOW_AT_HOME);
            })
            .verifyComplete();
    }

    @Test
    void should_delete_a_bill() {
        when(billRepository.findById(7L)).thenReturn(Mono.just(STORED));
        when(billRepository.delete(STORED)).thenReturn(Mono.empty());

        sut.delete(7L).as(StepVerifier::create).verifyComplete();
        verify(billRepository).delete(STORED);
    }

    @Test
    void should_answer_every_bill_newest_first() {
        final WaterBillEntity older = STORED.withId(6L).withPeriodFrom(FROM.minusMonths(1)).withPeriodTo(FROM.minusDays(1));
        when(billRepository.findAllByOrderByPeriodFromDesc()).thenReturn(Flux.just(STORED, older));
        when(meterRepository.consumption(any(), any(), any())).thenReturn(Flux.empty());

        sut.all().map(BillReply::id).as(StepVerifier::create).expectNext(7L, 6L).verifyComplete();
    }

    private static Flux<ConsumptionBucket> days(final int count, final String volume) {
        return Flux.fromStream(IntStream.range(0, count)
            .mapToObj(day -> new ConsumptionBucket(FROM.plusDays(day).atStartOfDay(), new BigDecimal(volume))));
    }
}
