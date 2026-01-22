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
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

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

    @Test
    void shouldReturnSystemTemperature() {
        when(waterService.queryWaterSystemTemperature())
            .thenReturn(Mono.just(TemperatureReply.builder()
                .water(new HotWaterStatus(50.0))
                .circulation(new CirculationStatus(40.0, false))
                .build()));

        webTestClient.get()
            .uri("/status/temperature")
            .exchange()
            .expectStatus().isOk()
            .expectBody(TemperatureReply.class);
    }
}
