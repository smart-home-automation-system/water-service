package cloud.cholewa.water.api;

import cloud.cholewa.water.config.ExceptionHandlerConfig;
import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.model.SoftenerReply;
import cloud.cholewa.water.service.SoftenerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

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

    @Test
    void should_answer_the_softener() {
        when(softenerService.current()).thenReturn(Mono.just(new SoftenerReply(true, true,
            LocalDateTime.of(2026, 11, 9, 10, 0), false,
            new SoftenerReply.Water(312.4, 410.0, 1890.5, 152003.2, 0.0),
            new SoftenerReply.Salt(45.0, 38, LocalDate.of(2026, 12, 17)),
            new SoftenerReply.Regeneration(3, LocalDate.of(2026, 11, 6), 412),
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
                  "regeneration": {"daysSinceLast": 3, "lastAt": "2026-11-06", "total": 412},
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
            new SoftenerReply.Regeneration(null, null, null),
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
}
