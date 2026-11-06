package cloud.cholewa.water.api;

import cloud.cholewa.water.circulation.CirculationService;
import cloud.cholewa.water.config.ExceptionHandlerConfig;
import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.model.CirculationReply;
import cloud.cholewa.water.model.CirculationRunReply;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;

//The JSON of the circulation pump is the contract of the water page of the dashboard (HAS-404, HAS-406), so the bodies
//are written out
@WebFluxTest(CirculationController.class)
@Import(ExceptionHandlerConfig.class)
class CirculationControllerTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 5, 19, 0);

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private CirculationService circulationService;

    @Test
    void should_answer_the_pump_and_leave_out_what_is_not_known() {
        when(circulationService.current()).thenReturn(Mono.just(new CirculationReply(true, AT, "manual", "dashboard", 28.5,
            45.0, AT.plusSeconds(5), new CirculationRunReply(AT, null, "manual", "dashboard", null, new BigDecimal("24.50"),
            null))));

        webTestClient.get()
            .uri("/circulation")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {"running": true, "since": "2026-11-05T19:00:00", "trigger": "manual", "source": "dashboard",
                 "returnTemperature": 28.5, "tankTemperature": 45.0, "measuredAt": "2026-11-05T19:00:05",
                 "lastRun": {"startedAt": "2026-11-05T19:00:00", "trigger": "manual", "source": "dashboard",
                             "returnAtStart": 24.50}}
                """, JsonCompareMode.STRICT);
    }

    @Test
    void should_answer_nothing_known_as_an_empty_object() {
        when(circulationService.current())
            .thenReturn(Mono.just(new CirculationReply(null, null, null, null, null, null, null, null)));

        webTestClient.get()
            .uri("/circulation")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("{}", JsonCompareMode.STRICT);
    }

    //202: started is not warm - the read says when the return got there
    @Test
    void should_start_the_pump() {
        when(circulationService.start("dashboard")).thenReturn(Mono.just(new CirculationRunReply(AT, null, "manual",
            "dashboard", null, new BigDecimal("24.50"), null)));

        webTestClient.post()
            .uri("/circulation?source=dashboard")
            .exchange()
            .expectStatus().isAccepted()
            .expectBody().json("""
                {"startedAt": "2026-11-05T19:00:00", "trigger": "manual", "source": "dashboard", "returnAtStart": 24.50}
                """, JsonCompareMode.STRICT);
    }

    @Test
    void should_refuse_a_start_with_the_code_of_its_cause() {
        when(circulationService.start("dashboard")).thenReturn(Mono.error(ValveException.tankCold(37.5, 38.0)));
        when(circulationService.start(null)).thenReturn(Mono.error(ValveException.invalidCommand("The source is ...")));
        when(circulationService.start("bruno")).thenReturn(Mono.error(ValveException.pumpFailed(null)));

        webTestClient.post().uri("/circulation?source=dashboard").exchange()
            .expectStatus().isEqualTo(HttpStatus.CONFLICT)
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("TANK_COLD")
            .jsonPath("$.errors[0].details").isEqualTo("The tank is at 37.5 C, below 38.0 C");
        webTestClient.post().uri("/circulation").exchange()
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("INVALID_COMMAND");
        webTestClient.post().uri("/circulation?source=bruno").exchange()
            .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("DEVICE_FAILED");
    }

    @Test
    void should_answer_the_runs_of_a_range() {
        when(circulationService.runs(AT.minusDays(1), AT)).thenReturn(Mono.just(List.of(new CirculationRunReply(
            AT.minusHours(2), AT.minusHours(2).plusSeconds(70), "manual", "dashboard", "temperature",
            new BigDecimal("24.50"), new BigDecimal("35.10")))));

        webTestClient.get()
            .uri("/circulation/runs?from={from}&to={to}", "2026-11-04T19:00:00", "2026-11-05T19:00:00")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [{"startedAt": "2026-11-05T17:00:00", "endedAt": "2026-11-05T17:01:10", "trigger": "manual",
                  "source": "dashboard", "stoppedBy": "temperature", "returnAtStart": 24.50, "returnAtEnd": 35.10}]
                """, JsonCompareMode.STRICT);
    }

    @Test
    void should_refuse_a_wrong_range() {
        when(circulationService.runs(AT, AT.minusDays(1)))
            .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to")));

        webTestClient.get()
            .uri("/circulation/runs?from={from}&to={to}", "2026-11-05T19:00:00", "2026-11-04T19:00:00")
            .exchange()
            .expectStatus().isBadRequest();
        webTestClient.get()
            .uri("/circulation/runs?from={from}&to={to}", "2026-11-05T19:00:00+01:00", "2026-11-06T19:00:00")
            .exchange()
            .expectStatus().isBadRequest();
    }
}
