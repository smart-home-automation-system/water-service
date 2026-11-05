package cloud.cholewa.water.api;

import cloud.cholewa.water.config.ExceptionHandlerConfig;
import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.model.ConsumptionReply;
import cloud.cholewa.water.model.ImportedDay;
import cloud.cholewa.water.service.ConsumptionImportService;
import cloud.cholewa.water.service.ConsumptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

//The JSON of GET /consumption is the contract of the water page of the dashboard and of the bills (HAS-317)
@WebFluxTest(ConsumptionController.class)
@Import(ExceptionHandlerConfig.class)
class ConsumptionControllerTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 11, 5, 0, 0);

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ConsumptionService consumptionService;

    @MockitoBean
    private ConsumptionImportService consumptionImportService;

    @Test
    void should_answer_the_consumption() {
        when(consumptionService.consumption("day", "2026-11-05")).thenReturn(Mono.just(new ConsumptionReply("day", DAY,
            DAY.plusDays(1), "m3", 0.124, 0.2, List.of(new ConsumptionReply.Bucket(DAY.plusHours(7), 0.123)))));

        webTestClient.get()
            .uri("/consumption?range=day&at=2026-11-05")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {
                  "range": "day",
                  "from": "2026-11-05T00:00:00",
                  "to": "2026-11-06T00:00:00",
                  "unit": "m3",
                  "total": 0.124,
                  "previousTotal": 0.2,
                  "buckets": [{"at": "2026-11-05T07:00:00", "volume": 0.123}]
                }
                """, JsonCompareMode.STRICT);
    }

    //missing is never 0: a total nobody watched is left out, the buckets stay a list
    @Test
    void should_leave_out_the_totals_not_known() {
        when(consumptionService.consumption("month", null)).thenReturn(Mono.just(new ConsumptionReply("month",
            DAY.withDayOfMonth(1), DAY.withDayOfMonth(1).plusMonths(1), "m3", null, null, List.of())));

        webTestClient.get()
            .uri("/consumption?range=month")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {"range": "month", "from": "2026-11-01T00:00:00", "to": "2026-12-01T00:00:00", "unit": "m3", "buckets": []}
                """, JsonCompareMode.STRICT);
    }

    @Test
    void should_answer_and_replace_the_imported_days() {
        final List<ImportedDay> days = List.of(new ImportedDay(LocalDate.of(2026, 10, 1), new BigDecimal("0.512")));
        when(consumptionImportService.all()).thenReturn(Flux.fromIterable(days));
        when(consumptionImportService.replace(days)).thenReturn(Mono.empty());

        webTestClient.get().uri("/consumption/imported").exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [{"day": "2026-10-01", "volume": 0.512}]
                """, JsonCompareMode.STRICT);
        webTestClient.put().uri("/consumption/imported").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                [{"day": "2026-10-01", "volume": 0.512}]
                """)
            .exchange()
            .expectStatus().isNoContent();
    }

    @Test
    void should_refuse_an_import_with_its_code() {
        when(consumptionImportService.replace(any()))
            .thenReturn(Mono.error(ValveException.invalidImport("The day 2026-11-05 is sent twice")));

        webTestClient.put().uri("/consumption/imported").contentType(MediaType.APPLICATION_JSON).bodyValue("[]")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("INVALID_IMPORT");
    }

    //a missing range is refused by the service with its code, never by WebFlux without one
    @Test
    void should_refuse_with_the_code_of_its_cause() {
        when(consumptionService.consumption(isNull(), any()))
            .thenReturn(Mono.error(ValveException.invalidRange("The range must be day, week, month or year, not 'null'")));

        webTestClient.get()
            .uri("/consumption")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("INVALID_RANGE")
            .jsonPath("$.errors[0].message").isEqualTo("Invalid consumption range");
    }
}
