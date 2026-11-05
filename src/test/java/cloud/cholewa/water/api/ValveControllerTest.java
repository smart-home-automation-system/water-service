package cloud.cholewa.water.api;

import cloud.cholewa.water.config.ExceptionHandlerConfig;
import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.model.CounterReply;
import cloud.cholewa.water.model.ValveReply;
import cloud.cholewa.water.service.ValveService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

import static org.mockito.Mockito.when;

//The JSON of GET /valve is the contract of the water page of the dashboard (HAS-316), so the bodies are written out
@WebFluxTest(ValveController.class)
@Import(ExceptionHandlerConfig.class)
class ValveControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ValveService valveService;

    @Test
    void should_answer_the_valve() {
        final LocalDateTime at = LocalDateTime.of(2026, 11, 4, 19, 51, 20);
        when(valveService.current())
            .thenReturn(Mono.just(new ValveReply("open", 12.0, 4.95, 12.9, new CounterReply(61.666, at), at)));

        webTestClient.get()
            .uri("/valve")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {
                  "state": "open",
                  "flowRate": 12.0,
                  "pressure": 4.95,
                  "temperature": 12.9,
                  "counter": {"total": 61.666, "measuredAt": "2026-11-04T19:51:20"},
                  "measuredAt": "2026-11-04T19:51:20"
                }
                """, JsonCompareMode.STRICT);
    }

    //missing is never off: a value not known is left out, not written as null
    @Test
    void should_leave_out_what_is_not_known() {
        when(valveService.current()).thenReturn(Mono.just(new ValveReply(null, null, null, null, null, null)));

        webTestClient.get()
            .uri("/valve")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("{}", JsonCompareMode.STRICT);
    }

    @Test
    void should_refuse_with_the_code_of_its_cause() {
        when(valveService.current()).thenReturn(Mono.error(ValveException.notFound()));

        webTestClient.get()
            .uri("/valve")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("NOT_FOUND_VALVE");

        when(valveService.current()).thenReturn(Mono.error(ValveException.registryUnavailable()));

        webTestClient.get()
            .uri("/valve")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("REGISTRY_UNAVAILABLE");
    }
}
