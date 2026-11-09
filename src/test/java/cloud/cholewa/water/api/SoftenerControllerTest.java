package cloud.cholewa.water.api;

import cloud.cholewa.water.config.ExceptionHandlerConfig;
import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.model.SoftenerAlertReply;
import cloud.cholewa.water.model.SoftenerCommandReply;
import cloud.cholewa.water.model.SoftenerReply;
import cloud.cholewa.water.model.SoftenerDayReply;
import cloud.cholewa.water.model.SoftenerHistoryReply;
import cloud.cholewa.water.model.SoftenerRegenerationReply;
import cloud.cholewa.water.service.SoftenerAlertService;
import cloud.cholewa.water.service.SoftenerCommandService;
import cloud.cholewa.water.service.SoftenerHistoryService;
import cloud.cholewa.water.service.SoftenerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.mockito.Mockito.when;

//The JSON of GET /softener is the contract of the softener card of the dashboard (HAS-397, HAS-402), so the bodies are
//written out
@WebFluxTest(SoftenerController.class)
@Import(ExceptionHandlerConfig.class)
class SoftenerControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private SoftenerService softenerService;

    @MockitoBean
    private SoftenerHistoryService softenerHistoryService;

    @MockitoBean
    private SoftenerAlertService softenerAlertService;

    @MockitoBean
    private SoftenerCommandService softenerCommandService;

    //HAS-398: the history of a metric - the shape of the weather and the hot-water histories
    @Test
    void should_answer_the_history_of_a_metric() {
        when(softenerHistoryService.history("saltLevel", "2026-11-08T00:00", "2026-11-09T00:00"))
            .thenReturn(Mono.just(new SoftenerHistoryReply("saltLevel", LocalDateTime.of(2026, 11, 8, 0, 0),
                LocalDateTime.of(2026, 11, 9, 0, 0), 300, List.of(new SoftenerHistoryReply.Point(
                LocalDateTime.of(2026, 11, 8, 10, 0), new BigDecimal("25.00"), new BigDecimal("25.0"),
                new BigDecimal("25.0"))))));

        webTestClient.get()
            .uri("/softener/history?metric=saltLevel&from=2026-11-08T00:00&to=2026-11-09T00:00")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {"metric": "saltLevel", "from": "2026-11-08T00:00:00", "to": "2026-11-09T00:00:00", "bucketSeconds": 300,
                 "points": [{"at": "2026-11-08T10:00:00", "avg": 25.00, "min": 25.0, "max": 25.0}]}
                """, JsonCompareMode.STRICT);
    }

    @Test
    void should_refuse_an_unknown_metric_with_its_code() {
        when(softenerHistoryService.history("salt", null, null))
            .thenReturn(Mono.error(ValveException.unknownMetric("salt")));

        webTestClient.get()
            .uri("/softener/history?metric=salt")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("UNKNOWN_METRIC")
            .jsonPath("$.errors[0].details").isEqualTo("There is no metric 'salt' of the softener");
    }

    @Test
    void should_answer_the_days_and_the_regenerations() {
        when(softenerHistoryService.days("2026-11-01", null)).thenReturn(Flux.just(
            new SoftenerDayReply(LocalDate.of(2026, 11, 8), new BigDecimal("310.0"))));
        when(softenerHistoryService.regenerations(null, null)).thenReturn(Flux.just(
            new SoftenerRegenerationReply(LocalDateTime.of(2026, 11, 9, 2, 0), null, null, true),
            new SoftenerRegenerationReply(LocalDateTime.of(2026, 11, 6, 2, 0), LocalDateTime.of(2026, 11, 6, 4, 0),
                LocalDateTime.of(2026, 11, 6, 2, 0), false)));

        webTestClient.get().uri("/softener/days?from=2026-11-01").exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [{"day": "2026-11-08", "litres": 310.0}]
                """, JsonCompareMode.STRICT);
        webTestClient.get().uri("/softener/regenerations").exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [{"startedAt": "2026-11-09T02:00:00", "running": true},
                 {"startedAt": "2026-11-06T02:00:00", "endedAt": "2026-11-06T04:00:00",
                  "deviceStartedAt": "2026-11-06T02:00:00", "running": false}]
                """, JsonCompareMode.STRICT);
    }

    @Test
    void should_refuse_a_range_with_its_code() {
        when(softenerHistoryService.days("2026-13-01", null))
            .thenReturn(Flux.error(ValveException.invalidRange("from must be YYYY-MM-DD, not '2026-13-01'")));

        webTestClient.get().uri("/softener/days?from=2026-13-01").exchange()
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("INVALID_RANGE");
    }

    @Test
    void should_answer_the_softener() {
        when(softenerService.current()).thenReturn(Mono.just(new SoftenerReply(true, true,
            LocalDateTime.of(2026, 11, 9, 10, 0), false,
            new SoftenerReply.Water(312.4, 410.0, 1890.5, 152003.2, 0.0),
            new SoftenerReply.Salt(45.0, 38, LocalDate.of(2026, 12, 17)),
            new SoftenerReply.Regeneration(3, LocalDate.of(2026, 11, 6), 412, "scheduled"),
            14.0, 239.7, 950, -58,
            new SoftenerReply.Alerts(false, true, false, false, false, false))));

        webTestClient.get()
            .uri("/softener")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {
                  "reachable": true,
                  "online": true,
                  "measuredAt": "2026-11-09T10:00:00",
                  "stale": false,
                  "water": {"usedTodayLitres": 312.4, "averageDailyLitres": 410.0, "treatedAvailableLitres": 1890.5,
                            "totalLitres": 152003.2, "flowLitresPerMinute": 0.0},
                  "salt": {"levelPercent": 45.0, "daysLeft": 38, "outOfSaltDate": "2026-12-17"},
                  "regeneration": {"daysSinceLast": 3, "lastAt": "2026-11-06", "total": 412, "status": "scheduled"},
                  "hardnessGpg": 14.0,
                  "hardnessMgCaco3": 239.7,
                  "daysInOperation": 950,
                  "signalDbm": -58,
                  "alerts": {"lowSalt": false, "leak": true, "excessiveWaterUse": false, "shutoffValve": false,
                             "resin": false, "error": false}
                }
                """, JsonCompareMode.STRICT);
    }

    //missing is never off: what the cloud left out is not in the body
    @Test
    void should_leave_out_what_is_not_known() {
        when(softenerService.current()).thenReturn(Mono.just(new SoftenerReply(false, null,
            LocalDateTime.of(2026, 11, 9, 10, 0), true,
            new SoftenerReply.Water(null, null, null, null, null),
            new SoftenerReply.Salt(null, null, null),
            new SoftenerReply.Regeneration(null, null, null, null),
            null, null, null, null,
            new SoftenerReply.Alerts(null, null, null, null, null, null))));

        webTestClient.get()
            .uri("/softener")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {"reachable": false, "measuredAt": "2026-11-09T10:00:00", "stale": true, "water": {}, "salt": {},
                 "regeneration": {}, "alerts": {}}
                """, JsonCompareMode.STRICT);
    }

    @Test
    void should_be_unavailable_before_the_first_reading() {
        when(softenerService.current()).thenReturn(Mono.error(ValveException.softenerUnavailable()));

        webTestClient.get()
            .uri("/softener")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("SOFTENER_UNAVAILABLE")
            .jsonPath("$.errors[0].message").isEqualTo("Water softener unavailable");
    }

    //HAS-400: a command of the regeneration answers 202 with what the cloud said
    @Test
    void should_accept_a_regeneration_tonight() {
        when(softenerCommandService.regenerate("tonight")).thenReturn(Mono.just(new SoftenerCommandReply(
            "regenerate tonight", "success", "Command sent", LocalDateTime.of(2026, 11, 9, 19, 0))));

        webTestClient.post()
            .uri("/softener/regeneration?when=tonight")
            .exchange()
            .expectStatus().isAccepted()
            .expectBody().json("""
                {"command": "regenerate tonight", "status": "success", "message": "Command sent",
                 "sentAt": "2026-11-09T19:00:00"}
                """, JsonCompareMode.STRICT);
    }

    @Test
    void should_accept_the_cancel_of_a_regeneration() {
        when(softenerCommandService.cancel()).thenReturn(Mono.just(new SoftenerCommandReply(
            "cancel the scheduled regeneration", null, null, LocalDateTime.of(2026, 11, 9, 19, 0))));

        webTestClient.delete()
            .uri("/softener/regeneration")
            .exchange()
            .expectStatus().isAccepted()
            .expectBody().json("""
                {"command": "cancel the scheduled regeneration", "sentAt": "2026-11-09T19:00:00"}
                """, JsonCompareMode.STRICT);
    }

    @Test
    void should_refuse_a_regeneration_while_one_goes_on() {
        when(softenerCommandService.regenerate(null)).thenReturn(Mono.error(ValveException.regenerationInProgress()));

        webTestClient.post()
            .uri("/softener/regeneration")
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("REGENERATION_IN_PROGRESS");
    }

    @Test
    void should_refuse_a_command_too_soon() {
        when(softenerCommandService.cancel()).thenReturn(Mono.error(ValveException.commandTooSoon(20)));

        webTestClient.delete()
            .uri("/softener/regeneration")
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("COMMAND_TOO_SOON");
    }

    @Test
    void should_answer_502_when_the_cloud_refused_the_command() {
        when(softenerCommandService.regenerate("now")).thenReturn(Mono.error(ValveException.softenerCommandFailed(500)));

        webTestClient.post()
            .uri("/softener/regeneration?when=now")
            .exchange()
            .expectStatus().isEqualTo(502)
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("SOFTENER_COMMAND_FAILED")
            .jsonPath("$.errors[0].message").isEqualTo("Softener command failed");
    }

    @Test
    void should_never_regenerate_on_a_get() {
        webTestClient.get()
            .uri("/softener/regeneration")
            .exchange()
            .expectStatus().is4xxClientError();
    }

    //HAS-399: the alerts of the iQua cloud for the alerts card of the dashboard, a field it left out left out
    @Test
    void should_answer_the_alerts_of_the_cloud() {
        when(softenerAlertService.alerts()).thenReturn(Mono.just(List.of(
            new SoftenerAlertReply("a1", "low_salt", "Low salt", "Add salt", "warning",
                LocalDateTime.of(2026, 11, 9, 8, 0), false),
            new SoftenerAlertReply("a0", null, "Service", null, null, null, true))));

        webTestClient.get()
            .uri("/softener/alerts")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [{"id": "a1", "type": "low_salt", "title": "Low salt", "message": "Add salt", "level": "warning",
                  "at": "2026-11-09T08:00:00", "read": false},
                 {"id": "a0", "title": "Service", "read": true}]
                """, JsonCompareMode.STRICT);
    }

    @Test
    void should_answer_502_when_the_cloud_gave_no_alerts() {
        when(softenerAlertService.alerts()).thenReturn(Mono.error(ValveException.softenerCloudFailed()));

        webTestClient.get()
            .uri("/softener/alerts")
            .exchange()
            .expectStatus().isEqualTo(502)
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("DEVICE_FAILED");
    }
}
