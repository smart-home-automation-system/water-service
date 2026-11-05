package cloud.cholewa.water.api;

import cloud.cholewa.water.config.ExceptionHandlerConfig;
import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.model.BillReply;
import cloud.cholewa.water.model.BillRequest;
import cloud.cholewa.water.service.BillService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

//The JSON of the bills is the contract of the bills view of the dashboard (HAS-318), so the bodies are written out
@WebFluxTest(BillController.class)
@Import(ExceptionHandlerConfig.class)
class BillControllerTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 5, 10, 0);
    private static final BillReply BILL = new BillReply(7L, LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 20),
        new BigDecimal("11.000"), new BigDecimal("1234.500"), new BigDecimal("98.76"), "Waterworks",
        new BigDecimal("11.020"), new BigDecimal("0.020"), new BigDecimal("0.2"), AT, AT);
    private static final String BILL_JSON = """
        {
          "id": 7,
          "periodFrom": "2026-09-29",
          "periodTo": "2026-10-20",
          "billedVolume": 11.000,
          "meterReading": 1234.500,
          "amount": 98.76,
          "note": "Waterworks",
          "measuredVolume": 11.020,
          "difference": 0.020,
          "differencePercent": 0.2,
          "createdAt": "2026-11-05T10:00:00",
          "updatedAt": "2026-11-05T10:00:00"
        }
        """;
    private static final String REQUEST_JSON = """
        {"periodFrom": "2026-09-29", "periodTo": "2026-10-20", "billedVolume": 11, "amount": 98.76}
        """;
    private static final BillRequest REQUEST = new BillRequest(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 20),
        new BigDecimal("11"), null, new BigDecimal("98.76"), null);

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private BillService billService;

    @Test
    void should_answer_the_bills() {
        when(billService.all()).thenReturn(Flux.just(BILL));

        webTestClient.get().uri("/bills").exchange()
            .expectStatus().isOk()
            .expectBody().json("[" + BILL_JSON + "]", JsonCompareMode.STRICT);
    }

    //missing is never 0: a period the house did not watch has no measurement
    @Test
    void should_leave_out_what_is_not_known() {
        when(billService.one(7L)).thenReturn(Mono.just(new BillReply(7L, LocalDate.of(2026, 9, 29),
            LocalDate.of(2026, 10, 20), new BigDecimal("11.000"), null, null, null, null, null, null, AT, AT)));

        webTestClient.get().uri("/bills/7").exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {"id": 7, "periodFrom": "2026-09-29", "periodTo": "2026-10-20", "billedVolume": 11.000,
                 "createdAt": "2026-11-05T10:00:00", "updatedAt": "2026-11-05T10:00:00"}
                """, JsonCompareMode.STRICT);
    }

    @Test
    void should_enter_a_bill() {
        when(billService.create(REQUEST)).thenReturn(Mono.just(BILL));

        webTestClient.post().uri("/bills").contentType(MediaType.APPLICATION_JSON).bodyValue(REQUEST_JSON).exchange()
            .expectStatus().isCreated()
            .expectBody().json(BILL_JSON, JsonCompareMode.STRICT);
    }

    @Test
    void should_replace_and_delete_a_bill() {
        when(billService.update(7L, REQUEST)).thenReturn(Mono.just(BILL));
        when(billService.delete(7L)).thenReturn(Mono.empty());

        webTestClient.put().uri("/bills/7").contentType(MediaType.APPLICATION_JSON).bodyValue(REQUEST_JSON).exchange()
            .expectStatus().isOk();
        webTestClient.delete().uri("/bills/7").exchange()
            .expectStatus().isNoContent();
    }

    @Test
    void should_refuse_with_the_code_of_its_cause() {
        when(billService.create(any())).thenReturn(Mono.error(ValveException.invalidBill("The period overlaps the bill 3")));
        when(billService.update(eq(8L), any())).thenReturn(Mono.error(ValveException.notFoundBill(8L)));

        webTestClient.post().uri("/bills").contentType(MediaType.APPLICATION_JSON).bodyValue(REQUEST_JSON).exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("INVALID_BILL")
            .jsonPath("$.errors[0].details").isEqualTo("The period overlaps the bill 3");
        webTestClient.put().uri("/bills/8").contentType(MediaType.APPLICATION_JSON).bodyValue(REQUEST_JSON).exchange()
            .expectStatus().isNotFound()
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("NOT_FOUND_BILL");
    }
}
