package cloud.cholewa.water.command;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.shelly.http.ShellyCallException;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import cloud.cholewa.shelly.model.BooleanStatus;
import cloud.cholewa.shelly.model.NumberStatus;
import cloud.cholewa.water.database.model.WaterValveCommandEntity;
import cloud.cholewa.water.database.repository.WaterValveCommandRepository;
import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.infrastructure.error.WaterError;
import cloud.cholewa.water.model.ValveCommandReply;
import cloud.cholewa.water.registry.ValveRegistry;
import cloud.cholewa.water.registry.WaterValve;
import cloud.cholewa.water.valve.ValveProperties;
import cloud.cholewa.water.valve.ValveReconciliation;
import cloud.cholewa.water.valve.ValveTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The command of the water valve (HAS-321): Boolean.Set once, the command stored and confirmed by the report of the
//valve, the refusals with their codes, the third unconfirmed command in a row an alert
class ValveCommandServiceTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    //18:05:01 in the house
    private static final Instant NOW = Instant.parse("2026-11-05T17:05:01Z");
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 5, 18, 5, 1);
    private static final WaterValve VALVE = new WaterValve(139L, "neowatervalve-a1b2c3d4e5f6", "192.0.2.15");

    private final Clock clock = Clock.fixed(NOW, HOUSE);
    private final ValveRegistry registry = mock(ValveRegistry.class);
    private final ShellyRpcClient rpc = mock(ShellyRpcClient.class);
    private final WaterValveCommandRepository repository = mock(WaterValveCommandRepository.class);
    private final ValveCommands commands = new ValveCommands(repository);
    private final ValveTracker tracker = new ValveTracker(clock, commands);
    private final ValveReconciliation reconciliation = mock(ValveReconciliation.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private ValveCommandService sut;

    @BeforeEach
    void setUp() {
        when(registry.isKnown()).thenReturn(true);
        when(registry.current()).thenReturn(Optional.of(VALVE));
        when(rpc.booleanSet(anyString(), any(Integer.class), any(Boolean.class))).thenReturn(Mono.empty());
        when(repository.save(any())).thenAnswer(call -> {
            final WaterValveCommandEntity row = call.getArgument(0);
            return Mono.just(new WaterValveCommandEntity(7L, row.commandedAt(), row.state(), row.source(), null));
        });
        when(repository.confirm(any(), any())).thenReturn(Mono.just(1));
        when(reconciliation.readNow()).thenReturn(Mono.empty());
        when(publisher.publishAlert(anyString())).thenReturn(Mono.empty());
        sut = new ValveCommandService(registry, rpc, repository, commands, tracker, reconciliation, publisher,
            new ValveProperties(Duration.ofMinutes(5)), clock);
    }

    //close is Boolean.Set false of boolean:200, the command stored and waiting: lastCommandConfirmed false
    @Test
    void should_send_store_and_wait_for_the_report() {
        sut.command("close", "dashboard").as(StepVerifier::create)
            .expectNext(new ValveCommandReply("close", "dashboard", AT))
            .verifyComplete();

        verify(rpc).booleanSet("192.0.2.15", 200, false);
        verify(repository).save(new WaterValveCommandEntity(null, AT, "close", "dashboard", null));
        assertThat(commands.lastConfirmed()).isFalse();
        assertThat(commands.current().pending()).isEqualTo(new ValveCommand(7L, false, "dashboard", AT));
    }

    //the report of the valve closed confirms it, and its row
    @Test
    void should_be_confirmed_by_the_report_of_the_valve() {
        sut.command("close", "dashboard").as(StepVerifier::create).expectNextCount(1).verifyComplete();

        tracker.report(200, BooleanStatus.builder().value(true).build());
        assertThat(commands.lastConfirmed()).isFalse();
        tracker.report(201, NumberStatus.builder().value(490.0).build());
        tracker.report(200, BooleanStatus.builder().value(false).build());

        assertThat(commands.lastConfirmed()).isTrue();
        verify(repository).confirm(7L, AT);
    }

    //the command is taken whether its row is stored or not
    @Test
    void should_take_a_command_the_database_did_not_store() {
        doReturn(Mono.error(new IllegalStateException("database down"))).when(repository).save(any());

        sut.command("open", "dashboard").as(StepVerifier::create).expectNextCount(1).verifyComplete();

        tracker.report(200, BooleanStatus.builder().value(true).build());
        assertThat(commands.lastConfirmed()).isTrue();
        verify(repository, never()).confirm(any(), any());
    }

    @ParameterizedTest
    @CsvSource(value = {"shut,dashboard", "null,dashboard", "OPEN,dashboard", "open,null", "open,Dashboard",
        "open,a-source-of-more-than-twenty"}, nullValues = "null")
    void should_refuse_an_invalid_command(final String state, final String source) {
        sut.command(state, source).as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(ValveException.class,
                refusal -> assertThat(refusal.getError()).isEqualTo(WaterError.INVALID_COMMAND)))
            .verify();

        verify(rpc, never()).booleanSet(anyString(), any(Integer.class), any(Boolean.class));
    }

    @Test
    void should_refuse_while_no_valve_is_known() {
        when(registry.isKnown()).thenReturn(false);

        sut.command("close", "dashboard").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(ValveException.class,
                refusal -> assertThat(refusal.getError()).isEqualTo(WaterError.REGISTRY_UNAVAILABLE)))
            .verify();

        when(registry.isKnown()).thenReturn(true);
        when(registry.current()).thenReturn(Optional.empty());

        sut.command("close", "dashboard").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(ValveException.class,
                refusal -> assertThat(refusal.getError()).isEqualTo(WaterError.NOT_FOUND_VALVE)))
            .verify();
    }

    //the valve refused: DEVICE_FAILED with its status alone, nothing stored, nothing waits
    @Test
    void should_answer_device_failed_when_the_valve_refuses() {
        when(rpc.booleanSet(anyString(), any(Integer.class), any(Boolean.class)))
            .thenReturn(Mono.error(new ShellyCallException("Boolean.Set of 192.0.2.15 answered 500: boom", 500, null)));

        sut.command("close", "dashboard").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(ValveException.class, refusal -> {
                assertThat(refusal.getError()).isEqualTo(WaterError.DEVICE_FAILED);
                assertThat(refusal.getMessage()).isEqualTo("The water valve refused the command with 500");
            }))
            .verify();

        verify(repository, never()).save(any());
        assertThat(commands.lastConfirmed()).isNull();
    }

    //no answer: it may have been carried out - stored and waiting like a taken one, the caller still gets the 502
    @Test
    void should_store_and_watch_a_command_the_valve_did_not_answer() {
        when(rpc.booleanSet(anyString(), any(Integer.class), any(Boolean.class)))
            .thenReturn(Mono.error(new ShellyCallException("Boolean.Set of 192.0.2.15 timed out")));

        sut.command("close", "dashboard").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(ValveException.class,
                refusal -> assertThat(refusal.getError()).isEqualTo(WaterError.DEVICE_FAILED)))
            .verify();

        verify(repository).save(new WaterValveCommandEntity(null, AT, "close", "dashboard", null));
        tracker.report(200, BooleanStatus.builder().value(false).build());
        assertThat(commands.lastConfirmed()).isTrue();
    }

    //a valve that never answers is cut off - the caller gets DEVICE_FAILED, not a hung request
    @Test
    void should_cut_off_a_valve_that_never_answers() {
        when(rpc.booleanSet(anyString(), any(Integer.class), any(Boolean.class))).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(() -> sut.command("close", "dashboard"))
            .expectSubscription()
            .thenAwait(ValveCommandService.COMMAND_TIMEOUT)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(ValveException.class,
                refusal -> assertThat(refusal.getMessage()).isEqualTo("The water valve did not answer the command")))
            .verify();
    }

    //nothing answers within the window: the valve is read once on the way, unconfirmed counted, the third an alert
    @Test
    void should_alert_the_third_unconfirmed_command_in_a_row() {
        tracker.report(202, NumberStatus.builder().value(12.9).build());

        for (int run = 1; run <= 3; run++) {
            final ValveCommand command = new ValveCommand((long) run, false, "dashboard", AT);
            commands.issued(command);
            StepVerifier.withVirtualTime(() -> sut.confirmation(command))
                .expectSubscription()
                .thenAwait(ValveCommand.WINDOW)
                .verifyComplete();
        }

        assertThat(commands.current().inARow()).isEqualTo(3);
        assertThat(commands.lastConfirmed()).isFalse();
        verify(reconciliation, times(3)).readNow();
        verify(publisher).publishAlert("The water valve has not confirmed 3 commands in a row: the last one, close by "
            + "dashboard at 18:05, reported nothing within 60 s.");
        assertThat(commands.current().announced()).isTrue();
    }

    //a valve that is not heard: the offline alarm speaks for it, no alert of the commands
    @Test
    void should_not_alert_while_the_valve_is_not_heard() {
        for (int run = 1; run <= 3; run++) {
            final ValveCommand command = new ValveCommand((long) run, true, "dashboard", AT);
            commands.issued(command);
            StepVerifier.withVirtualTime(() -> sut.confirmation(command))
                .expectSubscription()
                .thenAwait(ValveCommand.WINDOW)
                .verifyComplete();
        }

        verify(publisher, never()).publishAlert(anyString());
        assertThat(commands.current().announced()).isFalse();
    }
}
