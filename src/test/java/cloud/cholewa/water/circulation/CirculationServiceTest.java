package cloud.cholewa.water.circulation;

import cloud.cholewa.shelly.http.ShellyCallException;
import cloud.cholewa.shelly.http.ShellyGen1Client;
import cloud.cholewa.shelly.model.ExternalTemperatureValue;
import cloud.cholewa.shelly.model.Relay;
import cloud.cholewa.shelly.model.ShellyUniStatusResponse;
import cloud.cholewa.shelly.model.WifiStatus;
import cloud.cholewa.water.circulation.CirculationRules.Stop;
import cloud.cholewa.water.client.WaterSensorClient;
import cloud.cholewa.water.config.WaterSensorConfig;
import cloud.cholewa.water.database.model.CirculationRunEntity;
import cloud.cholewa.water.database.repository.CirculationRunRepository;
import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.infrastructure.error.WaterError;
import cloud.cholewa.water.model.CirculationReply;
import cloud.cholewa.water.model.CirculationRunReply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The circulation pump (HAS-404): a start reads the Uni, refuses a cold tank, sends turn=on with the timer of max-run once,
//holds and stores the run; a run going on is answered, not started again; a stop switches the relay off and ends the row
class CirculationServiceTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    //19:00:00 in the house
    private static final Instant NOW = Instant.parse("2026-11-05T18:00:00Z");
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 5, 19, 0);
    private static final String UNI = "192.0.2.47:80";

    private final AtomicReference<Clock> clock = new AtomicReference<>(Clock.fixed(NOW, HOUSE));
    private final WaterSensorClient client = mock(WaterSensorClient.class);
    private final ShellyGen1Client gen1 = mock(ShellyGen1Client.class);
    private final CirculationRunRepository repository = mock(CirculationRunRepository.class);
    private final CirculationPump pump = new CirculationPump();
    private CirculationService sut;

    static ShellyUniStatusResponse status(final boolean pumpOn, final Double tank, final Double returnTemperature) {
        final ShellyUniStatusResponse status = new ShellyUniStatusResponse()
            .wifiSta(new WifiStatus())
            .relays(List.of(new Relay().ison(false), new Relay().ison(pumpOn)));
        return status.extTemperature(Map.of("0", new ExternalTemperatureValue().tC(tank),
            "1", new ExternalTemperatureValue().tC(returnTemperature)));
    }

    @BeforeEach
    void setUp() {
        when(client.status()).thenReturn(Mono.just(status(false, 45.0, 24.5)));
        when(gen1.relay(anyString(), anyInt(), anyBoolean(), any())).thenReturn(Mono.just(new Relay().ison(true)));
        when(gen1.relay(anyString(), anyInt(), anyBoolean())).thenReturn(Mono.just(new Relay().ison(false)));
        when(repository.save(any())).thenAnswer(call -> {
            final CirculationRunEntity row = call.getArgument(0);
            return Mono.just(new CirculationRunEntity(9L, row.startedAt(), null, row.trigger(), row.source(), null,
                row.returnAtStart(), null));
        });
        when(repository.end(any(), any(), any(), any())).thenReturn(Mono.just(1));
        when(repository.findFirstByOrderByIdDesc()).thenReturn(Mono.empty());
        sut = new CirculationService(client, new WaterSensorConfig("192.0.2.47", 80), gen1, pump, repository,
            new CirculationProperties(35.0, Duration.ofMinutes(3), 38.0, true), new MovingClock(clock));
    }

    @Test
    void should_start_the_pump_for_max_run_and_store_the_run() {
        sut.start("dashboard").as(StepVerifier::create)
            .expectNext(new CirculationRunReply(AT, null, "manual", "dashboard", null, new BigDecimal("24.50"), null))
            .verifyComplete();

        verify(gen1).relay(UNI, 1, true, 180);
        verify(repository).save(new CirculationRunEntity(null, AT, null, "manual", "dashboard", null,
            new BigDecimal("24.50"), null));
        assertThat(pump.run()).contains(new CirculationRun(9L, AT, "manual", "dashboard", 24.5, true));
    }

    //a run going on is answered as it is: no second command, no second row
    @Test
    void should_answer_a_run_going_on_without_a_second_command() {
        sut.start("dashboard").then(sut.start("bruno")).as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.source()).isEqualTo("dashboard"))
            .verifyComplete();

        verify(gen1, times(1)).relay(anyString(), anyInt(), anyBoolean(), any());
        verify(repository, times(1)).save(any());
    }

    //two starts at once share one: the second waits for the read of the first
    @Test
    void should_share_one_start_between_two_at_once() {
        final Sinks.One<ShellyUniStatusResponse> read = Sinks.one();
        when(client.status()).thenReturn(read.asMono());

        final Mono<List<CirculationRunReply>> both = Flux.merge(sut.start("dashboard"), sut.start("bruno")).collectList();
        both.as(StepVerifier::create)
            .then(() -> read.tryEmitValue(status(false, 45.0, 24.5)))
            .assertNext(replies -> assertThat(replies).hasSize(2).allSatisfy(reply ->
                assertThat(reply.source()).isEqualTo("dashboard")))
            .verifyComplete();

        verify(gen1, times(1)).relay(anyString(), anyInt(), anyBoolean(), any());
    }

    @Test
    void should_refuse_a_cold_tank_without_a_command() {
        when(client.status()).thenReturn(Mono.just(status(false, 37.9, 22.0)));

        sut.start("dashboard").as(StepVerifier::create)
            .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(ValveException.class, refused -> {
                assertThat(refused.getError()).isEqualTo(WaterError.TANK_COLD);
                assertThat(refused.getHttpStatus().value()).isEqualTo(409);
                assertThat(refused.getMessage()).isEqualTo("The tank is at 37.9 C, below 38.0 C");
            }))
            .verify();

        verify(gen1, never()).relay(anyString(), anyInt(), anyBoolean(), any());
        assertThat(pump.run()).isEmpty();
    }

    //a Uni not read, or read without the tank: not cold - not known; 502 and no command
    @Test
    void should_refuse_a_start_when_the_tank_is_not_known() {
        when(client.status()).thenReturn(Mono.error(new IllegalStateException("connection refused")),
            Mono.just(status(false, null, 22.0)));

        for (int attempt = 0; attempt < 2; attempt++) {
            sut.start("dashboard").as(StepVerifier::create)
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(ValveException.class, refused -> {
                    assertThat(refused.getError()).isEqualTo(WaterError.DEVICE_FAILED);
                    assertThat(refused.getHttpStatus().value()).isEqualTo(502);
                }))
                .verify();
        }

        verify(gen1, never()).relay(anyString(), anyInt(), anyBoolean(), any());
    }

    @Test
    void should_refuse_a_source_that_is_not_a_short_word() {
        for (final String source : new String[]{null, "", "Dashboard", "a-source-far-too-long-for-it"}) {
            sut.start(source).as(StepVerifier::create)
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(ValveException.class, refused ->
                    assertThat(refused.getError()).isEqualTo(WaterError.INVALID_COMMAND)))
                .verify();
        }

        verify(client, never()).status();
    }

    //refused with a status: nothing was done - no run, no row
    @Test
    void should_hold_nothing_when_the_uni_refused_the_start() {
        when(gen1.relay(anyString(), anyInt(), anyBoolean(), any()))
            .thenReturn(Mono.error(new ShellyCallException("GET /relay/1?turn=on&timer=180 on " + UNI + ": 500", 500, null)));

        sut.start("dashboard").as(StepVerifier::create)
            .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(ValveException.class, refused -> {
                assertThat(refused.getError()).isEqualTo(WaterError.DEVICE_FAILED);
                assertThat(refused.getMessage()).isEqualTo("The circulation pump refused the command with 500");
            }))
            .verify();

        assertThat(pump.run()).isEmpty();
        verify(repository, never()).save(any());
    }

    //not answered: it may have been carried out - held and watched all the same, the caller gets the 502
    @Test
    void should_hold_a_start_the_uni_did_not_answer() {
        when(gen1.relay(anyString(), anyInt(), anyBoolean(), any()))
            .thenReturn(Mono.error(new ShellyCallException("GET /relay/1?turn=on&timer=180 on " + UNI + ": timeout")));

        sut.start("dashboard").as(StepVerifier::create)
            .expectErrorSatisfies(error -> assertThat(error).hasMessage("The circulation pump did not answer the command"))
            .verify();

        assertThat(pump.run()).hasValueSatisfying(run -> assertThat(run.answered()).isFalse());
        verify(repository).save(any());
    }

    //the row is a log: a run whose row failed goes on without an id, and its end is not stored
    @Test
    void should_start_when_the_row_cannot_be_stored() {
        doReturn(Mono.error(new IllegalStateException("pool exhausted"))).when(repository).save(any());

        sut.start("dashboard").as(StepVerifier::create).expectNextCount(1).verifyComplete();
        final CirculationRun run = pump.run().orElseThrow();
        assertThat(run.id()).isNull();

        sut.stop(run, new Stop("temperature", true), 35.5, AT.plusSeconds(60)).as(StepVerifier::create).verifyComplete();
        verify(repository, never()).end(any(), any(), any(), any());
        assertThat(pump.run()).isEmpty();
    }

    @Test
    void should_switch_the_pump_off_and_end_the_row() {
        sut.start("dashboard").as(StepVerifier::create).expectNextCount(1).verifyComplete();
        clock.set(Clock.fixed(NOW.plusSeconds(70), HOUSE));
        final CirculationRun run = pump.run().orElseThrow();

        sut.stop(run, new Stop("temperature", true), 35.25, AT.plusSeconds(68)).as(StepVerifier::create).verifyComplete();

        verify(gen1).relay(UNI, 1, false);
        verify(repository).end(9L, AT.plusSeconds(68), "temperature", new BigDecimal("35.25"));
        assertThat(pump.run()).isEmpty();
    }

    //the relay found off already: nothing to send; one end only - a second stop of the same run does nothing
    @Test
    void should_end_a_run_once_and_send_nothing_for_a_relay_found_off() {
        sut.start("dashboard").as(StepVerifier::create).expectNextCount(1).verifyComplete();
        final CirculationRun run = pump.run().orElseThrow();

        sut.stop(run, new Stop("device", false), null, AT.plusSeconds(30))
            .then(sut.stop(run, new Stop("limit", true), 30.0, AT.plusSeconds(35)))
            .as(StepVerifier::create).verifyComplete();

        verify(gen1, never()).relay(anyString(), anyInt(), anyBoolean());
        verify(repository, times(1)).end(any(), any(), any(), any());
        verify(repository).end(eq(9L), eq(AT.plusSeconds(30)), eq("device"), eq(null));
    }

    //an off that fails leaves the timer of the device to stop the pump: the run ends as failure
    @Test
    void should_end_a_run_as_failure_when_the_pump_is_not_switched_off() {
        when(gen1.relay(anyString(), anyInt(), anyBoolean()))
            .thenReturn(Mono.error(new ShellyCallException("GET /relay/1?turn=off on " + UNI + ": timeout")));
        sut.start("dashboard").as(StepVerifier::create).expectNextCount(1).verifyComplete();

        sut.stop(pump.run().orElseThrow(), new Stop("limit", true), 31.0, AT.plusSeconds(180)).as(StepVerifier::create)
            .verifyComplete();

        verify(repository).end(eq(9L), any(), eq("failure"), eq(new BigDecimal("31.00")));
    }

    @Test
    void should_answer_the_pump_of_a_run_with_its_trigger_and_the_last_row() {
        sut.start("dashboard").as(StepVerifier::create).expectNextCount(1).verifyComplete();
        pump.read(new UniReading(true, 45.0, 28.0, AT.plusSeconds(5)));
        clock.set(Clock.fixed(NOW.plusSeconds(6), HOUSE));
        when(repository.findFirstByOrderByIdDesc()).thenReturn(Mono.just(new CirculationRunEntity(9L, AT, null, "manual",
            "dashboard", null, new BigDecimal("24.50"), null)));

        sut.current().as(StepVerifier::create)
            .expectNext(new CirculationReply(true, AT, "manual", "dashboard", 28.0, 45.0, AT.plusSeconds(5),
                new CirculationRunReply(AT, null, "manual", "dashboard", null, new BigDecimal("24.50"), null)))
            .verifyComplete();
    }

    //a read older than a minute tells nothing; a failed read of the table leaves lastRun out
    @Test
    void should_leave_out_what_is_not_known() {
        pump.read(new UniReading(true, 45.0, 28.0, AT.minusSeconds(61)));
        when(repository.findFirstByOrderByIdDesc()).thenReturn(Mono.error(new IllegalStateException("pool exhausted")));

        sut.current().as(StepVerifier::create)
            .expectNext(new CirculationReply(null, null, null, null, null, null, null, null))
            .verifyComplete();
    }

    //a pump switched on by something else (the scene of the cloud): on since its first read, no trigger
    @Test
    void should_answer_a_pump_switched_on_by_something_else() {
        pump.read(new UniReading(true, 45.0, 26.0, AT.minusSeconds(20)));
        pump.read(new UniReading(true, 45.0, 27.0, AT.minusSeconds(5)));

        sut.current().as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.running()).isTrue();
                assertThat(reply.since()).isEqualTo(AT.minusSeconds(20));
                assertThat(reply.trigger()).isNull();
            })
            .verifyComplete();
    }

    //the runs an earlier pod left open, the one held now kept; a failure is told by false, for the monitor to try again
    @Test
    void should_end_the_runs_of_an_earlier_pod_but_the_one_held() {
        when(repository.endOrphans(any(), anyLong())).thenReturn(Mono.just(2));

        sut.closeOrphans().as(StepVerifier::create).expectNext(true).verifyComplete();
        verify(repository).endOrphans(CirculationService.NO_RUN, 180L);

        sut.start("dashboard").as(StepVerifier::create).expectNextCount(1).verifyComplete();
        sut.closeOrphans().as(StepVerifier::create).expectNext(true).verifyComplete();
        verify(repository).endOrphans(AT, 180L);

        when(repository.endOrphans(any(), anyLong())).thenReturn(Mono.error(new IllegalStateException("pool")));
        sut.closeOrphans().as(StepVerifier::create).expectNext(false).verifyComplete();
    }

    @Test
    void should_answer_the_runs_of_a_range_and_refuse_a_wrong_one() {
        when(repository.findStartedIn(AT.minusDays(1), AT)).thenReturn(Flux.just(new CirculationRunEntity(9L,
            AT.minusHours(2).plusNanos(5000), AT.minusHours(2).plusSeconds(70), "manual", "dashboard", "temperature",
            new BigDecimal("24.50"), new BigDecimal("35.10"))));

        sut.runs(AT.minusDays(1), AT).as(StepVerifier::create)
            .expectNext(List.of(new CirculationRunReply(AT.minusHours(2), AT.minusHours(2).plusSeconds(70), "manual",
                "dashboard", "temperature", new BigDecimal("24.50"), new BigDecimal("35.10"))))
            .verifyComplete();
        sut.runs(AT, AT.minusDays(1)).as(StepVerifier::create)
            .expectError(ResponseStatusException.class)
            .verify();
        sut.runs(AT.minusDays(32), AT).as(StepVerifier::create)
            .expectError(ResponseStatusException.class)
            .verify();
    }

    //a clock a test moves on
    private static final class MovingClock extends Clock {

        private final AtomicReference<Clock> current;

        private MovingClock(final AtomicReference<Clock> current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return current.get().getZone();
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return current.get().withZone(zone);
        }

        @Override
        public Instant instant() {
            return current.get().instant();
        }
    }
}
