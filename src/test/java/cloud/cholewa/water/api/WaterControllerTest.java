package cloud.cholewa.water.api;

import cloud.cholewa.home.model.SystemActiveReply;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(WaterController.class)
class WaterControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void shouldReturnSystemActiveStatus() {
        webTestClient.get()
            .uri("/status/active")
            .exchange()
            .expectStatus().isOk()
            .expectBody(SystemActiveReply.class);
    }
}
