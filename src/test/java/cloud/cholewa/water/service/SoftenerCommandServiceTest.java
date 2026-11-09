package cloud.cholewa.water.service;

import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.infrastructure.error.WaterError;
import cloud.cholewa.water.model.SoftenerCommandReply;
import cloud.cholewa.water.softener.IquaClient;
import cloud.cholewa.water.softener.IquaCommandResult;
import cloud.cholewa.water.softener.IquaException;
import cloud.cholewa.water.softener.Regeneration;
import cloud.cholewa.water.softener.SoftenerReader;
import cloud.cholewa.water.softener.SoftenerReading;
import cloud.cholewa.water.softener.SoftenerSamples;
import cloud.cholewa.water.softener.SoftenerTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The commands of the regeneration (HAS-400): the pairs the device offers sent once, the three refusals before anything is
//sent - unavailable, in progress, too soon - and the failure of the cloud as 502 with its status alone
class SoftenerCommandServiceTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 9, 10, 0);
    private static final IquaCommandResult ACCEPTED = new IquaCommandResult("success", "Command sent");

    private final SoftenerSamples.MovableClock clock = new SoftenerSamples.MovableClock();
    private final SoftenerReader reader = mock(SoftenerReader.class);
    private final SoftenerTracker tracker = mock(SoftenerTracker.class);
    private final IquaClient client = mock(IquaClient.class);
    private SoftenerCommandService sut;

    @BeforeEach
    void setUp() {
        when(reader.deviceId()).thenReturn(Optional.of("dev-1"));
        when(tracker.current()).thenReturn(new SoftenerTracker.State(new SoftenerReading(true, null, null, null, null, null,
            null, null, null, null, null, null, null, new SoftenerReading.Alerts(null, null, null, null, null, null)), T0,
            clock.instant(), true, 0));
        when(tracker.regeneration()).thenReturn(Regeneration.NONE);
        when(client.command(anyString(), anyString(), anyString())).thenReturn(Mono.just(ACCEPTED));
        sut = new SoftenerCommandService(reader, tracker, client, clock);
    }

    @Test
    void should_regenerate_now_by_default() {
        sut.regenerate(null).as(StepVerifier::create)
            .expectNext(new SoftenerCommandReply("regenerate now", "success", "Command sent", T0))
            .verifyComplete();

        verify(client).command("dev-1", "regenerate", "regenerate");
    }

    @Test
    void should_schedule_a_regeneration_tonight() {
        sut.regenerate("Tonight").as(StepVerifier::create)
            .expectNext(new SoftenerCommandReply("regenerate tonight", "success", "Command sent", T0))
            .verifyComplete();

        verify(client).command("dev-1", "regenerate", "schedule");
    }

    @Test
    void should_cancel_a_scheduled_regeneration() {
        sut.cancel().as(StepVerifier::create)
            .expectNext(new SoftenerCommandReply("cancel the scheduled regeneration", "success", "Command sent", T0))
            .verifyComplete();

        verify(client).command("dev-1", "regenerate", "cancel");
    }

    @Test
    void should_refuse_a_when_it_does_not_know() {
        sut.regenerate("tomorrow").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(((ValveException) failure).getError())
                .isEqualTo(WaterError.INVALID_COMMAND))
            .verify();

        verify(client, never()).command(anyString(), anyString(), anyString());
    }

    @Test
    void should_be_unavailable_without_a_reading() {
        when(tracker.current()).thenReturn(new SoftenerTracker.State(null, null, null, false, 0));

        sut.regenerate("now").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(((ValveException) failure).getError())
                .isEqualTo(WaterError.SOFTENER_UNAVAILABLE))
            .verify();
        verify(client, never()).command(anyString(), anyString(), anyString());
    }

    @Test
    void should_be_unavailable_before_the_softener_was_found() {
        when(reader.deviceId()).thenReturn(Optional.empty());

        sut.cancel().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(((ValveException) failure).getError())
                .isEqualTo(WaterError.SOFTENER_UNAVAILABLE))
            .verify();
    }

    @Test
    void should_refuse_while_the_softener_regenerates() {
        when(tracker.regeneration()).thenReturn(Regeneration.NONE.next(true, T0.minusMinutes(5), LocalTime.of(2, 0), T0));

        sut.regenerate("now").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(((ValveException) failure).getError())
                .isEqualTo(WaterError.REGENERATION_IN_PROGRESS))
            .verify();
        sut.cancel().as(StepVerifier::create).expectError(ValveException.class).verify();
        verify(client, never()).command(anyString(), anyString(), anyString());
    }

    //one command a minute at most, a refused one included - it may have been carried out
    @Test
    void should_refuse_a_command_within_a_minute_of_the_last() {
        when(client.command(anyString(), anyString(), anyString()))
            .thenReturn(Mono.error(new IquaException("The iQua cloud answered HTTP 500 for the command", 500)))
            .thenReturn(Mono.just(ACCEPTED));
        sut.regenerate("tonight").as(StepVerifier::create).expectError(ValveException.class).verify();

        clock.moveBy(Duration.ofSeconds(59));
        sut.cancel().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> {
                assertThat(((ValveException) failure).getError()).isEqualTo(WaterError.COMMAND_TOO_SOON);
                assertThat(failure).hasMessage("The last command of the softener was sent 59 s ago - one a minute at most");
            })
            .verify();

        clock.moveBy(Duration.ofSeconds(1));
        sut.cancel().as(StepVerifier::create).expectNextCount(1).verifyComplete();
    }

    //what the cloud said goes to the log, the caller gets its status alone
    @Test
    void should_answer_a_refused_command_with_the_status_alone() {
        when(client.command(anyString(), anyString(), anyString()))
            .thenReturn(Mono.error(new IquaException("The iQua cloud answered HTTP 422 for the command", 422)));

        sut.regenerate("now").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> {
                assertThat(((ValveException) failure).getError()).isEqualTo(WaterError.SOFTENER_COMMAND_FAILED);
                assertThat(failure).hasMessage("The iQua cloud refused the command of the softener with 422");
            })
            .verify();
    }

    @Test
    void should_answer_a_command_not_answered() {
        when(client.command(anyString(), anyString(), anyString()))
            .thenReturn(Mono.error(new IquaException("The iQua cloud did not answer for the command: TimeoutException")));

        sut.regenerate("now").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure)
                .hasMessage("The iQua cloud did not answer the command of the softener"))
            .verify();
    }

    //the pairs the house sends are those the real device offers in the actions of its detail - idle and scheduled
    @Test
    void should_send_only_pairs_the_real_device_offers() {
        final List<String> offered = List.of("/iqua/detail-or-summary.json", "/iqua/detail-scheduled.json").stream()
            .flatMap(resource -> StreamSupport.stream(SoftenerSamples.JSON.readTree(SoftenerSamples.resource(resource))
                .path("device").path("enriched_data").path("water_treatment").path("recharge").path("ui").path("actions")
                .spliterator(), false))
            .map(action -> action.path("command"))
            .map(command -> command.path("function").asString() + "/" + command.path("action").asString())
            .toList();

        assertThat(offered).contains("regenerate/regenerate", "regenerate/schedule", "regenerate/cancel");
        for (final SoftenerCommandService.Command command : SoftenerCommandService.Command.values()) {
            assertThat(offered).contains("regenerate/" + command.action());
        }
    }

    @Test
    void should_pin_the_actions_of_the_device() {
        assertThat(List.of(SoftenerCommandService.Command.values()))
            .extracting(SoftenerCommandService.Command::action)
            .containsExactly("regenerate", "schedule", "cancel");
        final JsonNode scheduled = SoftenerSamples.JSON.readTree(SoftenerSamples.resource("/iqua/detail-scheduled.json"));
        assertThat(scheduled.path("device").path("enriched_data").path("water_treatment").path("regeneration_status")
            .asString()).isEqualTo("scheduled");
    }
}
