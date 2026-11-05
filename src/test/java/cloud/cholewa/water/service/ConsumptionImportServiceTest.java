package cloud.cholewa.water.service;

import cloud.cholewa.water.database.repository.ConsumptionImportRepository;
import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.model.ImportedDay;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The import of the daily consumption (HAS-318): a day must not be after the first day of the readings of the house
class ConsumptionImportServiceTest {

    private static final List<ImportedDay> DAYS = List.of(new ImportedDay(LocalDate.of(2026, 11, 3), new BigDecimal("0.4")));

    private final ConsumptionImportRepository repository = mock(ConsumptionImportRepository.class);
    //2026-11-05 in the house
    private final ConsumptionImportService sut = new ConsumptionImportService(repository,
        Clock.fixed(Instant.parse("2026-11-05T09:00:00Z"), ZoneId.of("Europe/Warsaw")));

    @Test
    void should_replace_the_days_up_to_the_first_reading() {
        when(repository.firstReadingDay()).thenReturn(Mono.just(LocalDate.of(2026, 11, 4)));
        when(repository.replaceAll(DAYS)).thenReturn(Mono.empty());

        sut.replace(DAYS).as(StepVerifier::create).verifyComplete();
        verify(repository).replaceAll(DAYS);
    }

    @Test
    void should_refuse_a_day_after_the_first_reading_and_write_nothing() {
        when(repository.firstReadingDay()).thenReturn(Mono.just(LocalDate.of(2026, 11, 2)));

        sut.replace(DAYS).as(StepVerifier::create).expectError(ValveException.class).verify();
        verify(repository, never()).replaceAll(any());
    }

    //without a reading the bound is today of the house
    @Test
    void should_bound_the_days_by_today_while_there_is_no_reading() {
        when(repository.firstReadingDay()).thenReturn(Mono.empty());
        when(repository.replaceAll(any())).thenReturn(Mono.empty());

        sut.replace(DAYS).as(StepVerifier::create).verifyComplete();
        sut.replace(List.of(new ImportedDay(LocalDate.of(2026, 11, 6), BigDecimal.ONE))).as(StepVerifier::create)
            .expectError(ValveException.class)
            .verify();
    }
}
