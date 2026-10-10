package cloud.cholewa.water.service;

import cloud.cholewa.water.database.model.WaterTemperatureBucket;
import cloud.cholewa.water.database.repository.WaterTemperatureRepository;
import cloud.cholewa.water.model.TemperatureHistoryReply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemperatureHistoryServiceTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 8, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 10, 9, 0, 0);

    @Mock
    private WaterTemperatureRepository repository;

    @InjectMocks
    private TemperatureHistoryService sut;

    @Test
    void should_answer_the_buckets_of_the_range_as_points_with_both_temperatures() {
        when(repository.findHistory(FROM, TO, 300)).thenReturn(Flux.just(
            new WaterTemperatureBucket(LocalDateTime.of(2026, 10, 8, 0, 0), 46.81, 26.44),
            new WaterTemperatureBucket(LocalDateTime.of(2026, 10, 8, 0, 10), 46.5, 26.4)
        ));

        sut.queryHistory(FROM, TO)
            .as(StepVerifier::create)
            .expectNext(new TemperatureHistoryReply(
                FROM, TO, 300, List.of(
                new TemperatureHistoryReply.Point(LocalDateTime.of(2026, 10, 8, 0, 0), 46.81, 26.44),
                new TemperatureHistoryReply.Point(LocalDateTime.of(2026, 10, 8, 0, 10), 46.5, 26.4)
            )))
            .verifyComplete();
    }

    @Test
    void should_answer_a_range_without_readings_with_no_points() {
        when(repository.findHistory(FROM, TO, 300)).thenReturn(Flux.empty());

        sut.queryHistory(FROM, TO)
            .as(StepVerifier::create)
            .expectNext(new TemperatureHistoryReply(FROM, TO, 300, List.of()))
            .verifyComplete();
    }

    @ParameterizedTest
    @CsvSource({"2, 300", "8, 1800", "31, 7200"})
    void should_ask_for_wider_buckets_over_a_longer_range(final long days, final long bucketSeconds) {
        final LocalDateTime to = FROM.plusDays(days);
        when(repository.findHistory(FROM, to, bucketSeconds)).thenReturn(Flux.empty());

        sut.queryHistory(FROM, to)
            .as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.bucketSeconds()).isEqualTo(bucketSeconds))
            .verifyComplete();
    }

    //a range that is refused costs no query
    @ParameterizedTest
    @CsvSource({
        "2026-10-08T00:00:00, 2026-11-09T00:00:00, The range must not be longer than 31 days",
        "2026-10-09T00:00:00, 2026-10-08T00:00:00, from must be before to"
    })
    void should_refuse_a_range_without_asking_the_database(
        final LocalDateTime from,
        final LocalDateTime to,
        final String reason
    ) {
        sut.queryHistory(from, to)
            .as(StepVerifier::create)
            .expectErrorSatisfies(throwable -> assertThat(throwable)
                .isInstanceOfSatisfying(ResponseStatusException.class, refusal -> {
                    assertThat(refusal.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(refusal.getReason()).isEqualTo(reason);
                }))
            .verify();

        verifyNoInteractions(repository);
    }
}
