package cloud.cholewa.water.api;

import cloud.cholewa.water.config.ExceptionHandlerConfig;
import cloud.cholewa.water.model.TemperatureHistoryReply;
import cloud.cholewa.water.service.TemperatureHistoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//The JSON is the contract of the charts of the web dashboard (HAS-201), so the whole body is
//written out
@WebFluxTest(TemperatureHistoryController.class)
@Import(ExceptionHandlerConfig.class)
class TemperatureHistoryControllerTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 8, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 10, 9, 0, 0);
    private static final String HISTORY = "/temperature/history?from={from}&to={to}";

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean(answers = Answers.RETURNS_SMART_NULLS)
    private TemperatureHistoryService temperatureHistoryService;

    @Test
    void should_return_the_history() {
        when(temperatureHistoryService.queryHistory(FROM, TO)).thenReturn(Mono.just(
            new TemperatureHistoryReply(
                FROM, TO, 300, List.of(
                new TemperatureHistoryReply.Point(LocalDateTime.of(2026, 10, 8, 0, 0), 46.81, 26.44),
                new TemperatureHistoryReply.Point(LocalDateTime.of(2026, 10, 8, 0, 5), 46.5, 26.4)
            ))
        ));

        webTestClient.get()
            .uri(HISTORY, "2026-10-08T00:00:00", "2026-10-09T00:00:00")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json(
                """
                    {
                      "from": "2026-10-08T00:00:00",
                      "to": "2026-10-09T00:00:00",
                      "bucketSeconds": 300,
                      "points": [
                        {"at": "2026-10-08T00:00:00", "water": 46.81, "circulation": 26.44},
                        {"at": "2026-10-08T00:05:00", "water": 46.5, "circulation": 26.4}
                      ]
                    }
                    """, JsonCompareMode.STRICT
            );
    }

    //no readings in the range is an answer, not a failure: an empty list, never a missing one
    @Test
    void should_return_an_empty_history_as_an_empty_list() {
        when(temperatureHistoryService.queryHistory(FROM, TO)).thenReturn(Mono.just(
            new TemperatureHistoryReply(FROM, TO, 300, List.of())
        ));

        webTestClient.get()
            .uri(HISTORY, "2026-10-08T00:00:00", "2026-10-09T00:00:00")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json(
                """
                    {
                      "from": "2026-10-08T00:00:00",
                      "to": "2026-10-09T00:00:00",
                      "bucketSeconds": 300,
                      "points": []
                    }
                    """, JsonCompareMode.STRICT
            );
    }

    //read with the offset dropped, 22:00Z would be answered as 22:00 of the house
    @ParameterizedTest
    @ValueSource(strings = {"2026-10-08T00:00:00Z", "2026-10-08T00:00:00+02:00", "2026-10-08", "yesterday", ""})
    void should_answer_400_for_a_bound_that_is_not_a_local_date_time(final String bound) {
        webTestClient.get()
            .uri(HISTORY, bound, "2026-10-09T00:00:00")
            .exchange()
            .expectStatus().isBadRequest();

        webTestClient.get()
            .uri(HISTORY, "2026-10-07T00:00:00", bound)
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(temperatureHistoryService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/temperature/history",
        "/temperature/history?from=2026-10-08T00:00:00",
        "/temperature/history?to=2026-10-09T00:00:00"
    })
    void should_answer_400_without_both_bounds(final String uri) {
        webTestClient.get()
            .uri(uri)
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(temperatureHistoryService);
    }

    @Test
    void should_answer_400_with_the_reason_for_a_range_the_service_refuses() {
        when(temperatureHistoryService.queryHistory(TO, FROM)).thenReturn(Mono.error(
            new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to")
        ));

        webTestClient.get()
            .uri(HISTORY, "2026-10-09T00:00:00", "2026-10-08T00:00:00")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.errors.length()").isEqualTo(1)
            .jsonPath("$.errors[0].message").value(String.class, message ->
                assertThat(message).contains("from must be before to"));
    }
}
