package cloud.cholewa.water.api;

import cloud.cholewa.home.model.SystemActiveReply;
import cloud.cholewa.water.model.CirculationStatus;
import cloud.cholewa.water.model.HotWaterStatus;
import cloud.cholewa.water.model.TemperatureReply;
import cloud.cholewa.water.service.WaterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

import static org.mockito.Mockito.when;

@WebFluxTest(WaterController.class)
class WaterControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private WaterService waterService;

    @Test
    void shouldReturnSystemActiveStatus() {
        when(waterService.queryWaterSystemActive())
            .thenReturn(Mono.just(SystemActiveReply.builder().active(true).build()));

        webTestClient.get()
            .uri("/status/active")
            .exchange()
            .expectStatus().isOk()
            .expectBody(SystemActiveReply.class);
    }

    //the JSON is the contract of the hot-water page of the web dashboard, so the whole body is
    //written out; measuredAt is what tells a current reading from the last one of a silent sensor
    @Test
    void shouldReturnSystemTemperature() {
        when(waterService.queryWaterSystemTemperature())
            .thenReturn(Mono.just(TemperatureReply.builder()
                .measuredAt(LocalDateTime.of(2026, 10, 9, 14, 21, 10))
                .water(new HotWaterStatus(46.81))
                .circulation(new CirculationStatus(26.44, false))
                .build()));

        webTestClient.get()
            .uri("/status/temperature")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json(
                """
                    {
                      "measuredAt": "2026-10-09T14:21:10",
                      "water": {"temperature": 46.81},
                      "circulation": {"temperature": 26.44, "pumpActive": false}
                    }
                    """, JsonCompareMode.STRICT
            );
    }

    //before the first reading there is no row: the answer stays a 200 without a body, which the
    //dashboard reads as "nothing measured yet"
    @Test
    void shouldReturnNoBodyBeforeTheFirstReading() {
        when(waterService.queryWaterSystemTemperature()).thenReturn(Mono.empty());

        webTestClient.get()
            .uri("/status/temperature")
            .exchange()
            .expectStatus().isOk()
            .expectBody().isEmpty();
    }
}
