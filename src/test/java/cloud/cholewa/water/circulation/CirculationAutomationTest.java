package cloud.cholewa.water.circulation;

import cloud.cholewa.water.light.LightClient;
import cloud.cholewa.water.light.LightRoom;
import cloud.cholewa.water.presence.PresenceClient;
import cloud.cholewa.water.presence.ResidentPresence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The automation of the circulation (HAS-405): a new motion or a light switched on in a bathroom, and a minute of a comfort
//window, start a run - while somebody is at home, the tank warm, the return cold, out of the pause and the quiet hours
class CirculationAutomationTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    //14:00 in the house - outside the comfort windows
    private static final Instant AFTERNOON = Instant.parse("2026-11-06T13:00:00Z");
    private static final String UP = "bathroom up";
    private static final String DOWN = "bathroom down";
    private static final LocalDateTime MOTION = LocalDateTime.of(2026, 11, 6, 13, 50);

    private final LightClient light = mock(LightClient.class);
    private final PresenceClient presence = mock(PresenceClient.class);
    private final CirculationService service = mock(CirculationService.class);
    private final CirculationPump pump = new CirculationPump();
    private Instant now = AFTERNOON;
    private CirculationAutomation sut;

    @BeforeEach
    void setUp() {
        rooms(false, MOTION);
        when(presence.residents()).thenAnswer(call -> Mono.just(List.of(
            new ResidentPresence("Aurelia", true, house().minusHours(2), house()))));
        when(service.lastSuccessfulEnd()).thenReturn(Mono.empty());
        when(service.startAutomatic(anyString(), anyString())).thenReturn(Mono.just(
            new CirculationRun(1L, house(), "motion", UP, 26.4, true)));
        sut = automation(AutomationRulesTest.AUTOMATION);
    }

    private CirculationAutomation automation(final AutomationProperties properties) {
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
        return new CirculationAutomation(light, presence, service, pump, properties, AutomationRulesTest.CIRCULATION, clock);
    }

    private LocalDateTime house() {
        return LocalDateTime.ofInstant(now, HOUSE);
    }

    private void rooms(final Boolean upOn, final LocalDateTime upMotion) {
        when(light.room(UP)).thenReturn(Mono.just(new LightRoom(UP, upOn, new LightRoom.Occupancy(upMotion))));
        when(light.room(DOWN)).thenReturn(Mono.just(new LightRoom(DOWN, false, new LightRoom.Occupancy(null))));
    }

    private void uni(final Boolean pumpOn, final Double tank, final Double returnTemperature) {
        pump.read(new UniReading(pumpOn, tank, returnTemperature, house().minusSeconds(5)));
    }

    private void pass() {
        sut.pass().as(StepVerifier::create).verifyComplete();
    }

    //the first pass learns what is there; a new motion after it starts a run
    @Test
    void should_start_a_run_on_a_new_motion_in_a_bathroom() {
        uni(false, 43.0, 26.4);
        pass();
        verify(service, never()).startAutomatic(anyString(), anyString());

        rooms(false, MOTION.plusMinutes(10));
        now = now.plusSeconds(10);
        uni(false, 43.0, 26.4);
        pass();

        verify(service).startAutomatic("motion", UP);
    }

    @Test
    void should_start_a_run_when_a_light_is_switched_on_in_a_bathroom() {
        uni(false, 43.0, 26.4);
        pass();

        when(light.room(DOWN)).thenReturn(Mono.just(new LightRoom(DOWN, true, new LightRoom.Occupancy(null))));
        now = now.plusSeconds(10);
        uni(false, 43.0, 26.4);
        pass();

        verify(service).startAutomatic("light", DOWN);
    }

    //once a minute of a window, not at every pass of it
    @Test
    void should_start_a_run_once_a_minute_of_a_comfort_window() {
        now = Instant.parse("2026-11-06T18:00:05Z");
        uni(false, 43.0, 26.4);
        pass();
        now = now.plusSeconds(10);
        uni(false, 43.0, 26.4);
        pass();

        verify(service, times(1)).startAutomatic("window", "19:00-23:30");

        now = now.plusSeconds(60);
        uni(false, 43.0, 26.4);
        pass();

        verify(service, times(2)).startAutomatic("window", "19:00-23:30");
    }

    @Test
    void should_not_start_a_run_in_an_empty_house_or_one_not_known() {
        uni(false, 43.0, 26.4);
        pass();
        when(presence.residents()).thenAnswer(call -> Mono.just(List.of(
            new ResidentPresence("Aurelia", false, house().minusHours(2), house()))));
        rooms(false, MOTION.plusMinutes(10));
        now = now.plusSeconds(10);
        uni(false, 43.0, 26.4);
        pass();

        when(presence.residents()).thenReturn(Mono.error(new IllegalStateException("presence-service down")));
        rooms(false, MOTION.plusMinutes(20));
        now = now.plusSeconds(10);
        uni(false, 43.0, 26.4);
        pass();

        verify(service, never()).startAutomatic(anyString(), anyString());
    }

    @Test
    void should_not_start_a_run_with_a_cold_tank_or_a_warm_return() {
        uni(false, 37.0, 26.4);
        pass();
        rooms(false, MOTION.plusMinutes(10));
        now = now.plusSeconds(10);
        uni(false, 37.0, 26.4);
        pass();

        rooms(false, MOTION.plusMinutes(20));
        now = now.plusSeconds(10);
        uni(false, 43.0, 31.0);
        pass();

        verify(service, never()).startAutomatic(anyString(), anyString());
        verify(presence, never()).residents();
    }

    @Test
    void should_not_start_a_run_in_the_pause_after_the_last_one() {
        when(service.lastSuccessfulEnd()).thenAnswer(call -> Mono.just(house().minusMinutes(10)));
        uni(false, 43.0, 26.4);
        pass();
        rooms(false, MOTION.plusMinutes(10));
        now = now.plusSeconds(10);
        uni(false, 43.0, 26.4);
        pass();

        verify(service, never()).startAutomatic(anyString(), anyString());
    }

    //the pause only spares heat: a table not read is no pause
    @Test
    void should_start_a_run_when_the_last_run_cannot_be_read() {
        when(service.lastSuccessfulEnd()).thenReturn(Mono.error(new IllegalStateException("database down")));
        uni(false, 43.0, 26.4);
        pass();
        rooms(false, MOTION.plusMinutes(10));
        now = now.plusSeconds(10);
        uni(false, 43.0, 26.4);
        pass();

        verify(service).startAutomatic("motion", UP);
    }

    @Test
    void should_not_start_a_run_in_the_quiet_hours() {
        now = Instant.parse("2026-11-06T23:00:00Z");
        uni(false, 43.0, 26.4);
        pass();
        rooms(false, MOTION.plusHours(10));
        now = now.plusSeconds(10);
        uni(false, 43.0, 26.4);
        pass();

        verify(service, never()).startAutomatic(anyString(), anyString());
    }

    @Test
    void should_read_nothing_while_the_automation_is_off() {
        final AutomationProperties on = AutomationRulesTest.AUTOMATION;
        sut = automation(new AutomationProperties(false, on.rooms(), on.startBelow(), on.pauseAfter(), on.quietFrom(),
            on.quietTo(), on.comfortWindows(), on.minRise(), on.noRiseRuns()));
        now = Instant.parse("2026-11-06T18:00:05Z");
        uni(false, 43.0, 26.4);
        pass();

        verify(light, never()).room(anyString());
        verify(service, never()).startAutomatic(anyString(), anyString());
    }

    //a room light-service does not answer triggers nothing, the other is still read
    @Test
    void should_go_on_with_the_other_room_when_one_is_not_read() {
        when(light.room(UP)).thenReturn(Mono.error(new IllegalStateException("light-service down")));
        uni(false, 43.0, 26.4);
        pass();
        when(light.room(DOWN)).thenReturn(Mono.just(new LightRoom(DOWN, true, new LightRoom.Occupancy(null))));
        now = now.plusSeconds(10);
        uni(false, 43.0, 26.4);
        pass();

        verify(service).startAutomatic("light", DOWN);
    }

    //a refused start (a cold tank read by the start itself) is logged; the pass goes on
    @Test
    void should_complete_the_pass_when_the_start_is_refused() {
        when(service.startAutomatic(anyString(), anyString())).thenReturn(Mono.error(new IllegalStateException("cold")));
        uni(false, 43.0, 26.4);
        pass();
        rooms(false, MOTION.plusMinutes(10));
        now = now.plusSeconds(10);
        uni(false, 43.0, 26.4);
        pass();

        verify(service).startAutomatic("motion", UP);
    }

    @Test
    void should_ask_nothing_more_while_the_pump_is_on() {
        uni(true, 43.0, 26.4);
        pass();
        rooms(false, MOTION.plusMinutes(10));
        now = now.plusSeconds(10);
        uni(true, 43.0, 26.4);
        pass();

        verify(service, never()).lastSuccessfulEnd();
        verify(service, never()).startAutomatic(anyString(), anyString());
    }

    @Test
    void should_keep_the_window_at_the_hour_of_the_house() {
        now = Instant.parse("2026-11-06T05:30:00Z");
        uni(false, 43.0, 26.4);
        pass();

        verify(service).startAutomatic("window", LocalTime.of(6, 0) + "-" + LocalTime.of(8, 0));
        verify(service, never()).startAutomatic("window", "19:00-23:30");
        verify(light, times(1)).room(UP);
        verify(light, times(1)).room(DOWN);
    }
}
