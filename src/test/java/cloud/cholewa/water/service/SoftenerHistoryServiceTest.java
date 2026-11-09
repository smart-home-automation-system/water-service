package cloud.cholewa.water.service;

import cloud.cholewa.water.database.repository.SoftenerRepository;
import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.infrastructure.error.WaterError;
import cloud.cholewa.water.model.SoftenerDayReply;
import cloud.cholewa.water.model.SoftenerHistoryReply;
import cloud.cholewa.water.model.SoftenerRegenerationReply;
import cloud.cholewa.water.softener.SoftenerMetric;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//What the house keeps of the softener (HAS-398): the metric and the range checked before any statement, the bucket by the
//length, the days and the regenerations of the last 30 days by default - both days included
class SoftenerHistoryServiceTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private final SoftenerRepository repository = mock(SoftenerRepository.class);
    private final SoftenerHistoryService sut = new SoftenerHistoryService(repository,
        Clock.fixed(Instant.parse("2026-11-09T09:00:00Z"), HOUSE));

    @Test
    void should_answer_the_history_of_a_metric() {
        final LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        final LocalDateTime to = LocalDateTime.of(2026, 11, 9, 0, 0);
        final SoftenerHistoryReply.Point point = new SoftenerHistoryReply.Point(from, new BigDecimal("25.00"),
            new BigDecimal("25.0"), new BigDecimal("25.0"));
        when(repository.history(SoftenerMetric.SALT_LEVEL, from, to, 86400)).thenReturn(Flux.just(point));

        sut.history("saltLevel", "2026-10-01T00:00", "2026-11-09T00:00").as(StepVerifier::create)
            .expectNext(new SoftenerHistoryReply("saltLevel", from, to, 86400, List.of(point)))
            .verifyComplete();
    }

    @Test
    void should_refuse_an_unknown_metric_and_a_bad_range_before_any_statement() {
        sut.history("salt", "2026-10-01T00:00", "2026-11-09T00:00").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(((ValveException) failure).getError())
                .isEqualTo(WaterError.UNKNOWN_METRIC))
            .verify();
        sut.history("saltLevel", "2025-01-01T00:00", "2026-11-09T00:00").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(((ValveException) failure).getError())
                .isEqualTo(WaterError.INVALID_RANGE))
            .verify();
        sut.history("saltLevel", null, "2026-11-09T00:00").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).hasMessage("from is required"))
            .verify();

        verifyNoInteractions(repository);
    }

    //the last 30 days up to today, both included: the statement reads up to the day after
    @Test
    void should_answer_the_days_of_the_last_month_by_default() {
        when(repository.days(any(), any())).thenReturn(Flux.just(
            new SoftenerRepository.SoftenerDay(LocalDate.of(2026, 11, 8), new BigDecimal("310.0"))));

        sut.days(null, null).as(StepVerifier::create)
            .expectNext(new SoftenerDayReply(LocalDate.of(2026, 11, 8), new BigDecimal("310.0")))
            .verifyComplete();

        verify(repository).days(LocalDate.of(2026, 10, 11), LocalDate.of(2026, 11, 10));
    }

    @Test
    void should_answer_the_regenerations_with_whether_each_goes_on() {
        final LocalDateTime start = LocalDateTime.of(2026, 11, 9, 2, 0);
        when(repository.regenerations(any(), any())).thenReturn(Flux.just(
            new SoftenerRepository.StoredRegeneration(start, null, start),
            new SoftenerRepository.StoredRegeneration(start.minusDays(3), start.minusDays(3).plusHours(2), null)));

        sut.regenerations("2026-11-01", "2026-11-09").as(StepVerifier::create)
            .expectNext(new SoftenerRegenerationReply(start, null, start, true))
            .expectNext(new SoftenerRegenerationReply(start.minusDays(3), start.minusDays(3).plusHours(2), null, false))
            .verifyComplete();

        verify(repository).regenerations(LocalDateTime.of(2026, 11, 1, 0, 0), LocalDateTime.of(2026, 11, 10, 0, 0));
    }

    @Test
    void should_refuse_more_than_a_year_of_days() {
        sut.days("2025-11-08", "2026-11-09").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).hasMessage("The range must not be longer than 366 days"))
            .verify();
    }
}
