package cloud.cholewa.water.circulation;

import cloud.cholewa.water.circulation.CirculationRules.Stop;
import cloud.cholewa.water.client.WaterSensorClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static cloud.cholewa.water.circulation.CirculationServiceTest.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The monitor of the pump (HAS-404): the Uni read every 15 s, every pass while a run goes on, the run judged after every
//read and given up when the Uni is not heard past its limit
class CirculationMonitorTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    //19:00:00 in the house
    private static final Instant NOW = Instant.parse("2026-11-05T18:00:00Z");
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 5, 19, 0);

    private final WaterSensorClient client = mock(WaterSensorClient.class);
    private final CirculationService service = mock(CirculationService.class);
    private final CirculationPump pump = new CirculationPump();
    private Instant now = NOW;
    private CirculationMonitor sut;

    @BeforeEach
    void setUp() {
        when(client.status()).thenReturn(Mono.just(status(false, 45.0, 24.0)));
        when(service.stop(any(), any(), any(), any())).thenReturn(Mono.empty());
        when(service.closeOrphans()).thenReturn(Mono.just(true));
        final Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return HOUSE;
            }

            @Override
            public Clock withZone(final ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        sut = new CirculationMonitor(client, pump, service, new CirculationProperties(35.0, Duration.ofMinutes(3), 38.0,
            true), clock);
    }

    private void passAfter(final int seconds) {
        now = NOW.plusSeconds(seconds);
        sut.pass().as(StepVerifier::create).verifyComplete();
    }

    //idle: a read, then nothing until 15 s are over (a second early still reads - the delay counts from the end of a pass)
    @Test
    void should_read_the_uni_every_fifteen_seconds_while_idle() {
        passAfter(0);
        passAfter(5);
        passAfter(10);
        passAfter(14);
        passAfter(19);
        passAfter(24);
        passAfter(28);

        verify(client, times(3)).status();
        assertThat(pump.reading()).contains(new UniReading(false, 45.0, 24.0, AT.plusSeconds(28)));
    }

    //a run: every pass reads, and the return at its target stops it
    @Test
    void should_read_every_pass_and_stop_a_run_at_its_return() {
        final CirculationRun run = new CirculationRun(9L, AT, "manual", "dashboard", 24.0, true);
        passAfter(0);
        pump.started(run);
        when(client.status()).thenReturn(Mono.just(status(true, 45.0, 31.0)), Mono.just(status(true, 45.0, 35.5)));

        passAfter(5);
        passAfter(10);

        verify(client, times(3)).status();
        verify(service, times(1)).stop(run, new Stop("temperature", true), 35.5, AT.plusSeconds(10));
    }

    //a silent Uni: nothing judged until the limit and a minute are over, then the run is given up
    @Test
    void should_give_up_a_run_of_a_silent_uni() {
        final CirculationRun run = new CirculationRun(9L, AT, "manual", "dashboard", 24.0, true);
        pump.started(run);
        when(client.status()).thenReturn(Mono.error(new IllegalStateException("connection refused")));

        passAfter(200);
        verify(service, never()).stop(any(), any(), any(), any());

        passAfter(240);
        verify(service).stop(run, new Stop("failure", false), null, AT.plusSeconds(180));
    }

    //a silent Uni without a run: the pass ends quietly, the last read stays
    @Test
    void should_end_a_pass_quietly_when_the_uni_is_silent() {
        passAfter(0);
        when(client.status()).thenReturn(Mono.error(new IllegalStateException("connection refused")));

        passAfter(15);

        assertThat(pump.reading()).contains(new UniReading(false, 45.0, 24.0, AT));
        verify(service, never()).stop(any(), any(), any(), any());
    }

    //the runs of an earlier pod are ended at the first pass that manages it, never again
    @Test
    void should_end_the_runs_of_an_earlier_pod_once() {
        when(service.closeOrphans()).thenReturn(Mono.just(false), Mono.just(true));

        passAfter(0);
        passAfter(15);
        passAfter(30);

        verify(service, times(2)).closeOrphans();
        verify(client, times(3)).status();
    }

    //"now" is read inside the chain: the same Mono subscribed twice reads twice, 15 s apart
    @Test
    void should_read_the_clock_at_every_subscription() {
        final Mono<Void> pass = sut.pass();
        pass.as(StepVerifier::create).verifyComplete();
        now = NOW.plusSeconds(15);
        pass.as(StepVerifier::create).verifyComplete();

        verify(client, times(2)).status();
    }
}
